package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stap 3B: AxisScorer (uitgebreid)
 *
 * Input:
 * - Episode (tijd/kwaliteit/exclusion)
 * - FCLvNextObsEpisodeSummary (feiten: peak/nadir/timeAbove10/firstInsulin/predPeakError/rebound)
 *
 * Output:
 * - AxisObservation per axis: TIMING / HEIGHT / PERSISTENCE
 *
 * Kernregels (volgens jouw wensen):
 * - TIMING "LATE" is pas een probleem als de werkelijke piek > 10 mmol/L.
 *   Een late eerste dosis maar géén hoge piek => TIMING=OK.
 * - HEIGHT primair op werkelijke piek (peakBg). predictedPeak mismatch alleen als tag.
 * - Exclusions:
 *   - episode.excluded => geen learning (we returnen 1 UNKNOWN + reason als trace)
 */

enum class Axis {
    TIMING,
    HEIGHT,
    PERSISTENCE
}

enum class AxisOutcome {
    OK,

    // TIMING
    EARLY,
    LATE,

    // HEIGHT
    TOO_HIGH,      // peak boven grens (10)
    TOO_STRONG,    // hypo/nadir onder grens of sterke overshoot naar beneden
    TOO_WEAK,      // (nog beperkt bruikbaar zonder carbs/meal truth)

    // PERSISTENCE
    TOO_SHORT,     // lang boven 10 => te weinig/te kort volgehouden
    TOO_LONG,      // te lang doorgezet => nadir te laag / rebound na hypo (proxy)

    UNKNOWN
}

data class AxisObservation(
    val episodeId: Long,
    val axis: Axis,
    val outcome: AxisOutcome,
    val signalStrength: Double,   // 0..1 (duidelijkheid van dit label)
    val reason: String,
    val tags: Map<String, String> = emptyMap()
)

/**
 * Alle thresholds expliciet in config zodat jij later veilig kunt tunen.
 */
data class FCLvNextObsAxisScorerConfig(

    // Targets / zones
    val highBgThresholdMmol: Double = 10.0,
    val hypoThresholdMmol: Double = 3.9,

    // TIMING:
    // Alleen labelen als peak > highBgThreshold.
    val timingLateMinMinutesToFirstInsulin: Int = 20,

    // EARLY / TOO_LONG / TOO_STRONG proxies:
    // - EARLY: eerste meaningful insulin erg snel + nadir laag / of nadir < hypoThreshold
    val earlyMaxMinutesToFirstInsulin: Int = 8,
    val earlyNadirSafetyMmol: Double = 4.2,     // iets boven hypo, conservatief

    // PERSISTENCE TOO_SHORT:
    // - lang boven 10 => te kort / te zwak doorgezet
    val persistenceTooShort_TimeAbove10Min: Int = 45,

    // PERSISTENCE TOO_LONG / TOO_STRONG:
    // - nadir onder hypoThreshold => TOO_STRONG
    // - nadir in [hypoThreshold..earlyNadirSafety] + reboundDetected => TOO_LONG (proxy)
    val tooLongReboundRequiresLowNadir: Boolean = true,

    // predicted mismatch (tag-only)
    val predictedPeakMismatchAbsMmol: Double = 2.0
)

