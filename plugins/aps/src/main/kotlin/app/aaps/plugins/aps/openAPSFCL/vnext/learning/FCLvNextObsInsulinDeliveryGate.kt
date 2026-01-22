package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import kotlin.math.abs
import kotlin.math.max

/**
 * Controleert of commanded insulin globaal zichtbaar wordt in IOB.
 *
 * - gebruikt rolling window (default 15 min)
 * - is tolerant voor ruis & IOB-lag
 * - verlaagt confidence, blokkeert niets
 */
class FCLvNextObsInsulinDeliveryGate(
    private val windowTicks: Int = 3,      // 3 × 5 min = 15 min
    private val minWindowU: Double = 0.5,  // pas checken boven deze som
    private val tolOk: Double = 0.40,      // <= 40% afwijking: ok
    private val tolFail: Double = 1.00     // >= 100% afwijking: conf=0
) {

    data class DeliveryCheck(
        val ok: Boolean,
        val confidenceMultiplier: Double,
        val reason: String? = null
    )

    private data class Tick(
        val time: DateTime,
        val iob: Double,
        val commanded: Double
    )

    private val buffer = ArrayDeque<Tick>()

    fun recordCycle(
        now: DateTime,
        commandedU: Double,
        currentIob: Double,
        phase: String
    ): DeliveryCheck {

        buffer.addLast(
            Tick(
                time = now,
                iob = currentIob,
                commanded = max(0.0, commandedU)
            )
        )

        while (buffer.size > windowTicks) {
            buffer.removeFirst()
        }

        // Nog niet genoeg data → geen oordeel
        if (buffer.size < windowTicks) {
            return DeliveryCheck(ok = true, confidenceMultiplier = 1.0)
        }

        val sumCommanded = buffer.sumOf { it.commanded }
        if (sumCommanded < minWindowU) {
            return DeliveryCheck(ok = true, confidenceMultiplier = 1.0)
        }

        val deltaIob = buffer.last().iob - buffer.first().iob

        // HARD FAIL: duidelijk geen delivery zichtbaar
        if (deltaIob <= 0.0) {
            return DeliveryCheck(
                ok = false,
                confidenceMultiplier = 0.0,
                reason = "ΔIOB≤0 while commanded≈${"%.2f".format(sumCommanded)}U"
            )
        }

        val ratio = deltaIob / sumCommanded
        val mismatch = abs(1.0 - ratio)

        val multiplier = when {
            mismatch <= tolOk -> 1.0
            mismatch >= tolFail -> 0.0
            else -> {
                val t = (mismatch - tolOk) / (tolFail - tolOk)
                (1.0 - t).coerceIn(0.0, 1.0)
            }
        }

        return DeliveryCheck(
            ok = multiplier > 0.0,
            confidenceMultiplier = multiplier,
            reason = if (multiplier < 1.0)
                "IOB mismatch ${"%.0f".format(mismatch * 100)}%"
            else null
        )
    }

    fun reset() {
        buffer.clear()
    }
}
