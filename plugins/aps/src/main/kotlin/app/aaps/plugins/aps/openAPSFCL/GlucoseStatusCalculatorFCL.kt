package app.aaps.plugins.aps.openAPSFCL

import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.aps.openAPS.DeltaCalculator
import dagger.Reusable
import javax.inject.Inject

@Reusable
class GlucoseStatusCalculatorFCL @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val deltaCalculator: DeltaCalculator
) {

    fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatusAutoIsf? {

        val data = iobCobCalculator.ads.getBucketedDataTableCopy() ?: return null
        if (data.isEmpty()) return null

        if (data[0].timestamp < dateUtil.now() - 7 * 60 * 1000L && !allowOldData)
            return null

        val now = data[0]
        val nowDate = now.timestamp

        if (data.size == 1) {
            return GlucoseStatusAutoIsf(
                glucose = now.recalculated,
                date = nowDate
            )
        }

        val deltaResult = deltaCalculator.calculateDeltas(data)

        return GlucoseStatusAutoIsf(
            glucose = now.recalculated,
            delta = deltaResult.delta,
            shortAvgDelta = deltaResult.shortAvgDelta,
            longAvgDelta = deltaResult.longAvgDelta,
            date = nowDate
        ).also {
            aapsLogger.debug(LTag.GLUCOSE, "FCL AutoISF GlucoseStatus=$it")
        }
    }
}

