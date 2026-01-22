package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import app.aaps.plugins.aps.openAPSFCL.vnext.FCLvNextBgHistoryProvider
import org.joda.time.DateTime

/**
 * Adapter: laat observatie-learning de centrale vNext BG-provider gebruiken
 * zonder directe afhankelijkheid van PersistenceLayer.
 */
class FCLvNextObsBgProviderAdapter(
    private val coreProvider: FCLvNextBgHistoryProvider
) : BgHistoryProvider {

    override fun getBgBetween(
        start: DateTime,
        end: DateTime
    ): List<Pair<DateTime, Double>> {

        return coreProvider
            .getBetween(start, end)
            .map { it.time to it.bgMmol }
    }
}