class FCLvNextObsAxisScorer(
    private val cfg: FCLvNextObsAxisScorerConfig = FCLvNextObsAxisScorerConfig()
) {

    fun score(
        episode: Episode,
        summary: FCLvNextObsEpisodeSummary
    ): List<AxisObservation> {

        // Excluded episodes: geen learning labels
        if (episode.excluded) {
            return listOf(
                AxisObservation(
                    episodeId = episode.id,
                    axis = Axis.HEIGHT,
                    outcome = AxisOutcome.UNKNOWN,
                    signalStrength = 0.0,
                    reason = "EXCLUDED: ${episode.exclusionReason}"
                )
            )
        }

        val quality = clamp01(episode.qualityScore)

        val peak = summary.peakBg
        val nadir = summary.nadirBg
        val minutesToFirst = summary.minutesToFirstInsulin
        val timeAbove10 = summary.timeAbove10Min

        val tags = mutableMapOf<String, String>()

        // predicted mismatch tag (debug)
        if (summary.predictedPeakAtStart != null && peak != null) {
            val errAbs = abs(summary.predictedPeakAtStart - peak)
            if (errAbs >= cfg.predictedPeakMismatchAbsMmol) {
                tags["PRED_MISMATCH"] =
                    "absErr=${fmt(errAbs)} pred=${fmt(summary.predictedPeakAtStart)} act=${fmt(peak)}"
            }
        }
        if (summary.peakPredictionError != null) {
            tags["PRED_ERR"] = "act-pred=${fmt(summary.peakPredictionError)}"
        }

        val timing = scoreTiming(
            episodeId = episode.id,
            quality = quality,
            peakBg = peak,
            minutesToFirstInsulin = minutesToFirst,
            nadirBg = nadir,
            tags = tags
        )

        val height = scoreHeight(
            episodeId = episode.id,
            quality = quality,
            peakBg = peak,
            nadirBg = nadir,
            tags = tags
        )

        val persistence = scorePersistence(
            episodeId = episode.id,
            quality = quality,
            peakBg = peak,
            nadirBg = nadir,
            timeAbove10Min = timeAbove10,
            reboundDetected = summary.reboundDetected,
            tags = tags
        )

        return listOf(timing, height, persistence)
    }

    // ─────────────────────────────────────────────
    // TIMING (nu met EARLY-proxy)
    // ─────────────────────────────────────────────
    private fun scoreTiming(
        episodeId: Long,
        quality: Double,
        peakBg: Double?,
        minutesToFirstInsulin: Int?,
        nadirBg: Double?,
        tags: Map<String, String>
    ): AxisObservation {

        if (peakBg == null) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.TIMING,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "TIMING unknown: peakBg missing",
                tags = tags
            )
        }

        // Als peak niet hoog is => timing niet problematiseren (jouw eis)
        if (peakBg <= cfg.highBgThresholdMmol) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.TIMING,
                outcome = AxisOutcome.OK,
                signalStrength = clamp01(0.65 * quality),
                reason = "TIMING OK: peak<=${fmt(cfg.highBgThresholdMmol)} (peak=${fmt(peakBg)})",
                tags = tags
            )
        }

        // Peak is hoog => timing relevant
        if (minutesToFirstInsulin == null) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.TIMING,
                outcome = AxisOutcome.LATE,
                signalStrength = clamp01(0.70 * quality + 0.30 * signalFromPeak(peakBg)),
                reason = "TIMING LATE: peak>${fmt(cfg.highBgThresholdMmol)} but no meaningful insulin detected",
                tags = tags
            )
        }

        // EARLY-proxy:
        // Als de eerste meaningful insulin heel snel kwam én nadir laag werd => EARLY
        val early =
            minutesToFirstInsulin <= cfg.earlyMaxMinutesToFirstInsulin &&
                nadirBg != null &&
                nadirBg <= cfg.earlyNadirSafetyMmol

        if (early) {
            val depth = clamp01((cfg.earlyNadirSafetyMmol - nadirBg!!) / 1.0) // 0..1
            val strength = clamp01(0.55 * quality + 0.45 * depth)
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.TIMING,
                outcome = AxisOutcome.EARLY,
                signalStrength = strength,
                reason = "TIMING EARLY: minToFirst=${minutesToFirstInsulin}m nadir=${fmt(nadirBg)} (<=${fmt(cfg.earlyNadirSafetyMmol)})",
                tags = tags
            )
        }

        // LATE:
        val late = minutesToFirstInsulin >= cfg.timingLateMinMinutesToFirstInsulin
        val outcome = if (late) AxisOutcome.LATE else AxisOutcome.OK

        val latenessStrength =
            if (!late) 0.30
            else clamp01((minutesToFirstInsulin - cfg.timingLateMinMinutesToFirstInsulin).toDouble() / 30.0)

        val strength =
            clamp01(
                0.55 * quality +
                    0.25 * signalFromPeak(peakBg) +
                    0.20 * latenessStrength
            )

        return AxisObservation(
            episodeId = episodeId,
            axis = Axis.TIMING,
            outcome = outcome,
            signalStrength = strength,
            reason = "TIMING ${outcome.name}: peak=${fmt(peakBg)} minToFirst=${minutesToFirstInsulin} (late>=${cfg.timingLateMinMinutesToFirstInsulin})",
            tags = tags
        )
    }

    // ─────────────────────────────────────────────
    // HEIGHT (nu met TOO_STRONG / TOO_WEAK placeholders)
    // ─────────────────────────────────────────────
    private fun scoreHeight(
        episodeId: Long,
        quality: Double,
        peakBg: Double?,
        nadirBg: Double?,
        tags: Map<String, String>
    ): AxisObservation {

        if (peakBg == null) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "HEIGHT unknown: peakBg missing",
                tags = tags
            )
        }

        // TOO_STRONG: nadir onder hypo-threshold (hard)
        if (nadirBg != null && nadirBg < cfg.hypoThresholdMmol) {
            val severity = clamp01((cfg.hypoThresholdMmol - nadirBg) / 1.0)
            val strength = clamp01(0.55 * quality + 0.45 * severity)
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.TOO_STRONG,
                signalStrength = strength,
                reason = "HEIGHT TOO_STRONG: nadir=${fmt(nadirBg)} < hypo=${fmt(cfg.hypoThresholdMmol)}",
                tags = tags
            )
        }

        // TOO_HIGH: peak boven 10
        val tooHigh = peakBg > cfg.highBgThresholdMmol
        if (tooHigh) {
            val overshoot = max(0.0, peakBg - cfg.highBgThresholdMmol)
            val overshootStrength = clamp01(overshoot / 4.0)
            val strength = clamp01(0.60 * quality + 0.40 * overshootStrength)
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.HEIGHT,
                outcome = AxisOutcome.TOO_HIGH,
                signalStrength = strength,
                reason = "HEIGHT TOO_HIGH: peak=${fmt(peakBg)} > ${fmt(cfg.highBgThresholdMmol)}",
                tags = tags
            )
        }

        // TOO_WEAK: in deze fase eigenlijk alleen zinvol als je later carbs/meal truth toevoegt.
        // Nu laten we dit conservatief op OK als peak niet hoog is en geen hypo.
        return AxisObservation(
            episodeId = episodeId,
            axis = Axis.HEIGHT,
            outcome = AxisOutcome.OK,
            signalStrength = clamp01(0.65 * quality),
            reason = "HEIGHT OK: peak=${fmt(peakBg)} nadir=${nadirBg?.let { fmt(it) } ?: "?"}",
            tags = tags
        )
    }

    // ─────────────────────────────────────────────
    // PERSISTENCE (nu met TOO_LONG proxy)
    // ─────────────────────────────────────────────
    private fun scorePersistence(
        episodeId: Long,
        quality: Double,
        peakBg: Double?,
        nadirBg: Double?,
        timeAbove10Min: Int,
        reboundDetected: Boolean,
        tags: Map<String, String>
    ): AxisObservation {

        if (peakBg == null) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.UNKNOWN,
                signalStrength = 0.0,
                reason = "PERSISTENCE unknown: peakBg missing",
                tags = tags
            )
        }

        // Als peak niet hoog was: persistence probleemloos (voor nu)
        if (peakBg <= cfg.highBgThresholdMmol) {
            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.OK,
                signalStrength = clamp01(0.65 * quality),
                reason = "PERSISTENCE OK: peak<=${fmt(cfg.highBgThresholdMmol)}",
                tags = tags
            )
        }

        // TOO_LONG proxy:
        // Als er rebound is én nadir laag-ish => mogelijk te lang doorgezet (of te agressief na peak)
        val lowEnoughForTooLong =
            if (!cfg.tooLongReboundRequiresLowNadir) true
            else (nadirBg != null && nadirBg <= cfg.earlyNadirSafetyMmol)

        if (reboundDetected && lowEnoughForTooLong) {
            val depth =
                if (nadirBg == null) 0.5
                else clamp01((cfg.earlyNadirSafetyMmol - nadirBg) / 1.0)

            val strength = clamp01(0.55 * quality + 0.45 * depth)

            return AxisObservation(
                episodeId = episodeId,
                axis = Axis.PERSISTENCE,
                outcome = AxisOutcome.TOO_LONG,
                signalStrength = strength,
                reason = "PERSISTENCE TOO_LONG: reboundDetected=true nadir=${nadirBg?.let { fmt(it) } ?: "?"}",
                tags = tags
            )
        }

        // TOO_SHORT:
        val tooShort = timeAbove10Min >= cfg.persistenceTooShort_TimeAbove10Min
        val outcome = if (tooShort) AxisOutcome.TOO_SHORT else AxisOutcome.OK

        val aboveStrength =
            if (!tooShort) 0.30
            else clamp01((timeAbove10Min - cfg.persistenceTooShort_TimeAbove10Min).toDouble() / 60.0)

        val strength =
            clamp01(
                0.55 * quality +
                    0.25 * signalFromPeak(peakBg) +
                    0.20 * aboveStrength
            )

        return AxisObservation(
            episodeId = episodeId,
            axis = Axis.PERSISTENCE,
            outcome = outcome,
            signalStrength = strength,
            reason = "PERSISTENCE ${outcome.name}: timeAbove10=${timeAbove10Min}m (tooShort>=${cfg.persistenceTooShort_TimeAbove10Min}m) rebound=$reboundDetected",
            tags = tags
        )
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────
    private fun clamp01(x: Double): Double = min(1.0, max(0.0, x))

    private fun signalFromPeak(peak: Double): Double {
        val overshoot = max(0.0, peak - cfg.highBgThresholdMmol)
        return clamp01(overshoot / 4.0)
    }

    private fun fmt(x: Double): String = String.format("%.2f", x)
}
