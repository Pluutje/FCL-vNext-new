package app.aaps.plugins.aps.openAPSFCL.vnext

import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSFCL.vnext.model.BGDataPoint
import org.joda.time.DateTime
import kotlin.math.max

/**
 * Centrale BG-history provider voor FCL vNext.
 *
 * Single source of truth voor:
 * - determineBasal
 * - FCLvNext input
 * - observatie-learning
 *
 * Alle waarden worden geleverd in mmol/L
 * en gesorteerd (oudste → nieuwste).
 */
class FCLvNextBgHistoryProvider(
    private val persistenceLayer: PersistenceLayer
) {

    data class BgPoint(
        val time: DateTime,
        val bgMmol: Double
    )

    /**
     * Haal BG-data op tussen twee tijdstippen.
     */
    fun getBetween(
        start: DateTime,
        end: DateTime
    ): List<BgPoint> {

        val readings =
            persistenceLayer.getBgReadingsDataFromTimeToTime(
                start.millis,
                end.millis,
                false
            )

        if (readings.isEmpty()) return emptyList()

        val MGDL_TO_MMOL = 18.0

        return readings
            .sortedBy { it.timestamp }
            .mapNotNull { r ->
                val mmol = r.value / MGDL_TO_MMOL
                if (mmol > 0.0) {
                    BgPoint(
                        time = DateTime(r.timestamp),
                        bgMmol = mmol
                    )
                } else {
                    null
                }
            }
    }



    /**
     * Convenience: haal laatste N uren BG-data.
     * (Exact wat je nu in determineBasal doet.)
     */
    fun getLastHours(hoursBack: Int): List<BgPoint> {
        val now = DateTime.now()
        val start = now.minusHours(max(1, hoursBack))
        return getBetween(start, now)
    }
}
