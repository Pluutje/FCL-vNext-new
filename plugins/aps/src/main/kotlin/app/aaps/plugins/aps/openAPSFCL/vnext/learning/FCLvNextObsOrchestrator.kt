package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

/**
 * FCLvNextObsOrchestrator
 *
 * Verbindt:
 * - EpisodeTracker (stap 1)
 * - EpisodeSummarizer (stap 3A)
 * - AxisScorer (stap 3B)
 * - ConfidenceAccumulator (stap 4)
 * - AdviceEmitter (stap 5)
 *
 * Deze klasse is de ENIGE die je vanuit determineBasal hoeft aan te roepen.
 */
class FCLvNextObsOrchestrator(
    private val episodeTracker: EpisodeTracker,
    private val summarizer: FCLvNextObsEpisodeSummarizer,
    private val axisScorer: FCLvNextObsAxisScorer,
    private val confidenceAccumulator: FCLvNextObsConfidenceAccumulator,
    private val adviceEmitter: FCLvNextObsAdviceEmitter
) {

    /**
     * Deze methode roep je 1× per determineBasal cycle aan.
     *
     * @return eventueel een AdviceBundle (of null als er niets te melden is)
     */
    fun onFiveMinuteTick(
        now: DateTime,
        isNight: Boolean,

        // episode start/stop signalen
        peakActive: Boolean,
        mealSignalActive: Boolean,
        prePeakCommitWindow: Boolean,

        rescueConfirmed: Boolean,
        downtrendLocked: Boolean,

        // trend info
        slope: Double,
        acceleration: Double,
        deltaToTarget: Double,
        consistency: Double,

        // optioneel (mag null)
        predictedPeakAtStart: Double?,
        deliveryConfidence: Double
    ): FCLvNextObsAdviceBundle? {

        val event = episodeTracker.onFiveMinuteTick(
            now = now,
            isNight = isNight,
            peakActive = peakActive,
            mealSignalActive = mealSignalActive,
            prePeakCommitWindow = prePeakCommitWindow,
            rescueConfirmed = rescueConfirmed,
            downtrendLocked = downtrendLocked,
            slope = slope,
            acceleration = acceleration,
            deltaToTarget = deltaToTarget,
            consistency = consistency
        )

        // Alleen iets doen bij einde van episode
        if (event !is EpisodeEvent.Finished) return null

        val episode = event.episode

        // Excluded episodes tellen niet mee voor learning
        if (episode.excluded) return null

        // 1️⃣ Samenvatten (feiten)
        val summary =
            summarizer.summarize(
                episode = episode,
                predictedPeakAtStart = predictedPeakAtStart
            )

        // 2️⃣ Scoren langs assen
        val observations =
            axisScorer.score(
                episode = episode,
                summary = summary
            )

        // 3️⃣ Confidence opbouwen
        confidenceAccumulator.ingestEpisode(
            now = now,
            isNight = isNight,
            observations = observations,
            deliveryConfidence = deliveryConfidence
        )

        // 4️⃣ Adviezen genereren (indien structureel)
        val topSignals =
            confidenceAccumulator.getTopSignals(now)

        if (topSignals.isEmpty()) {
            // debug bundle teruggeven zodat je ziet dat pipeline draait
            val snap = confidenceAccumulator.buildSnapshot(now)
            val debug = buildString {
                append("[OBS] Episode #${episode.id} finished. Buckets:\n")
                for ((axis, list) in snap.perAxis) {
                    val top = list.firstOrNull()
                    if (top != null) {
                        append(" - $axis: top=${top.outcome} conf=${"%.2f".format(top.confidence)} n=${top.supportCount}\n")
                    } else {
                        append(" - $axis: (no evidence)\n")
                    }
                }
            }.trim()

            return FCLvNextObsAdviceBundle(
                createdAt = now,
                advices = emptyList(),
                debugSummary = debug
            )
        }

        return adviceEmitter.emit(
            now = now,
            topSignals = topSignals
        )
    }
}
