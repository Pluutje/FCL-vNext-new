package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max

/**
 * Dit is een "observatie-provider":
 * - determineBasal roept recordCycle() aan met:
 *   - commandedU (wat jij denkt te leveren op basis van bolus+basal)
 *   - currentIOB (uit iob_data_array[0].iob)
 *
 * - De provider bewaart records voor EpisodeSummarizer.
 * - En doet een ruwe sanity-check: komt ΔIOB ongeveer overeen met commandedU (minus simpele decay)?
 *
 * Let op:
 * - ΔIOB is NIET perfect (model/rounding), dus we gebruiken het alleen om quality/confidence te dempen.
 */
class FCLvNextObsInsulinDeliveryProvider(
    private val maxRecords: Int = 2000,
    private val cycleMinutes: Int = 5,

    /**
     * Simpele lineaire decay-aanname:
     * Over 5 minuten is IOB-afname ongeveer (IOB * 5 / DIA_minutes).
     * Default DIA=240 min (4 uur). Als jij DIA anders wil, zet dit in ctor.
     */
    private val diaMinutes: Double = 540.0
) : InsulinDeliveryProvider {

    data class DeliveryCheck(
        val ok: Boolean,
        val observedDeltaIob: Double,
        val expectedDeltaIob: Double,
        val mismatchU: Double,
        val toleranceU: Double,
        val reason: String,
        val confidenceMultiplier: Double
    )

    private val records = ArrayDeque<InsulinDeliveryRecord>()
    private var lastIob: Double? = null
    private var lastIobAt: DateTime? = null

    /**
     * Call vanuit determineBasal, 1x per 5-min cycle.
     *
     * commandedU = (shouldDeliver ? bolusAmount + basalRate*(cycle/60) : 0)
     */
    fun recordCycle(
        now: DateTime,
        commandedU: Double,
        currentIob: Double,
        phase: String = "DELIVER"
    ): DeliveryCheck {

        val prevIob = lastIob
        lastIob = currentIob
        lastIobAt = now

        // Als we nog geen vorige IOB hebben: alleen opslaan, geen check.
        if (prevIob == null) {
            val rec = InsulinDeliveryRecord(
                time = now,
                units = commandedU,
                phase = phase,
                quality = 1.0,
                observedDeltaIob = 0.0,
                expectedDeltaIob = 0.0,
                mismatchU = 0.0,
                toleranceU = 0.0,
                ok = true,
                reason = "INIT (no prev IOB)"
            )
            add(rec)
            return DeliveryCheck(
                ok = true,
                observedDeltaIob = 0.0,
                expectedDeltaIob = 0.0,
                mismatchU = 0.0,
                toleranceU = 0.0,
                reason = "INIT (no prev IOB)",
                confidenceMultiplier = 1.0
            )

        }

        val observedDelta = currentIob - prevIob

        // Simpele decay in IOB per cycle (lineair benaderd)
        val decayFrac = (cycleMinutes / diaMinutes).coerceIn(0.0, 0.20) // safety cap
        val expectedDecay = prevIob * decayFrac

        // Verwachte ΔIOB ≈ +commandedU - decay
        val expectedDelta = commandedU - expectedDecay

        // Tolerantie:
        // - basis 0.08U (rounding/modelruis)
        // - plus relatief deel van commandedU (want grotere doses -> meer speling)
        // - plus klein deel van decay (want decay schatting is grof)
        val tolerance =
            max(
                0.08,
                0.60 * commandedU + 0.15 * expectedDecay
            )

        val mismatch = abs(observedDelta - expectedDelta)

        // Alleen “fout” noemen als we ook echt iets dachten te leveren
        val meaningfulCommand = commandedU >= 0.05

        val ok =
            if (!meaningfulCommand) true
            else mismatch <= tolerance

        val reason =
            if (!meaningfulCommand) "OK (no meaningful command)"
            else if (ok) "OK (ΔIOB within tolerance)"
            else "DELIVERY_MISMATCH: obsΔ=${fmt(observedDelta)} expΔ=${fmt(expectedDelta)} mismatch=${fmt(mismatch)} tol=${fmt(tolerance)}"

        val quality =
            if (!meaningfulCommand) 1.0
            else if (ok) 1.0
            else 0.0   // jij vroeg: bij duidelijke mismatch mag conf naar 0

        val rec = InsulinDeliveryRecord(
            time = now,
            units = commandedU,
            phase = phase,
            quality = quality,
            observedDeltaIob = observedDelta,
            expectedDeltaIob = expectedDelta,
            mismatchU = mismatch,
            toleranceU = tolerance,
            ok = ok,
            reason = reason
        )

        add(rec)

        return DeliveryCheck(
            ok = ok,
            observedDeltaIob = observedDelta,
            expectedDeltaIob = expectedDelta,
            mismatchU = mismatch,
            toleranceU = tolerance,
            reason = reason,
            confidenceMultiplier = quality
        )
    }

    override fun getDeliveriesBetween(start: DateTime, end: DateTime): List<InsulinDelivery> {
        // De EpisodeSummarizer verwacht de interface-type deliveries.
        return records
            .filter { (it.time.isAfter(start) || it.time.isEqual(start)) && (it.time.isBefore(end) || it.time.isEqual(end)) }
            .map {
                InsulinDelivery(
                    time = it.time,
                    units = it.units,
                    phase = it.phase
                )
            }
    }

    fun getRecordsBetween(start: DateTime, end: DateTime): List<InsulinDeliveryRecord> {
        return records
            .filter { (it.time.isAfter(start) || it.time.isEqual(start)) && (it.time.isBefore(end) || it.time.isEqual(end)) }
            .toList()
    }

    private fun add(rec: InsulinDeliveryRecord) {
        records.addLast(rec)
        while (records.size > maxRecords) records.removeFirst()
    }

    private fun fmt(x: Double): String = "%.2f".format(x)
}

/**
 * Extra record (voor debug / episode quality)
 * - EpisodeSummarizer kan hiermee quality meenemen
 */
data class InsulinDeliveryRecord(
    val time: DateTime,
    val units: Double,
    val phase: String,
    val quality: Double,          // 1.0 ok, 0.0 mismatch (simpel model)

    val observedDeltaIob: Double,
    val expectedDeltaIob: Double,
    val mismatchU: Double,
    val toleranceU: Double,
    val ok: Boolean,
    val reason: String
)


