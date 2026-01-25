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

        val first = buffer.first()
        val last = buffer.last()

        val deltaIob = last.iob - first.iob

// ─────────────────────────────────────────────
// Verwachte ΔIOB ≈ commanded − decay
// (zelfde simpele lineaire benadering als provider)
// ─────────────────────────────────────────────
        val cycleMinutes = 5.0
        val diaMinutes = 540.0   // moet overeenkomen met provider
        val avgIob = buffer.map { it.iob }.average()

        val decayFrac = (cycleMinutes * buffer.size / diaMinutes)
            .coerceIn(0.0, 0.30)

        val expectedDecay = avgIob * decayFrac
        val expectedDelta = sumCommanded - expectedDecay

// Te weinig verwachting → geen oordeel
        if (expectedDelta <= 0.1) {
            return DeliveryCheck(
                ok = true,
                confidenceMultiplier = 1.0,
                reason = "Expected ΔIOB too small for validation"
            )
        }

// Vergelijk OBS vs EXPECTED
        val mismatch = abs(deltaIob - expectedDelta) / expectedDelta


        val multiplier = when {
            mismatch <= tolOk -> 1.0
            mismatch >= tolFail -> 0.0
            else -> {
                val t = (mismatch - tolOk) / (tolFail - tolOk)
                (1.0 - t).coerceIn(0.0, 1.0)
            }
        }

        val ok = multiplier > 0.0


        return DeliveryCheck(
            ok = ok,
            confidenceMultiplier = multiplier,
            reason =
                if (multiplier < 1.0)
                    "IOB mismatch ${"%.0f".format(mismatch * 100)}% (expΔ=${"%.2f".format(expectedDelta)}, obsΔ=${"%.2f".format(deltaIob)})"
                else null
        )

    }

    fun reset() {
        buffer.clear()
    }
}
