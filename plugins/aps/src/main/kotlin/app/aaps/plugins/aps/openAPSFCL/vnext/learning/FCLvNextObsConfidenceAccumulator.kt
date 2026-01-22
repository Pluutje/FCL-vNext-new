package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import java.util.ArrayDeque
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Stap 4: ConfidenceAccumulator
 *
 * Doel:
 * - Neem AxisObservations (uit stap 3B)
 * - Bouw "structurele" confidence op over meerdere episodes
 * - Eén accumulator voor dag+nacht, maar nacht krijgt lagere weight
 *
 * Belangrijk:
 * - Read-only: geen parameter updates
 * - Episodes die excluded zijn, moeten NIET ingevoerd worden (of worden genegeerd)
 * - We slaan alleen "niet-OK" signalen op als evidence
 */

data class FCLvNextObsConfidenceSnapshot(
    val updatedAt: DateTime,
    val perAxis: Map<Axis, List<AxisOutcomeConfidence>>
)

data class AxisOutcomeConfidence(
    val axis: Axis,
    val outcome: AxisOutcome,
    val confidence: Double,        // 0..1
    val supportScore: Double,      // netto support (weighted)
    val supportCount: Int,         // #evidence items
    val lastSeenAt: DateTime?,
    val notes: String
)

data class FCLvNextObsConfidenceAccumulatorConfig(

    // Evidence retention: maximaal aantal opgeslagen items per (axis,outcome)
    val maxEvidencePerBucket: Int = 80,

    // Nacht weegt minder
    val nightWeightMul: Double = 0.65,

    // Minimum structurele eisen voordat we überhaupt confidence geven
    val minSupportCount: Int = 3,          // minimaal 3 episodes die deze outcome ondersteunen
    val minTotalCountInAxis: Int = 6,      // minimaal 6 episodes (alle non-OK in deze axis) om betrouwbaar te zijn

    // Tijd-decay: oude episodes tellen minder
    // half-life in uren (bv 72 uur ~ 3 dagen)
    val halfLifeHours: Double = 72.0,

    // Hoe agressief confidence stijgt met netto support
    val sigmoidK: Double = 1.35,

    // Drempels om advieswaardig te worden (stap 5 gebruikt dit)
    val emitMinConfidence: Double = 0.70,

    // OK / UNKNOWN worden niet opgeslagen
    val storedOutcomes: Set<AxisOutcome> = setOf(
        AxisOutcome.EARLY,
        AxisOutcome.LATE,
        AxisOutcome.TOO_HIGH,
        AxisOutcome.TOO_STRONG,
        AxisOutcome.TOO_SHORT,
        AxisOutcome.TOO_LONG
    )
)

