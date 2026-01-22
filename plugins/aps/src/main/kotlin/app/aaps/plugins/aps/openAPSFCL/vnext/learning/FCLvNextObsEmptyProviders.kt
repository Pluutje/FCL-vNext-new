package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

/**
 * Tijdelijke lege BG-provider.
 *
 * Wordt later vervangen door een echte koppeling
 * naar PersistenceLayer / CGM-historie.
 */
class EmptyBgHistoryProvider : BgHistoryProvider {

    override fun getBgBetween(
        start: DateTime,
        end: DateTime
    ): List<Pair<DateTime, Double>> {
        return emptyList()
    }
}

/**
 * Tijdelijke lege insulin-provider.
 *
 * Wordt later vervangen door een koppeling
 * naar FCL delivery logging.
 */
class EmptyInsulinDeliveryProvider : InsulinDeliveryProvider {

    override fun getDeliveriesBetween(
        start: DateTime,
        end: DateTime
    ): List<InsulinDelivery> {
        return emptyList()
    }
}
