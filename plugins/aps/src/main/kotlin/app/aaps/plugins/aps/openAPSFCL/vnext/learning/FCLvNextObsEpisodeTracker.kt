package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Episode(
    val id: Long,
    val startTime: DateTime,
    val endTime: DateTime?,
    val isNight: Boolean,

    val excluded: Boolean,
    val exclusionReason: ExclusionReason?,

    val qualityScore: Double
)

enum class ExclusionReason {
    RESCUE_CONFIRMED,
    DOWNTREND_LOCKED,
    MANUAL_BOLUS,
    DATA_INSUFFICIENT
}

sealed class EpisodeEvent {
    data class Started(val episode: Episode) : EpisodeEvent()
    data class Finished(val episode: Episode) : EpisodeEvent()
}

/**
 * EpisodeTracker (Optie A)
 *
 * Idee:
 * - Start pas als stijging "meal-like" is (slope + amplitude + duur)
 * - Maar startTime wordt retro-actief teruggezet naar laatste dal/stabiel punt
 * - Episode blijft open in TAIL zolang er nog IOB is (om hypo mee te nemen)
 * - Als tijdens TAIL een nieuwe meal-like stijging start: split
 */
class EpisodeTracker {

    private enum class State { IDLE, CANDIDATE, ACTIVE, TAIL }

    private var state: State = State.IDLE
    private var activeEpisode: Episode? = null
    private var nextId: Long = 1L

    // buffers voor retro-start en dal-detectie
    private data class Tick(
        val time: DateTime,
        val bg: Double,          // mmol/L
        val slope: Double,       // mmol/L/hr (liefst EWMA)
        val iob: Double,         // U
        val deltaToTarget: Double
    )

    private val buf: ArrayDeque<Tick> = ArrayDeque()

    // candidate tracking
    private var candidateSince: DateTime? = null
    private var candidateRetroStart: DateTime? = null

    // tail tracking
    private var tailSince: DateTime? = null

    // legacy fallback: als signals uit zijn, tellen we ticks
    private var inactiveTicks: Int = 0

