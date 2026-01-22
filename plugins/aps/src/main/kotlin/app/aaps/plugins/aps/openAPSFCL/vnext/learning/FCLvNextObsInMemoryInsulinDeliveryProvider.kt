package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import java.util.ArrayDeque
import kotlin.math.max

/**
 * In-memory delivery store voor episode-summarizer.
 * Retentie is op tijd (minutes). Geen disk, geen DB.
 *
 * Belangrijk: record alleen "effectief delivered" (dus wat FCLvNext al als deliveredTotalU rapporteert).
 */
class FCLvNextObsInMemoryInsulinDeliveryProvider(
    private val retentionMinutes: Int = 8 * 60 // 8 uur
) : InsulinDeliveryProvider {

    private val buffer: ArrayDeque<InsulinDelivery> = ArrayDeque()

    override fun getDeliveriesBetween(
        start: DateTime,
        end: DateTime
    ): List<InsulinDelivery> {
        cleanup(end)
        return buffer
            .filter { (it.time.isAfter(start) || it.time.isEqual(start)) && (it.time.isBefore(end) || it.time.isEqual(end)) }
            .sortedBy { it.time.millis }
    }

    fun record(delivery: InsulinDelivery) {
        if (delivery.units <= 0.0) return
        buffer.addLast(delivery)
        cleanup(delivery.time)
    }

    private fun cleanup(now: DateTime) {
        val cutoff = now.minusMinutes(max(1, retentionMinutes))
        while (buffer.isNotEmpty()) {
            val first = buffer.first()
            if (first.time.isBefore(cutoff)) buffer.removeFirst() else break
        }
    }
}