class FCLvNextObsConfidenceAccumulator(
    private val cfg: FCLvNextObsConfidenceAccumulatorConfig = FCLvNextObsConfidenceAccumulatorConfig()
) {

    private data class Evidence(
        val at: DateTime,
        val episodeId: Long,
        val axis: Axis,
        val outcome: AxisOutcome,
        val strength: Double,   // 0..1
        val weight: Double      // already includes night factor
    )

    /**
     * Buckets per axis/outcome.
     * We houden de history beperkt (maxEvidencePerBucket).
     */
    private val buckets: MutableMap<Pair<Axis, AxisOutcome>, ArrayDeque<Evidence>> =
        mutableMapOf()

    /**
     * Voeg één episode-resultaat toe (meestal 3 observations tegelijk).
     *
     * BELANGRIJK:
     * - Geef alleen observations door van episodes die NIET excluded zijn.
     * - OK/UNKNOWN worden automatisch genegeerd.
     */
    fun ingestEpisode(
        now: DateTime,
        isNight: Boolean,
        observations: List<AxisObservation>,
        deliveryConfidence: Double
    ) {
        val baseWeight = if (isNight) cfg.nightWeightMul else 1.0
        val deliveryW = clamp01(deliveryConfidence)
        val combinedWeight = clamp01(baseWeight * deliveryW)

        observations.forEach { obs ->
            if (!cfg.storedOutcomes.contains(obs.outcome)) return@forEach
            if (obs.signalStrength <= 0.0) return@forEach

            val key = obs.axis to obs.outcome
            val q = buckets.getOrPut(key) { ArrayDeque() }

            q.addFirst(
                Evidence(
                    at = now,
                    episodeId = obs.episodeId,
                    axis = obs.axis,
                    outcome = obs.outcome,
                    strength = clamp01(obs.signalStrength),
                    weight = combinedWeight
                )
            )

            while (q.size > cfg.maxEvidencePerBucket) {
                q.removeLast()
            }
        }
    }

    /**
     * Bouw een snapshot van confidence voor alle relevante buckets.
     * Dit is wat stap 5 (AdviceEmitter) straks zal gebruiken.
     */
    fun buildSnapshot(now: DateTime): FCLvNextObsConfidenceSnapshot {
        val perAxisOut: MutableMap<Axis, MutableList<AxisOutcomeConfidence>> = mutableMapOf()

        // Voor structurele criteria: tel "total non-OK evidence items per axis"
        val totalNonOkPerAxis = Axis.entries.associateWith { axis ->
            buckets
                .filterKeys { it.first == axis }
                .values
                .sumOf { it.size }
        }

        // Bereken confidence per bucket
        for ((key, queue) in buckets) {
            val axis = key.first
            val outcome = key.second

            val result = computeBucketConfidence(
                now = now,
                axis = axis,
                outcome = outcome,
                evidence = queue,
                totalAxisCount = totalNonOkPerAxis[axis] ?: 0
            )

            perAxisOut.getOrPut(axis) { mutableListOf() }.add(result)
        }

        // Sorteer per axis: hoogste confidence boven
        val finalized = perAxisOut.mapValues { (_, list) ->
            list.sortedByDescending { it.confidence }
        }

        return FCLvNextObsConfidenceSnapshot(
            updatedAt = now,
            perAxis = finalized
        )
    }

    /**
     * Helper: geeft snel de "beste" adviezen die boven emitMinConfidence zitten.
     * (Stap 5 kan dit direct gebruiken.)
     */
    fun getTopSignals(now: DateTime, maxItems: Int = 6): List<AxisOutcomeConfidence> {
        val snap = buildSnapshot(now)
        val all = snap.perAxis.values.flatten()
        return all
            .filter { it.confidence >= cfg.emitMinConfidence }
            .sortedByDescending { it.confidence }
            .take(maxItems)
    }

    // ─────────────────────────────────────────────
    // Core confidence logic
    // ─────────────────────────────────────────────

    private fun computeBucketConfidence(
        now: DateTime,
        axis: Axis,
        outcome: AxisOutcome,
        evidence: ArrayDeque<Evidence>,
        totalAxisCount: Int
    ): AxisOutcomeConfidence {

        if (evidence.isEmpty()) {
            return AxisOutcomeConfidence(
                axis = axis,
                outcome = outcome,
                confidence = 0.0,
                supportScore = 0.0,
                supportCount = 0,
                lastSeenAt = null,
                notes = "no evidence"
            )
        }

        // Structurele check 1: minimale support count
        val supportCount = evidence.size
        val enoughSupport = supportCount >= cfg.minSupportCount

        // Structurele check 2: genoeg axis-breed data
        val enoughAxisData = totalAxisCount >= cfg.minTotalCountInAxis

        // Compute decayed weighted support score
        var weightedSupport = 0.0
        var weightedTotal = 0.0

        var lastSeenAt: DateTime? = null

        evidence.forEachIndexed { idx, ev ->
            if (idx == 0) lastSeenAt = ev.at

            val ageHours = max(0.0, (now.millis - ev.at.millis).toDouble() / (3600_000.0))
            val timeDecay = halfLifeDecay(ageHours, cfg.halfLifeHours)

            val w = ev.weight * timeDecay
            weightedSupport += w * ev.strength
            weightedTotal += w
        }

        val supportScore =
            if (weightedTotal > 1e-9) (weightedSupport / weightedTotal) else 0.0

        // Net support vs opposition within same axis:
        // We schatten "opposition" als gemiddelde sterkte van de tegenoverliggende outcome(s).
        val oppositionScore = computeOppositionScore(now, axis, outcome)

        val net = clamp01(supportScore - oppositionScore)  // 0..1 (alleen positieve netto)

        // confidence from net via sigmoid, maar pas "echt" als structureel genoeg
        val rawConfidence = sigmoid(net, cfg.sigmoidK)

        val confidence =
            if (enoughSupport && enoughAxisData) rawConfidence
            else {
                // Als nog niet structureel, dan bewust lager houden
                rawConfidence * 0.55
            }

        val notes = buildString {
            append("supportCount=$supportCount totalAxisCount=$totalAxisCount ")
            append("supportScore=${fmt(supportScore)} opp=${fmt(oppositionScore)} net=${fmt(net)} ")
            if (!enoughSupport) append("minSupportMissing ")
            if (!enoughAxisData) append("minAxisDataMissing ")
        }.trim()

        return AxisOutcomeConfidence(
            axis = axis,
            outcome = outcome,
            confidence = clamp01(confidence),
            supportScore = supportScore,
            supportCount = supportCount,
            lastSeenAt = lastSeenAt,
            notes = notes
        )
    }

    /**
     * Opposition mapping per axis:
     * - TIMING: EARLY <-> LATE
     * - HEIGHT: TOO_HIGH <-> TOO_STRONG (niet perfect, maar werkt als tegen-symptoom)
     * - PERSISTENCE: TOO_SHORT <-> TOO_LONG
     */
    private fun computeOppositionScore(
        now: DateTime,
        axis: Axis,
        outcome: AxisOutcome
    ): Double {

        val opposites: List<AxisOutcome> = when (axis) {
            Axis.TIMING -> when (outcome) {
                AxisOutcome.EARLY -> listOf(AxisOutcome.LATE)
                AxisOutcome.LATE -> listOf(AxisOutcome.EARLY)
                else -> emptyList()
            }

            Axis.HEIGHT -> when (outcome) {
                AxisOutcome.TOO_HIGH -> listOf(AxisOutcome.TOO_STRONG)
                AxisOutcome.TOO_STRONG -> listOf(AxisOutcome.TOO_HIGH)
                else -> emptyList()
            }

            Axis.PERSISTENCE -> when (outcome) {
                AxisOutcome.TOO_SHORT -> listOf(AxisOutcome.TOO_LONG)
                AxisOutcome.TOO_LONG -> listOf(AxisOutcome.TOO_SHORT)
                else -> emptyList()
            }
        }

        if (opposites.isEmpty()) return 0.0

        // gemiddelde opposition score over opposites
        var sum = 0.0
        var n = 0

        for (opp in opposites) {
            val q = buckets[axis to opp] ?: continue
            val s = computeSupportScoreOnly(now, q)
            if (s > 0.0) {
                sum += s
                n++
            }
        }

        return if (n > 0) clamp01(sum / n.toDouble()) else 0.0
    }

    private fun computeSupportScoreOnly(now: DateTime, evidence: ArrayDeque<Evidence>): Double {
        if (evidence.isEmpty()) return 0.0

        var weightedSupport = 0.0
        var weightedTotal = 0.0

        evidence.forEach { ev ->
            val ageHours = max(0.0, (now.millis - ev.at.millis).toDouble() / (3600_000.0))
            val timeDecay = halfLifeDecay(ageHours, cfg.halfLifeHours)
            val w = ev.weight * timeDecay

            weightedSupport += w * ev.strength
            weightedTotal += w
        }

        return if (weightedTotal > 1e-9) clamp01(weightedSupport / weightedTotal) else 0.0
    }

    // ─────────────────────────────────────────────
    // Math helpers
    // ─────────────────────────────────────────────

    private fun clamp01(x: Double): Double = min(1.0, max(0.0, x))

    private fun halfLifeDecay(ageHours: Double, halfLifeHours: Double): Double {
        val hl = max(1e-6, halfLifeHours)
        // decay = 0.5^(age/hl)
        return Math.pow(0.5, ageHours / hl)
    }

    private fun sigmoid(x: Double, k: Double): Double {
        // x in [0..1] => map naar [-1..+1] zodat het niet te snel verzadigt
        val z = (x * 2.0) - 1.0
        val kk = max(0.1, k)
        val y = 1.0 / (1.0 + exp(-kk * z))
        return clamp01(y)
    }

    private fun fmt(x: Double): String = String.format("%.2f", x)
}