    fun onFiveMinuteTick(
        now: DateTime,
        isNight: Boolean,

        // bestaande signalen
        peakActive: Boolean,
        mealSignalActive: Boolean,
        prePeakCommitWindow: Boolean,

        rescueConfirmed: Boolean,
        downtrendLocked: Boolean,

        // nieuw
        forceMealConfirm: Boolean,
        manualBolusDetected: Boolean,

        // ⬇️ NIEUW (ruwe context)
        bgMmol: Double,
        targetMmol: Double,
        currentIob: Double,

        slope: Double,
        acceleration: Double,
        deltaToTarget: Double,
        consistency: Double
    ): EpisodeEvent?
 {

        // Exclusion heeft altijd prioriteit: als dit gebeurt willen we episode afsluiten (excluded)
        // (Je kunt later beslissen of je in plaats daarvan "mark" wilt doen en laten doorlopen.)
     val exclusion = detectExclusion(rescueConfirmed, downtrendLocked, manualBolusDetected)


     // Buffer vullen als we echte BG hebben; anders niet (degraded mode)
     val hasBg = bgMmol.isFinite()
     val hasIob = currentIob.isFinite()

     if (hasBg) {
         buf.addLast(
             Tick(
                 time = now,
                 bg = bgMmol,
                 slope = slope,
                 iob = if (hasIob) currentIob else Double.NaN,
                 deltaToTarget = deltaToTarget
             )
         )
         while (buf.size > BUFFER_MAX_TICKS) buf.removeFirst()
     }


        val anyStartSignal = peakActive || mealSignalActive || prePeakCommitWindow

        // legacy inactiveTicks (blijft nuttig als bg/iob niet ingevuld zijn)
        inactiveTicks = if (anyStartSignal) 0 else inactiveTicks + 1

        // ─────────────────────────────
        // Exclusion: sluit episode af (excluded) of ignore als IDLE
        // ─────────────────────────────
        if (exclusion != null) {
            if (activeEpisode != null) {
                val finished = activeEpisode!!.copy(
                    endTime = now,
                    excluded = true,
                    exclusionReason = exclusion
                )
                resetAll()
                return EpisodeEvent.Finished(finished)
            }
            // IDLE: geen episode om te sluiten
            resetCandidateOnly()
            return null
        }

        // ─────────────────────────────
        // Main state machine
        // ─────────────────────────────
        return when (state) {

            State.IDLE -> {
                // In IDLE: alleen starten via meal-like detect (Optie A).
                // Als we nog geen BG hebben: degraded fallback op signalen (zoals je oude gedrag).
                if (!hasBg) {
                    // degraded fallback (oude gedrag): start zodra een signaal aan gaat
                    if (anyStartSignal) {
                        val ep = Episode(
                            id = nextId++,
                            startTime = now,
                            endTime = null,
                            isNight = isNight,
                            excluded = false,
                            exclusionReason = null,
                            qualityScore = clamp(consistency)
                        )
                        activeEpisode = ep
                        state = State.ACTIVE
                        return EpisodeEvent.Started(ep)
                    }
                    return null
                }

                // 0) HARD start via bolus-trigger (forceMealConfirm)
               // -> start meteen een episode, met retroStart als we BG hebben
                if (forceMealConfirm) {
                    val startAt =
                        if (hasBg) (findRetroStart(now) ?: now)
                        else now

                    val ep = Episode(
                        id = nextId++,
                        startTime = startAt,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp(consistency)
                    )
                    activeEpisode = ep
                    state = State.ACTIVE
                    // reset candidate vars (buffer behouden)
                    candidateSince = null
                    candidateRetroStart = null
                    return EpisodeEvent.Started(ep)
                }

// 1) normal: detect candidate
                if (isMealCandidate(now, slope, mealSignalActive)) {
                    state = State.CANDIDATE
                    candidateSince = now
                    candidateRetroStart = findRetroStart(now)
                }
                null

            }

            State.CANDIDATE -> {
                // Als BG data ontbreekt: terug naar IDLE (we willen geen foute starts)
                if (!hasBg) {
                    resetAll()
                    return null
                }

                // Candidate timeout: als stijging wegvalt, reset
                if (!isMealCandidate(now, slope, mealSignalActive)) {
                    // kleine hysteresis: 1 tick tolereren
                    // -> gebruik legacy inactiveTicks als extra guard
                    if (inactiveTicks >= 2) {
                        resetAll()
                    }
                    return null
                }

                // Confirm? (ook als forceMealConfirm actief is)
                if (forceMealConfirm || isMealConfirmed(now, slope, mealSignalActive)) {
                    val startAt = candidateRetroStart ?: now
                    val ep = Episode(
                        id = nextId++,
                        startTime = startAt,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp(consistency)
                    )
                    activeEpisode = ep
                    state = State.ACTIVE
                    // candidate vars resetten (maar buffer behouden)
                    candidateSince = null
                    candidateRetroStart = null
                    return EpisodeEvent.Started(ep)
                }

                null
            }

            State.ACTIVE -> {
                val ep = activeEpisode ?: run {
                    // should not happen, but safe
                    resetAll()
                    return null
                }

                // Max duur failsafe
                if (minutesBetween(ep.startTime, now) > MAX_EPISODE_MINUTES) {
                    val finished = ep.copy(endTime = now)
                    resetAll()
                    return EpisodeEvent.Finished(finished)
                }

                // Wanneer naar TAIL?
                val goTail =
                    // indien we echte BG hebben: daling/terug naar stabiel
                    (hasBg && shouldEnterTail(now))
                        // fallback: als signals een tijd uit zijn
                        || (!hasBg && inactiveTicks >= END_INACTIVE_TICKS && minutesBetween(ep.startTime, now) >= MIN_EPISODE_MINUTES)

                if (goTail) {
                    state = State.TAIL
                    tailSince = now
                }

                null
            }

            State.TAIL -> {
                val ep = activeEpisode ?: run {
                    resetAll()
                    return null
                }

                // Max duur failsafe
                if (minutesBetween(ep.startTime, now) > MAX_EPISODE_MINUTES) {
                    val finished = ep.copy(endTime = now)
                    resetAll()
                    return EpisodeEvent.Finished(finished)
                }

                // 1) Re-entry: nieuwe meal-stijging tijdens tail => split
                if (hasBg && isReEntryConfirmed(now, slope, mealSignalActive)) {
                    val splitPoint = findSplitPoint(now) ?: now

                    // close current at splitPoint
                    val finished = ep.copy(endTime = splitPoint)

                    // start new episode with retroStart = splitPoint (jouw wens)
                    val newEp = Episode(
                        id = nextId++,
                        startTime = splitPoint,
                        endTime = null,
                        isNight = isNight,
                        excluded = false,
                        exclusionReason = null,
                        qualityScore = clamp(consistency)
                    )

                    // set as active again
                    activeEpisode = newEp
                    state = State.ACTIVE
                    // reset candidate/tail tracking
                    candidateSince = null
                    candidateRetroStart = null
                    tailSince = null

                    // Return "Finished" event eerst (caller krijgt episode-afsluiting),
                    // en de volgende tick zal de nieuwe episode als "active" bestaan.
                    // Als je liever beide events in 1 tick wilt, moeten we EpisodeEvent uitbreiden.
                    return EpisodeEvent.Finished(finished)
                }

                // 2) End condition: IOB onder threshold + BG stabiel + safe zone
                val canEnd =
                    if (hasBg && hasIob) {
                        val iobOk = currentIob < IOB_END_THRESHOLD
                        val stableOk = isStable(now)
                        val safeOk = isSafeZone(now, bgMmol, targetMmol, deltaToTarget)
                        iobOk && stableOk && safeOk
                    } else {
                        false
                    }


                if (canEnd) {
                    val finished = ep.copy(endTime = now)
                    resetAll()
                    return EpisodeEvent.Finished(finished)
                }

                null
            }
        }
    }

