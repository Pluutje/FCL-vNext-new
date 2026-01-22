package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

/**
 * Levert BG-waarden tussen twee tijdstippen.
 * Wordt later gekoppeld aan AAPS / PersistenceLayer.
 */
interface BgHistoryProvider {

    /**
     * @return lijst van (tijd, bg in mmol/L)
     */
    fun getBgBetween(
        start: DateTime,
        end: DateTime
    ): List<Pair<DateTime, Double>>
}

/**
 * Levert insulin-afgiftes tussen twee tijdstippen.
 * Wordt later gekoppeld aan FCL delivery logging.
 */
interface InsulinDeliveryProvider {

    fun getDeliveriesBetween(
        start: DateTime,
        end: DateTime
    ): List<InsulinDelivery>
}

/**
 * Eén insulin-afgifte.
 */
data class InsulinDelivery(
    val time: DateTime,
    val units: Double,

    /**
     * FCL fase:
     * MICRO / EARLY / COMMIT / PERSISTENT
     */
    val phase: String
)
