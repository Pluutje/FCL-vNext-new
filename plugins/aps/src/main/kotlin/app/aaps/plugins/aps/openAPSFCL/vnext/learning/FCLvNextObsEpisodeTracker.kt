package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs

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
    DATA_INSUFFICIENT
}

sealed class EpisodeEvent {
    data class Started(val episode: Episode) : EpisodeEvent()
    data class Finished(val episode: Episode) : EpisodeEvent()
}

class EpisodeTracker {

    private var activeEpisode: Episode? = null
    private var nextId: Long = 1L

    // nieuw: als signals uit zijn, tellen we ticks
    private var inactiveTicks: Int = 0

    fun onFiveMinuteTick(
        now: DateTime,
        isNight: Boolean,

        // ── bestaande FCL signalen ──
        peakActive: Boolean,
        mealSignalActive: Boolean,
        prePeakCommitWindow: Boolean,

        rescueConfirmed: Boolean,
        downtrendLocked: Boolean,

        slope: Double,
        acceleration: Double,
        deltaToTarget: Double,

        consistency: Double
    ): EpisodeEvent? {

        val anyStartSignal = peakActive || mealSignalActive || prePeakCommitWindow

        // ─────────────────────────────
        // START
        // ─────────────────────────────
        if (activeEpisode == null) {
            inactiveTicks = 0
            if (anyStartSignal) {
                activeEpisode = Episode(
                    id = nextId++,
                    startTime = now,
                    endTime = null,
                    isNight = isNight,
                    excluded = false,
                    exclusionReason = null,
                    qualityScore = clamp(consistency)
                )
                return EpisodeEvent.Started(activeEpisode!!)
            }
            return null
        }

        // ─────────────────────────────
        // ACTIVE → track inactivity
        // ─────────────────────────────
        inactiveTicks = if (anyStartSignal) 0 else inactiveTicks + 1

        val episode = activeEpisode!!

        // Exclusion heeft altijd prioriteit
        val exclusion = detectExclusion(
            rescueConfirmed,
            downtrendLocked
        )

        if (exclusion != null ||
            shouldEndEpisode(
                slope = slope,
                acceleration = acceleration,
                deltaToTarget = deltaToTarget,
                start = episode.startTime,
                now = now,
                inactiveTicks = inactiveTicks
            )
        ) {
            val finished = episode.copy(
                endTime = now,
                excluded = exclusion != null,
                exclusionReason = exclusion
            )
            activeEpisode = null
            inactiveTicks = 0
            return EpisodeEvent.Finished(finished)
        }

        return null
    }

    private fun shouldEndEpisode(
        slope: Double,
        acceleration: Double,
        deltaToTarget: Double,
        start: DateTime,
        now: DateTime,
        inactiveTicks: Int
    ): Boolean {

        val minutes = (now.millis - start.millis) / 60000
        if (minutes > MAX_EPISODE_MINUTES) return true

        // 1) Trend-based (werkt pas als jij echte slope/accel/delta doorgeeft)
        val trendEnd =
            acceleration < 0.0 &&
                slope <= -0.6 &&
                abs(deltaToTarget) < 0.2

        // 2) Signal-based fallback (werkt NU al):
        // als 3 ticks lang geen start-signaal actief is, en episode duurt minimaal 15 min → eindig
        val signalEnd =
            inactiveTicks >= END_INACTIVE_TICKS &&
                minutes >= MIN_EPISODE_MINUTES

        return trendEnd || signalEnd
    }

    private fun detectExclusion(
        rescueConfirmed: Boolean,
        downtrendLocked: Boolean
    ): ExclusionReason? {
        return when {
            rescueConfirmed -> ExclusionReason.RESCUE_CONFIRMED
            downtrendLocked -> ExclusionReason.DOWNTREND_LOCKED
            else -> null
        }
    }

    private fun clamp(x: Double): Double =
        min(1.0, max(0.0, x))

    companion object {
        private const val MAX_EPISODE_MINUTES = 360 // 6 uur
        private const val END_INACTIVE_TICKS = 3    // 3×5 min = 15 min “signal off”
        private const val MIN_EPISODE_MINUTES = 15  // niet meteen na start stoppen
    }
}