    // ─────────────────────────────
    // Detectie helpers (Optie A)
    // ─────────────────────────────

    private fun isMealCandidate(now: DateTime, slope: Double, mealSignalActive: Boolean): Boolean {
        // Candidate: sustained "verdachte" stijging
        // slope in mmol/L/hr
        val slopeOk = slope >= S_DETECT
        // mealSignal mag helpen, maar niet noodzakelijk
        return slopeOk || (mealSignalActive && slope >= S_DETECT * 0.8)
    }

    private fun isMealConfirmed(now: DateTime, slope: Double, mealSignalActive: Boolean): Boolean {
        // Confirm vereist duur + sterke slope
        val since = candidateSince ?: return false
        val mins = minutesBetween(since, now)
        val slopeOk = slope >= S_MEAL || (mealSignalActive && slope >= S_DETECT)
        return mins >= T_CONFIRM_MIN && slopeOk && amplitudeRiseOk(now)
    }

    private fun isReEntryConfirmed(now: DateTime, slope: Double, mealSignalActive: Boolean): Boolean {
        // Re-entry lijkt op confirm, maar iets strenger om false splits te vermijden
        val slopeOk = slope >= S_MEAL || (mealSignalActive && slope >= S_MEAL * 0.9)
        return slopeOk && amplitudeRiseOk(now)
    }

    private fun amplitudeRiseOk(now: DateTime): Boolean {
        // Vereist echte BG; als buffer te klein is: false
        if (buf.size < 6) return false // minstens ~30 min
        val window = buf.filter { minutesBetween(it.time, now) <= 45 }
        if (window.size < 4) return false
        val recentMin = window.minOf { it.bg }
        val recentMax = window.maxOf { it.bg }
        return (recentMax - recentMin) >= RISE_MIN
    }

    private fun shouldEnterTail(now: DateTime): Boolean {
        // Tail als we weer richting stabiel gaan / daling inzetten
        // Heuristiek: laatste 2-3 ticks slope onder stable en/of dalende bg
        val last = buf.takeLastSafe(3)
        if (last.size < 2) return false

        val stableSlope = last.all { abs(it.slope) < S_STABLE }
        val fallingBg = last.size >= 3 && (last[2].bg < last[1].bg && last[1].bg < last[0].bg)

        return stableSlope || fallingBg
    }

    private fun isStable(now: DateTime): Boolean {
        val last = buf.takeLastSafe(2)
        if (last.size < 2) return false
        return last.all { abs(it.slope) < S_STABLE }
    }

    private fun isSafeZone(now: DateTime, bgMmol: Double, targetMmol: Double, deltaToTarget: Double): Boolean {
        val bgOk = bgMmol >= NEAR_HYPO
        val upperOk =
            if (targetMmol.isFinite()) bgMmol <= targetMmol + 1.0
            else abs(deltaToTarget) <= 1.0
        return bgOk && upperOk
    }

