package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import org.joda.time.Minutes
import kotlin.math.abs

/**
 * Uitgebreide, feitelijke samenvatting van één afgesloten episode.
 * GEEN interpretatie, GEEN learning.
 *
 * Deze klasse levert ALLE feiten die AxisScorer nodig heeft,
 * zodat scorers zelf geen berekeningen hoeven te doen.
 */
data class FCLvNextObsEpisodeSummary(

    // Identiteit
    val episodeId: Long,
    val isNight: Boolean,

    // Tijd
    val startTime: DateTime,
    val endTime: DateTime,
    val durationMin: Int,

    // ─────────────────────────────
    // Glucose feiten
    // ─────────────────────────────
    val startBg: Double?,
    val peakBg: Double?,
    val peakTime: DateTime?,
    val timeToPeakMin: Int?,

    val nadirBg: Double?,
    val nadirTime: DateTime?,

    val timeAbove10Min: Int,

    // Post-peak gedrag
    val postPeakDurationMin: Int?,
    val reboundDetected: Boolean,

    // ─────────────────────────────
    // Insulin feiten
    // ─────────────────────────────
    val totalInsulinU: Double,
    val firstMeaningfulInsulinAt: DateTime?,
    val minutesToFirstInsulin: Int?,

    // ─────────────────────────────
    // Predictie feiten
    // ─────────────────────────────
    val predictedPeakAtStart: Double?,
    val peakPredictionError: Double?
)

/**
 * Zet een Episode om in een uitgebreide feitelijke samenvatting.
 */
class FCLvNextObsEpisodeSummarizer(
    private val bgProvider: BgHistoryProvider,
    private val insulinProvider: InsulinDeliveryProvider
) {

    fun summarize(
        episode: Episode,
        predictedPeakAtStart: Double?
    ): FCLvNextObsEpisodeSummary {

        requireNotNull(episode.endTime) {
            "Episode must be finished before summarizing"
        }

        val start = episode.startTime
        val end = episode.endTime

        val bg = bgProvider.getBgBetween(start, end)
        val insulin = insulinProvider.getDeliveriesBetween(start, end)

        // ─────────────────────────────
        // Glucose analyse
        // ─────────────────────────────
        val startBg = bg.firstOrNull()?.second

        val peak = bg.maxByOrNull { it.second }
        val nadir = bg.minByOrNull { it.second }

        val peakBg = peak?.second
        val peakTime = peak?.first

        val timeToPeakMin =
            peakTime?.let {
                Minutes.minutesBetween(start, it).minutes
            }

        val timeAbove10Min = computeTimeAbove(bg, 10.0)

        val postPeakDurationMin =
            peakTime?.let {
                Minutes.minutesBetween(it, end).minutes
            }

        val reboundDetected =
            detectReboundAfterPeak(bg, peakTime)

        // ─────────────────────────────
        // Insulin analyse
        // ─────────────────────────────
        val totalInsulin = insulin.sumOf { it.units }

        val firstMeaningfulInsulinAt =
            insulin.firstOrNull { it.units >= 0.10 }?.time

        val minutesToFirstInsulin =
            firstMeaningfulInsulinAt?.let {
                Minutes.minutesBetween(start, it).minutes
            }

        // ─────────────────────────────
        // Predictie analyse
        // ─────────────────────────────
        val peakPredictionError =
            if (predictedPeakAtStart != null && peakBg != null)
                peakBg - predictedPeakAtStart
            else null

        return FCLvNextObsEpisodeSummary(
            episodeId = episode.id,
            isNight = episode.isNight,

            startTime = start,
            endTime = end,
            durationMin = Minutes.minutesBetween(start, end).minutes,

            startBg = startBg,
            peakBg = peakBg,
            peakTime = peakTime,
            timeToPeakMin = timeToPeakMin,

            nadirBg = nadir?.second,
            nadirTime = nadir?.first,

            timeAbove10Min = timeAbove10Min,

            postPeakDurationMin = postPeakDurationMin,
            reboundDetected = reboundDetected,

            totalInsulinU = totalInsulin,
            firstMeaningfulInsulinAt = firstMeaningfulInsulinAt,
            minutesToFirstInsulin = minutesToFirstInsulin,

            predictedPeakAtStart = predictedPeakAtStart,
            peakPredictionError = peakPredictionError
        )
    }

    // ─────────────────────────────
    // Helpers
    // ─────────────────────────────

    private fun computeTimeAbove(
        bg: List<Pair<DateTime, Double>>,
        threshold: Double
    ): Int {
        var minutes = 0

        for (i in 0 until bg.size - 1) {
            val a = bg[i]
            val b = bg[i + 1]

            val dt = Minutes.minutesBetween(a.first, b.first).minutes
            if (dt <= 0) continue

            val avg = (a.second + b.second) / 2.0
            if (avg > threshold) {
                minutes += dt
            }
        }
        return minutes
    }

    /**
     * Simpele rebound-detectie:
     * Na de piek stijgt BG opnieuw ≥ 0.5 mmol/L binnen 30 minuten.
     *
     * Dit is een FEIT, geen oordeel.
     */
    private fun detectReboundAfterPeak(
        bg: List<Pair<DateTime, Double>>,
        peakTime: DateTime?
    ): Boolean {

        if (peakTime == null) return false

        val postPeak = bg.filter {
            Minutes.minutesBetween(peakTime, it.first).minutes in 5..30
        }

        if (postPeak.size < 2) return false

        val minAfterPeak = postPeak.minOf { it.second }
        val last = postPeak.last().second

        return (last - minAfterPeak) >= 0.5
    }
}