    private fun findRetroStart(now: DateTime): DateTime? {
        // Zoek terug: eerste punt waar slope stabiel is of lokaal minimum
        // Window 60-90 min terug (genoeg voor een "dal" vóór stijging)
        val window = buf.filter { minutesBetween(it.time, now) in 10..90 }
        if (window.size < 4) return null

        // 1) vind laatste lokaal minimum (laagste bg) in die window
        val minTick = window.minByOrNull { it.bg }

        // 2) probeer nog net vóór dat minimum een "stabiel" segment te vinden
        val idx = window.indexOf(minTick)
        if (idx > 1) {
            val before = window.subList(max(0, idx - 3), idx + 1)
            val stable = before.any { abs(it.slope) < S_STABLE }
            if (stable) return before.firstOrNull { abs(it.slope) < S_STABLE }?.time ?: minTick?.time
        }

        return minTick?.time
    }

    private fun findSplitPoint(now: DateTime): DateTime? {
        // Splitpoint = laatste dal/stabiel punt sinds tail start
        val tailStart = tailSince ?: return findRetroStart(now)
        val window = buf.filter { it.time.isAfter(tailStart.minusMinutes(5)) && it.time.isBefore(now.plusMinutes(1)) }
        if (window.size < 3) return null

        val minTick = window.minByOrNull { it.bg }
        // als er ook een stabiel punt is dicht bij min, pak dat
        val stableNearMin = window
            .filter { abs(it.slope) < S_STABLE }
            .minByOrNull { abs(it.bg - (minTick?.bg ?: it.bg)) }

        return stableNearMin?.time ?: minTick?.time
    }

    // ─────────────────────────────
    // Exclusion & misc
    // ─────────────────────────────

    private fun detectExclusion(
        rescueConfirmed: Boolean,
        downtrendLocked: Boolean,
        manualBolusDetected: Boolean
    ): ExclusionReason? {
        return when {
            rescueConfirmed -> ExclusionReason.RESCUE_CONFIRMED
            downtrendLocked -> ExclusionReason.DOWNTREND_LOCKED
            manualBolusDetected -> ExclusionReason.MANUAL_BOLUS
            else -> null
        }
    }


    private fun clamp(x: Double): Double =
        min(1.0, max(0.0, x))

    private fun minutesBetween(a: DateTime, b: DateTime): Int =
        ((b.millis - a.millis) / 60000L).toInt()

    private fun resetCandidateOnly() {
        state = State.IDLE
        candidateSince = null
        candidateRetroStart = null
        inactiveTicks = 0
    }

    private fun resetAll() {
        activeEpisode = null
        state = State.IDLE
        candidateSince = null
        candidateRetroStart = null
        tailSince = null
        inactiveTicks = 0
        // buffer houden we bewust: helpt direct na restart/short gaps
    }

    private fun <T> ArrayDeque<T>.takeLastSafe(n: Int): List<T> {
        if (this.isEmpty()) return emptyList()
        val list = this.toList()
        val from = max(0, list.size - n)
        return list.subList(from, list.size).asReversed()
    }


    companion object {
        // buffer
        private const val BUFFER_MAX_TICKS = 40 // ~3h20m

        // safety caps
        private const val MAX_EPISODE_MINUTES = 360 // 6 uur
        private const val MIN_EPISODE_MINUTES = 15
        private const val END_INACTIVE_TICKS = 3 // legacy fallback

        // thresholds v1 (mmol/L/hr)
        private const val S_STABLE = 0.30
        private const val S_DETECT = 0.80
        private const val S_MEAL = 1.20

        // timing
        private const val T_CONFIRM_MIN = 20 // 4 ticks

        // amplitude
        private const val RISE_MIN = 0.7

        // tail iob end
        private const val IOB_END_THRESHOLD = 0.20

        // hypo bands
        private const val NEAR_HYPO = 4.4
    }

    fun totalEpisodes(): Long = nextId - 1L
    fun hasActiveEpisode(): Boolean = activeEpisode != null
    fun activeEpisodeStart(): DateTime? = activeEpisode?.startTime
}
