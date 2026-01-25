package app.aaps.plugins.aps.openAPSFCL.vnext


import android.annotation.SuppressLint
import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogger
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextParameterLogger
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextProfileParameterSnapshot
import app.aaps.plugins.aps.openAPSFCL.vnext.logging.FCLvNextCsvLogRow
import kotlin.math.roundToInt

data class FCLvNextInput(
    val bgNow: Double,                          // mmol/L
    val bgHistory: List<Pair<DateTime, Double>>, // mmol/L
    val currentIOB: Double,
    val maxIOB: Double,
    val effectiveISF: Double,                   // mmol/L per U
    val targetBG: Double,                       // mmol/L
    val isNight: Boolean
)

data class FCLvNextContext(
    val input: FCLvNextInput,

    // trends
    val slope: Double,          // mmol/L per uur
    val acceleration: Double,   // mmol/L per uur²
    val consistency: Double,    // 0..1

    // ✅ NEW short-term trend
    val recentSlope: Double,     // mmol/L per uur (laatste segment)
    val recentDelta5m: Double,   // mmol/L per 5 min

    // relatieve veiligheid
    val iobRatio: Double,       // currentIOB / maxIOB

    // afstand tot target
    val deltaToTarget: Double   // bgNow - targetBG
)

data class FCLvNextAdvice(
    val bolusAmount: Double,
    val basalRate: Double,
    val shouldDeliver: Boolean,

    // feedback naar determineBasal
    val effectiveISF: Double,
    val targetAdjustment: Double,

    // debug / UI
    val statusText: String
)

private data class DecisionResult(
    val allowed: Boolean,
    val force: Boolean,
    val dampening: Double,
    val reason: String
)

private data class ExecutionResult(
    val bolus: Double,
    val basalRate: Double,      // U/h (temp basal command; AAPS wordt elke cycle vernieuwd)
    val deliveredTotal: Double  // bolus + (basalRate * cycleHours)
)

private enum class MealState { NONE, UNCERTAIN, CONFIRMED }
private enum class TrendState {NONE, RISING_WEAK, RISING_CONFIRMED }
private enum class PeakCategory {NONE, MILD, MEAL, HIGH, EXTREME }
private enum class BgZone { LOW, IN_RANGE, MID, HIGH, EXTREME }
// ── RESCUE (hypo-prevent carbs) DETECTOR (persistent) ──
private enum class RescueState { IDLE, ARMED, CONFIRMED }
private enum class PeakPredictionState {IDLE, WATCHING, CONFIRMED }
private enum class DoseAccessLevel {BLOCKED, MICRO_ONLY, SMALL, NORMAL }
private enum class ReserveCause {PRE_UNCERTAIN_MEAL, POST_PEAK_TOP, SHORT_TERM_DIP }



private data class MealSignal(
    val state: MealState,
    val confidence: Double,     // 0..1
    val reason: String
)



private data class TrendDecision(
    val state: TrendState,
    val reason: String
)

private enum class DowntrendLock { OFF, LOCKED }

private var downtrendLock: DowntrendLock = DowntrendLock.OFF
private var downtrendConfirm: Int = 0
private var plateauConfirm: Int = 0

private data class DowntrendGate(
    val pauseThisCycle: Boolean,   // dipje → even pauze
    val locked: Boolean,           // echte daling → lock tot plateau
    val reason: String
)


/**
 * Stap 1: simpele trend-poort die snacks/ruis weert.
 * - WEAK: probe mag, maar geen grote acties
 * - CONFIRMED: grote acties toegestaan
 */
private fun classifyTrendState(ctx: FCLvNextContext, config: FCLvNextConfig): TrendDecision {

    // basis betrouwbaarheid
    if (ctx.consistency < config.minConsistency) {
        return TrendDecision(TrendState.NONE, "TREND none: low consistency")
    }

    // thresholds (startwaarden; later tunen op echte meals)
    val weakSlopeMin = 0.45
    val weakAccelMin = 0.10

    val confSlopeMin = 0.95
    val confAccelMin = 0.18
    val confDeltaMin = 1.8

    val slope = ctx.slope
    val accel = ctx.acceleration
    val delta = ctx.deltaToTarget

    // dalend/flat -> NONE
    if (slope <= 0.15 && accel <= 0.05) {
        return TrendDecision(
            TrendState.NONE,
            "TREND none: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)}"
        )
    }

    // CONFIRMED (alleen als ook delta echt boven target is)
    val confirmed =
        slope >= confSlopeMin &&
            accel >= confAccelMin &&
            delta >= confDeltaMin

    if (confirmed) {
        return TrendDecision(
            TrendState.RISING_CONFIRMED,
            "TREND confirmed: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
        )
    }

    // WEAK (voorzichtig)
    val weak =
        slope >= weakSlopeMin &&
            accel >= weakAccelMin

    if (weak) {
        return TrendDecision(
            TrendState.RISING_WEAK,
            "TREND weak: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
        )
    }

    return TrendDecision(
        TrendState.NONE,
        "TREND none: slope=${"%.2f".format(slope)} accel=${"%.2f".format(accel)} delta=${"%.2f".format(delta)}"
    )
}

private fun allowsLargeActions(
    trend: TrendState,
    persistentConfirmed: Boolean
): Boolean =
    trend == TrendState.RISING_CONFIRMED && persistentConfirmed


// ─────────────────────────────────────────────
// Meal-episode peak estimator (persistent over cycles)
// ─────────────────────────────────────────────

private data class PeakEstimatorContext(
    var active: Boolean = false,
    var startedAt: DateTime? = null,
    var startBg: Double = 0.0,

    // memory features
    var maxSlope: Double = 0.0,          // mmol/L/h
    var maxAccel: Double = 0.0,          // mmol/L/h²
    var posSlopeArea: Double = 0.0,      // mmol/L (∫ max(0,slope) dt)
    var momentum: Double = 0.0,          // mmol/L (decayed posSlopeArea)
    var lastAt: DateTime? = null,

    // state machine
    var state: PeakPredictionState = PeakPredictionState.IDLE,
    var confirmCounter: Int = 0
)

private data class PeakEstimate(
    val state: PeakPredictionState,
    val predictedPeak: Double,
    val peakBand: Int,              // 10/12/15/20 bucket (of 0 als <10)
    val maxSlope: Double,
    val momentum: Double,
    val riseSinceStart: Double
)



private fun classifyPeak(predictedPeak: Double): PeakCategory {
    return when {
        predictedPeak >= 17.5 -> PeakCategory.EXTREME
        predictedPeak >= 14.5 -> PeakCategory.HIGH
        predictedPeak >= 11.8 -> PeakCategory.MEAL
        predictedPeak >= 9.8  -> PeakCategory.MILD
        else -> PeakCategory.NONE
    }
}



private fun computeBgZone(ctx: FCLvNextContext): BgZone {
    val delta = ctx.deltaToTarget  // bgNow - target

    return when {
        ctx.input.bgNow <= 4.4 -> BgZone.LOW                    // absolute hypo zone
        delta <= 0.6 -> BgZone.IN_RANGE                         // dicht bij target
        delta <= 2.0 -> BgZone.MID                              // licht/matig boven target
        delta <= 4.5 -> BgZone.HIGH                             // duidelijk hoog
        else -> BgZone.EXTREME                                  // zeer hoog
    }
}

private fun computeDoseAccessLevel(
    ctx: FCLvNextContext,
    bgZone: BgZone
): DoseAccessLevel {

    return when (bgZone) {

        BgZone.LOW ->
            DoseAccessLevel.BLOCKED

        BgZone.IN_RANGE -> when {
            // meal/fast-rise: niet compleet blokkeren
            ctx.slope >= 0.6 && ctx.acceleration >= 0.10 -> DoseAccessLevel.MICRO_ONLY
            else -> DoseAccessLevel.BLOCKED
        }

        BgZone.MID -> when {
            ctx.slope < 0.5 ->
                DoseAccessLevel.MICRO_ONLY
            ctx.slope < 0.9 ->
                DoseAccessLevel.SMALL
            else ->
                DoseAccessLevel.NORMAL
        }

        BgZone.HIGH ->
            DoseAccessLevel.NORMAL

        BgZone.EXTREME ->
            DoseAccessLevel.NORMAL
    }
}




private val peakEstimator = PeakEstimatorContext()


private var lastCommitAt: DateTime? = null
private var lastCommitDose: Double = 0.0
private var lastCommitReason: String = ""

private var lastReentryCommitAt: DateTime? = null

// ─────────────────────────────────────────────
// 🟧 RESERVE POOL (anti-false-dip safety)
// ─────────────────────────────────────────────
private const val RESERVE_TTL_MIN = 25          // houdbaarheid reserve
private const val RESERVE_RELEASE_CAP_FRAC = 0.35 // max % van maxSMB per cycle vrijgeven

private var reservedInsulinU: Double = 0.0
private var reserveAddedAt: DateTime? = null
private var reserveCause: ReserveCause? = null

// logging helpers (reset per cycle)
private var reserveActionThisCycle: String = "NONE"
private var reserveDeltaThisCycle: Double = 0.0

// ─────────────────────────────────────────────
// ⚡ FAST-CARB micro ramp (earlier IOB without commit)
// thresholds tuned from your CSV sample
// ─────────────────────────────────────────────
// ─────────────────────────────────────────────
// 🍽️ MEAL micro ramp (earlier IOB for NORMAL meals)
// ─────────────────────────────────────────────
private const val MEAL_RISE_DELTA5M = 0.06      // mmol/5m  (vroeg, “normale” start)
private const val MEAL_RISE_SLOPE_HR = 1.0      // mmol/L/h
private const val MEAL_RISE_ACCEL = 0.08        // mmol/L/h^2

private const val MEAL_ABORT_DELTA5M = -0.04
private const val MEAL_ABORT_SLOPE_HR = -0.15
private const val MEAL_ABORT_ACCEL = -0.06

private const val MEAL_MICRO_MIN_U = 0.05
private const val MEAL_MICRO_MAX_U = 0.12

// ─────────────────────────────────────────────
// ⚡ FAST micro ramp (snelle carbs → iets hoger, maar strakker abort)
// ─────────────────────────────────────────────
private const val FAST_RISE_DELTA5M = 0.20      // mmol/5m  (echte snelle stijging)
private const val FAST_RISE_SLOPE_HR = 2.8      // mmol/L/h
private const val FAST_RISE_ACCEL = 0.14        // mmol/L/h^2

private const val FAST_ABORT_DELTA5M = -0.03
private const val FAST_ABORT_SLOPE_HR = -0.12
private const val FAST_ABORT_ACCEL = -0.05

private const val FAST_MICRO_MIN_U = 0.08
private const val FAST_MICRO_MAX_U = 0.15

// ─────────────────────────────────────────────
// Veiligheid / gating
// ─────────────────────────────────────────────
private const val MICRO_IOB_MAX = 0.45          // micro ramp alleen als er nog ruimte is
private const val MICRO_MIN_CONS = 0.45





// ── EARLY DOSE CONTROLLER (persistent) ──
private data class EarlyDoseContext(
    var stage: Int = 0,              // 0=none, 1=probe, 2=boost
    var lastFireAt: DateTime? = null,
    var lastConfidence: Double = 0.0
)

private var earlyDose = EarlyDoseContext()

// ── PRE-PEAK IMPULSE STATE ──
private var prePeakImpulseDone: Boolean = false
private var lastSegmentAt: DateTime? = null

private var lastSmallCorrectionAt: DateTime? = null

private var earlyConfirmDone: Boolean = false


private val persistCtrl = PersistentCorrectionController(
    cooldownCycles = 3,        // of 2, jij kiest
    maxBolusFraction = 0.30
)



private data class RescueDetectionContext(
    var state: RescueState = RescueState.IDLE,
    var armedAt: DateTime? = null,
    var armedBg: Double = 0.0,
    var armedPred60: Double = 0.0,
    var armedSlope: Double = 0.0,
    var armedAccel: Double = 0.0,
    var armedIobRatio: Double = 0.0,
    var lastConfirmAt: DateTime? = null,
    var lastReason: String = "",
    var confidence: Double = 0.0
)

private var rescue = RescueDetectionContext()



private const val MAX_DELIVERY_HISTORY = 6

val deliveryHistory: ArrayDeque<Pair<DateTime, Double>> =
    ArrayDeque()

private fun calculateEnergy(
    ctx: FCLvNextContext,
    kDelta: Double,
    kSlope: Double,
    kAccel: Double,
    config: FCLvNextConfig
): Double {

    val positional = ctx.deltaToTarget * kDelta
    val kinetic = ctx.slope * kSlope
    val accelerationBoost = ctx.acceleration * kAccel

    var energy = positional + kinetic + accelerationBoost

    // betrouwbaarheid (exponentieel)
    val consistency = ctx.consistency
        .coerceAtLeast(config.minConsistency)
        .let { Math.pow(it, config.consistencyExp) }

    energy *= consistency

    return energy
}

private fun calculateStagnationBoost(
    ctx: FCLvNextContext,
    config: FCLvNextConfig
): Double {

    val active =
        ctx.deltaToTarget >= config.stagnationDeltaMin &&
            ctx.slope > config.stagnationSlopeMaxNeg &&
            ctx.slope < config.stagnationSlopeMaxPos &&
            kotlin.math.abs(ctx.acceleration) <= config.stagnationAccelMaxAbs &&
            ctx.consistency >= config.minConsistency

    if (!active) return 0.0

    return config.stagnationEnergyBoost * ctx.deltaToTarget
}



private fun energyToInsulin(
    energy: Double,
    effectiveISF: Double,
    config: FCLvNextConfig
): Double {
    if (energy <= 0.0) return 0.0
    return (energy / effectiveISF) * config.gain
}



private fun decide(ctx: FCLvNextContext): DecisionResult {

    // === LAYER A — HARD STOPS ===
    if (ctx.consistency < 0.2) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: unreliable data"
        )
    }

    if (ctx.iobRatio > 1.1) {
        return DecisionResult(
            allowed = false,
            force = false,
            dampening = 0.0,
            reason = "Hard stop: IOB saturated"
        )
    }

    // === LAYER C — FORCE ALLOW ===
    if (ctx.slope > 2.0 && ctx.acceleration > 0.5 && ctx.consistency > 0.6) {
        return DecisionResult(
            allowed = true,
            force = true,
            dampening = 1.0,
            reason = "Force: strong rising trend"
        )
    }

    // === LAYER B — SOFT ALLOW ===
    val nightFactor = 1.0 //if (ctx.input.isNight) 0.7 else 1.0
    val consistencyFactor = ctx.consistency.coerceIn(0.3, 1.0)

    return DecisionResult(
        allowed = true,
        force = false,
        dampening = nightFactor * consistencyFactor,
        reason = "Soft allow"
    )
}

private fun updatePeakEstimate(
    config: FCLvNextConfig,
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    now: DateTime
): PeakEstimate {

    // ── episode start condities (flexibel, maar bewust niet te streng) ──
    val episodeShouldBeActive =
        mealSignal.state != MealState.NONE ||
            ( ctx.consistency >= 0.45 &&
                    ( ctx.deltaToTarget >= 0.6 ||
                            (ctx.acceleration >= 0.12 && ctx.slope >= 0.15)
                        )
                )

    // ── episode init/reset ──
    if (!peakEstimator.active && episodeShouldBeActive) {
        peakEstimator.active = true
        peakEstimator.startedAt = now
        peakEstimator.startBg = ctx.input.bgNow
        peakEstimator.maxSlope = ctx.slope.coerceAtLeast(0.0)
        peakEstimator.maxAccel = ctx.acceleration.coerceAtLeast(0.0)
        peakEstimator.posSlopeArea = 0.0
        peakEstimator.momentum = 0.0
        peakEstimator.lastAt = now
        peakEstimator.state = PeakPredictionState.IDLE
        peakEstimator.confirmCounter = 0
        // nieuw segment → nieuwe pre-peak impuls toegestaan
        prePeakImpulseDone = false
        lastSegmentAt = now
        earlyDose = EarlyDoseContext()
        earlyConfirmDone = false
    }

    // ── episode exit (niet te snel!) ──
    if (peakEstimator.active && !episodeShouldBeActive) {
        // exit pas als we echt “uit de meal dynamiek” zijn:
        val fallingClearly = ctx.slope <= -0.6 && ctx.consistency >= 0.55
        val lowDelta = ctx.deltaToTarget < 0.2 && ctx.acceleration <= 0.0
        if (fallingClearly || lowDelta) {
            peakEstimator.active = false
            peakEstimator.state = PeakPredictionState.IDLE
            peakEstimator.confirmCounter = 0
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false
        }
    }

    // ── update memory features ──
    val last = peakEstimator.lastAt ?: now
    val dtMin = org.joda.time.Minutes.minutesBetween(last, now).minutes.coerceAtLeast(0)
    val dtH = (dtMin / 60.0).coerceAtMost(0.2) // cap dt om rare jumps te dempen

    peakEstimator.lastAt = now

    if (peakEstimator.active && dtH > 0.0) {
        peakEstimator.maxSlope = maxOf(peakEstimator.maxSlope, ctx.slope.coerceAtLeast(0.0))
        peakEstimator.maxAccel = maxOf(peakEstimator.maxAccel, ctx.acceleration.coerceAtLeast(0.0))

        val pos = maxOf(0.0, ctx.slope) * dtH             // mmol/L
        peakEstimator.posSlopeArea += pos

        // momentum met half-life (zodat korte plateaus niet meteen alles resetten)
        val halfLifeMin = config.peakMomentumHalfLifeMin
        val decay = Math.pow(0.5, dtMin / halfLifeMin.coerceAtLeast(1.0))
        peakEstimator.momentum = peakEstimator.momentum * decay + pos
    }

    val riseSinceStart =
        if (peakEstimator.active) (ctx.input.bgNow - peakEstimator.startBg).coerceAtLeast(0.0) else 0.0

    // ── peak voorspelling: neem max van meerdere “conservatief vroege” schatters ──
    val h = config.peakPredictionHorizonH

    // 1) lokaal (maar nooit dominant als slope even wegvalt)
    val localV = maxOf(0.0, ctx.slope)
    val localA = maxOf(0.0, ctx.acceleration)

    val localBallistic =
        ctx.input.bgNow + localV * h + 0.5 * localA * h * h

    // 2) episode ballistic (houdt eerdere max-snelheid vast)
    val memV = maxOf(localV, config.peakUseMaxSlopeFrac * peakEstimator.maxSlope)
    val memA = maxOf(localA, config.peakUseMaxAccelFrac * peakEstimator.maxAccel)

    val memoryBallistic =
        ctx.input.bgNow + memV * h + 0.5 * memA * h * h

    // 3) momentum-based carry (integraal van stijging → “er komt nog meer”)
    val momentumCarry =
        ctx.input.bgNow + config.peakMomentumGain * peakEstimator.momentum

    // 4) rise-so-far scaling (als we al X mmol gestegen zijn, dan is “10” vaak te laag)
    val riseCarry =
        ctx.input.bgNow + config.peakRiseGain * riseSinceStart

    var predictedPeak = maxOf(localBallistic, memoryBallistic, momentumCarry, riseCarry)

    // nooit onder huidige BG
    predictedPeak = predictedPeak.coerceAtLeast(ctx.input.bgNow)

    // optionele bovengrens (veilig tegen explode door rare accel)
    predictedPeak = predictedPeak.coerceIn(ctx.input.bgNow, config.peakPredictionMaxMmol)

    // ── state machine (watch/confirm) op basis van predictedPeak + momentum ──
    val threshold = config.peakPredictionThreshold
    val enoughMomentum = peakEstimator.momentum >= config.peakMinMomentum
    val enoughConsistency = ctx.consistency >= config.peakMinConsistency

    when (peakEstimator.state) {
        PeakPredictionState.IDLE -> {
            if (predictedPeak >= threshold && enoughConsistency && (localV >= config.peakMinSlope || peakEstimator.maxSlope >= config.peakMinSlope) && enoughMomentum) {
                peakEstimator.state = PeakPredictionState.WATCHING
                peakEstimator.confirmCounter = 1
            }
        }

        PeakPredictionState.WATCHING -> {
            if (predictedPeak >= threshold && enoughConsistency && enoughMomentum) {
                peakEstimator.confirmCounter++
                if (peakEstimator.confirmCounter >= config.peakConfirmCycles) {
                    peakEstimator.state = PeakPredictionState.CONFIRMED
                }
            } else {
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
            }
        }

        PeakPredictionState.CONFIRMED -> {
            // exit zodra we echt post-peak zijn (jouw config-waarden)
            if (ctx.acceleration < config.peakExitAccel || ctx.slope < config.peakExitSlope) {
                peakEstimator.state = PeakPredictionState.IDLE
                peakEstimator.confirmCounter = 0
            }
        }
    }

    val band = when {
        predictedPeak >= 20.0 -> 20
        predictedPeak >= 15.0 -> 15
        predictedPeak >= 12.0 -> 12
        predictedPeak >= 10.0 -> 10
        else -> 0
    }

    return PeakEstimate(
        state = peakEstimator.state,
        predictedPeak = predictedPeak,
        peakBand = band,
        maxSlope = peakEstimator.maxSlope,
        momentum = peakEstimator.momentum,
        riseSinceStart = riseSinceStart
    )
}



private fun executeDelivery(
    dose: Double,
    hybridPercentage: Int,
    cycleMinutes: Int = 5,           // AAPS-cycle (typisch 5 min)
    maxTempBasalRate: Double = 25.0, // pomp/driver limiet (later pref)
    bolusStep: Double = 0.05,        // SMB stap
    basalRateStep: Double = 0.05,    // rate stap in U/h
    minSmb: Double = 0.05,
    smallDoseThreshold: Double = 0.4
): ExecutionResult {

    val cycleH = (cycleMinutes / 60.0).coerceAtLeast(1.0 / 60.0) // nooit 0
    val maxBasalUnitsThisCycle = (maxTempBasalRate.coerceAtLeast(0.0) * cycleH).coerceAtLeast(0.0)

    // helper: zet units -> rate, clamp en round
    fun unitsToRoundedRate(units: Double): Double {
        if (units <= 0.0) return 0.0
        val wantedRate = units / cycleH
        val cappedRate = wantedRate.coerceAtMost(maxTempBasalRate.coerceAtLeast(0.0))
        return roundToStep(cappedRate, basalRateStep).coerceAtLeast(0.0)
    }

    // 0) niets te doen: stuur expliciet 0-rate zodat lopende temp basal niet doorloopt
    if (dose <= 0.0) {
        return ExecutionResult(
            bolus = 0.0,
            basalRate = 0.0,
            deliveredTotal = 0.0
        )
    }

    // 1) Alle doses < smallDoseThreshold → volledig basaal
    if (dose < smallDoseThreshold || hybridPercentage <= 0) {

        val basalUnitsPlanned = dose.coerceAtMost(maxBasalUnitsThisCycle)
        val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
        val basalUnitsDelivered = basalRateRounded * cycleH

        // Eventueel restant (door cap/afronding) alsnog via SMB
        val missing = (dose - basalUnitsDelivered).coerceAtLeast(0.0)
        val bolusRounded =
            if (missing >= minSmb)
                roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
            else
                0.0

        return ExecutionResult(
            bolus = bolusRounded,
            basalRate = basalRateRounded,
            deliveredTotal = basalUnitsDelivered + bolusRounded
        )
    }

    // 3) hybride split (units), maar: bolus-deel < minSmb => schuif naar basaal (geen SMB-only!)
    val hp = hybridPercentage.coerceIn(0, 100)
    var basalUnitsWanted = dose * (hp / 100.0)
    var bolusUnitsWanted = (dose - basalUnitsWanted).coerceAtLeast(0.0)

    if (bolusUnitsWanted in 0.0..(minSmb - 1e-9)) {
        basalUnitsWanted = dose
        bolusUnitsWanted = 0.0
    }

    // 4) bolus afronden (kan 0 worden als we alles basaal willen)
    var bolusRounded = if (bolusUnitsWanted >= minSmb) {
        roundToStep(bolusUnitsWanted, bolusStep)
            .coerceAtLeast(minSmb)
            .coerceAtMost(dose)
    } else 0.0

    // 5) resterende units naar basaal, maar cap op wat in deze cycle kan
    val remainingForBasal = (dose - bolusRounded).coerceAtLeast(0.0)
    val basalUnitsPlanned = remainingForBasal.coerceAtMost(maxBasalUnitsThisCycle)

    val basalRateRounded = unitsToRoundedRate(basalUnitsPlanned)
    val basalUnitsDelivered = basalRateRounded * cycleH

    // 6) wat we niet kwijt konden via basaal (cap/rounding) => als extra SMB proberen
    val missing = (remainingForBasal - basalUnitsDelivered).coerceAtLeast(0.0)

    if (missing >= minSmb) {
        val extraBolus = roundToStep(missing, bolusStep).coerceAtLeast(minSmb)
        bolusRounded = (bolusRounded + extraBolus).coerceAtMost(dose)
    }

    val deliveredTotal = bolusRounded + basalUnitsDelivered

    return ExecutionResult(
        bolus = bolusRounded,
        basalRate = basalRateRounded,
        deliveredTotal = deliveredTotal
    )
}



private fun iobDampingFactor(
    iobRatio: Double,
    config: FCLvNextConfig,
    power: Double
): Double {

    val r = iobRatio.coerceIn(0.0, 2.0)

    if (r <= config.iobStart) return 1.0
    if (r >= config.iobMax) return config.iobMinFactor

    val x = ((r - config.iobStart) /
        (config.iobMax - config.iobStart))
        .coerceIn(0.0, 1.0)

    val shaped = 1.0 - Math.pow(x, power)

    return (config.iobMinFactor +
        (1.0 - config.iobMinFactor) * shaped)
        .coerceIn(config.iobMinFactor, 1.0)
}

private fun roundToStep(value: Double, step: Double): Double {
    if (step <= 0.0) return value
    return (kotlin.math.round(value / step) * step)
}

private fun clamp(value: Double, min: Double, max: Double): Double {
    return value.coerceIn(min, max)
}


private fun detectMealSignal(ctx: FCLvNextContext, config: FCLvNextConfig): MealSignal {

    // basisvoorwaarden: voldoende data
    if (ctx.consistency < config.minConsistency) {
        return MealSignal(MealState.NONE, 0.0, "Low consistency")
    }

    val thresholdMul = config.mealDetectThresholdMul

    val slopeMin = config.mealSlopeMin * thresholdMul
    val accelMin = config.mealAccelMin * thresholdMul
    val deltaMin = config.mealDeltaMin * thresholdMul


    // confidence: combineer factoren (simpel, maar werkt)
    val rising = ctx.slope > slopeMin
    val accelerating = ctx.acceleration > accelMin
    val aboveTarget = ctx.deltaToTarget > deltaMin

    val slopeScore = ((ctx.slope - slopeMin) / config.mealSlopeSpan).coerceIn(0.0, 1.0)
    val accelScore = ((ctx.acceleration - accelMin) / config.mealAccelSpan).coerceIn(0.0, 1.0)
    val deltaScore = ((ctx.deltaToTarget - deltaMin) / config.mealDeltaSpan).coerceIn(0.0, 1.0)



    val confidence =
        (0.45 * slopeScore + 0.35 * accelScore + 0.20 * deltaScore)
            .let { it * config.mealConfidenceSpeedMul }
            .coerceIn(0.0, 1.0)

    // state
    val state = when {
        rising && accelerating && aboveTarget && confidence >= config.mealConfirmConfidence ->
            MealState.CONFIRMED

        (rising || accelerating) && aboveTarget && confidence >= config.mealUncertainConfidence ->
            MealState.UNCERTAIN

        else -> MealState.NONE
    }

    val reason = "MealSignal=$state conf=${"%.2f".format(confidence)}"
    return MealSignal(state, confidence, reason)
}

private fun canCommitNow(now: DateTime, config: FCLvNextConfig): Boolean {
    val last = lastCommitAt ?: return true
    val minutes = org.joda.time.Minutes.minutesBetween(last, now).minutes
    return minutes >= config.commitCooldownMinutes
}

private fun commitFractionZoneFactor(bgZone: BgZone): Double {
    return when (bgZone) {
        BgZone.LOW -> 0.0
        BgZone.IN_RANGE -> 0.55
        BgZone.MID -> 0.75
        BgZone.HIGH -> 1.00
        BgZone.EXTREME -> 1.10
    }
}

private data class PreReserveDecision(
    val active: Boolean,
    val deliverNow: Double,
    val stash: Double,
    val reason: String
)

private fun computePreReserveSplit(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    commandedDose: Double,
    config: FCLvNextConfig,
    bgZone: BgZone
): PreReserveDecision {

    // Alleen bij meal-like maar onzeker
    if (mealSignal.state != MealState.UNCERTAIN) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Geen kleine doses knijpen
    if (commandedDose < 0.5) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Als IOB al hoog is → later mechanisme
    if (ctx.iobRatio >= 0.35) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // Als we al duidelijk afremmen → niet hier
    if (ctx.acceleration < 0.0) {
        return PreReserveDecision(false, commandedDose, 0.0, "")
    }

    // 🔹 Speciale versnelling voor echte maaltijdstijging in EXTREME
    val isAggressiveMealRise =
            bgZone == BgZone.EXTREME &&
            ctx.slope >= 0.9 &&
            ctx.acceleration >= 0.30 &&
            ctx.consistency >= 0.45 &&
            ctx.iobRatio < 0.60

    // Split: deliverFrac daalt vloeiend bij hogere doses
    val deliverFrac =
        if (isAggressiveMealRise) {
            0.85   // bijna alles leveren → sneller IOB
        } else {
            when {
                commandedDose <= 0.8 -> 0.65
                commandedDose >= 1.2 -> 0.50

                else                 -> {
                    // lineair van 0.65 → 0.50 tussen 0.8 en 1.2
                    val t = (commandedDose - 0.8) / (1.2 - 0.8)   // 0..1
                    0.65 - t * (0.65 - 0.50)
                }
            }
        }

    val deliverNow = (commandedDose * deliverFrac)
        .coerceAtMost(commandedDose)
        .coerceAtLeast(0.0)
    val stash = commandedDose - deliverNow

    return PreReserveDecision(
        active = true,
        deliverNow = deliverNow,
        stash = stash,
        reason =
            "PRE-RESERVE SPLIT: UNCERTAIN meal, low IOB (${ctx.iobRatio}), " +
                "dose=${"%.2f".format(commandedDose)}U → " +
                "${"%.2f".format(deliverNow)}U now / ${"%.2f".format(stash)}U reserve"
    )
}


private fun preMealRiseFloorU(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    bgZone: BgZone,
    suppressForPeak: Boolean,
    stagnationActive: Boolean,
    accessLevel: DoseAccessLevel,
    maxBolus: Double
): Double {

    // ── 1️⃣ WHEN: context & veiligheid ──

    // Alleen vóór meal confirm
    if (mealSignal.state == MealState.CONFIRMED) return 0.0

    // Geen floor tijdens peak/absorptie of stagnation
    if (suppressForPeak || stagnationActive) return 0.0

    if (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) return 0.0

    // Respecteer harde blokkade
    if (accessLevel == DoseAccessLevel.BLOCKED) return 0.0

    // Duidelijke, consistente stijging boven target
    val rising =
        ctx.consistency >= 0.45 &&
            (
                // normale pre-meal rise
                (ctx.deltaToTarget >= 1.2 && ctx.slope >= 0.30)
                    ||
                    // 🔥 agressieve meal rise in EXTREME
                    (
                        bgZone == BgZone.EXTREME &&
                            ctx.deltaToTarget >= 2.0 &&
                            ctx.slope >= 0.60 &&
                            ctx.acceleration >= 0.20
                        )
                )

    if (!rising) return 0.0


    // ── 2️⃣ HOW MUCH: schaal via maxBolus ──

    val zoneFraction = when (bgZone) {
        BgZone.MID     -> 0.07
        BgZone.HIGH    -> 0.12
        BgZone.EXTREME -> 0.20
        else           -> 0.0
    }

    if (zoneFraction == 0.0) return 0.0

    val rawFloor = maxBolus * zoneFraction

    // Veiligheidsclamps
    return rawFloor
        .coerceAtLeast(0.05)
        .coerceAtMost(0.50)
}

private data class CommitLearningDebug(
    val baseMin: Double,
    val multiplier: Double,
    val effectiveMin: Double
)

private fun computeCommitFraction(
    signal: MealSignal,
    config: FCLvNextConfig
): Double = when (signal.state) {

    MealState.NONE -> 0.0

    MealState.UNCERTAIN -> {
        val t =
            ((signal.confidence - config.mealUncertainConfidence) /
                (config.mealConfirmConfidence - config.mealUncertainConfidence))
                .coerceIn(0.0, 1.0)

        config.uncertainMinFraction +
            t * (config.uncertainMaxFraction - config.uncertainMinFraction)
    }

    MealState.CONFIRMED -> {
        val t =
            ((signal.confidence - config.mealConfirmConfidence) /
                (1.0 - config.mealConfirmConfidence))
                .coerceIn(0.0, 1.0)

        config.confirmMinFraction +
            t * (config.confirmMaxFraction - config.confirmMinFraction)
    }
}





private fun minutesSince(ts: DateTime?, now: DateTime): Int {
    if (ts == null) return Int.MAX_VALUE
    return org.joda.time.Minutes.minutesBetween(ts, now).minutes
}

private fun isInAbsorptionWindow(now: DateTime, config: FCLvNextConfig): Boolean {
    val m = minutesSince(lastCommitAt, now)
    return m in 0..config.absorptionWindowMinutes
}

/**
 * Detecteert "rond/na piek" gedrag: we willen dosing stoppen of sterk reduceren.
 * Logica:
 * - Alleen relevant binnen absorptionWindow na een commit
 * - Als accel negatief wordt (afremmen) OF slope bijna nul/negatief -> absorptie/peak
 */



private data class SafetyBlock(
    val active: Boolean,
    val reason: String
)



private fun hypoGuardBlock(pred60: Double): SafetyBlock {
    return if (pred60 <= 4.4) {
        SafetyBlock(true, "HYPO GUARD (pred60=${"%.2f".format(pred60)})")
    } else {
        SafetyBlock(false, "")
    }
}

private data class RescueSignal(
    val state: RescueState,
    val armed: Boolean,
    val confirmed: Boolean,
    val confidence: Double,
    val reason: String,
    val pred60: Double
)

/**
 * Soft inference: "rescue carbs likely"
 * Fase 1: alleen logging/labeling (geen dosing impact).
 */
private fun updateRescueDetection(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig,
    deliveredThisCycle: Double,
    pred60: Double
): RescueSignal {

    // --- Tunable thresholds (start conservatief) ---
    val armPred60 = 4.6          // arm bij voorspelde hypo-risk
    val armBgNow = 5.2           // of al laag-ish
    val armSlope = -0.9          // stevige daling
    val minIobToCare = 0.25      // alleen als er iob aanwezig is (anders kan het "gewoon" dalen door geen carbs)

    val confirmMinMinutes = 8
    val confirmMaxMinutes = 30

    // Rebound kenmerken: van dalend naar duidelijk herstel
    val reboundAccelMin = 0.18
    val reboundSlopeMin = 0.25

    // “Geen insulin verklaart rebound”: totaal sinds armedAt moet heel klein zijn
    val maxDeliveredSinceArmed = 0.10  // U (totaal) — start strikt

    // Cooldown na confirm (zodat je niet elke cycle confirmed blijft loggen)
    val confirmCooldownMin = 45

    // --- helper: total delivered since armedAt ---
    fun deliveredSince(t0: DateTime?): Double {
        if (t0 == null) return 0.0
        var sum = 0.0
        for ((t, u) in deliveryHistory) {
            if (t.isAfter(t0) || t.isEqual(t0)) sum += u
        }
        return sum
    }

    // --- Reset rule: na confirm cooldown terug naar IDLE ---
    if (rescue.state == RescueState.CONFIRMED) {
        val since = minutesSince(rescue.lastConfirmAt, now)
        if (since >= confirmCooldownMin) {
            rescue = RescueDetectionContext() // reset alles
        }
    }

    // --- ARM criteria (hypo risk) ---
    val armByPred = pred60 <= armPred60
    val armByDynamics =
        (ctx.input.bgNow <= armBgNow && ctx.slope <= armSlope && ctx.iobRatio >= minIobToCare && ctx.consistency >= config.minConsistency)

    val shouldArm = (armByPred || armByDynamics) && ctx.consistency >= config.minConsistency

    when (rescue.state) {
        RescueState.IDLE -> {
            if (shouldArm) {
                rescue.state = RescueState.ARMED
                rescue.armedAt = now
                rescue.armedBg = ctx.input.bgNow
                rescue.armedPred60 = pred60
                rescue.armedSlope = ctx.slope
                rescue.armedAccel = ctx.acceleration
                rescue.armedIobRatio = ctx.iobRatio
                rescue.lastReason = "ARM: pred60=${"%.2f".format(pred60)} bg=${"%.2f".format(ctx.input.bgNow)} slope=${"%.2f".format(ctx.slope)} iobR=${"%.2f".format(ctx.iobRatio)}"
                rescue.confidence = 0.35
            }
        }

        RescueState.ARMED -> {
            val t0 = rescue.armedAt
            val dt = minutesSince(t0, now)

            // Als risico verdwijnt heel snel (bv sensor ruis) -> terug naar IDLE
            val riskGone = pred60 > 5.2 && ctx.slope > -0.2 && dt >= 10
            if (riskGone) {
                rescue = RescueDetectionContext()
            } else {
                // Confirm window + rebound + no extra insulin
                val inWindow = dt in confirmMinMinutes..confirmMaxMinutes
                val rebound = (ctx.acceleration >= reboundAccelMin && ctx.slope >= reboundSlopeMin)

                val deliveredTotalSince = deliveredSince(t0) + deliveredThisCycle
                val noInsulin = deliveredTotalSince <= maxDeliveredSinceArmed

                if (inWindow && rebound && noInsulin) {
                    rescue.state = RescueState.CONFIRMED
                    rescue.lastConfirmAt = now

                    // confidence bouwen (simpel)
                    val predSeverity = ((4.6 - rescue.armedPred60) / 1.0).coerceIn(0.0, 1.0) // lager pred60 => meer
                    val reboundStrength = ((ctx.acceleration - reboundAccelMin) / 0.25).coerceIn(0.0, 1.0)
                    val insulinClean = (1.0 - (deliveredTotalSince / maxDeliveredSinceArmed)).coerceIn(0.0, 1.0)

                    rescue.confidence = (0.45 * predSeverity + 0.35 * reboundStrength + 0.20 * insulinClean).coerceIn(0.0, 1.0)

                    rescue.lastReason =
                        "CONFIRM: dt=${dt}m pred60@arm=${"%.2f".format(rescue.armedPred60)} " +
                            "→ rebound slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                            "delivSince=${"%.2f".format(deliveredTotalSince)}U conf=${"%.2f".format(rescue.confidence)}"
                }
            }
        }

        RescueState.CONFIRMED -> {
            // niks: cooldown reset doet het werk
        }
    }

    return RescueSignal(
        state = rescue.state,
        armed = rescue.state == RescueState.ARMED,
        confirmed = rescue.state == RescueState.CONFIRMED,
        confidence = rescue.confidence,
        reason = rescue.lastReason,
        pred60 = pred60
    )
}


private fun predictBg60(ctx: FCLvNextContext): Double {
    val h = 1.0
    return ctx.input.bgNow + ctx.slope * h + 0.5 * ctx.acceleration * h * h
}

private data class MicroRampResult(
    val active: Boolean,
    val microU: Double,
    val tier: String,
    val reason: String
)

private fun computeMicroRamp(ctx: FCLvNextContext, config: FCLvNextConfig): MicroRampResult {
    val mul = config.microRampThresholdMul
    // algemene safety: meteen stoppen als fast lane draait
    val hardAbort =
        ctx.recentDelta5m <= MEAL_ABORT_DELTA5M ||
            ctx.recentSlope <= MEAL_ABORT_SLOPE_HR ||
            ctx.acceleration <= MEAL_ABORT_ACCEL

    if (hardAbort) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO blocked (abort)")
    }

    val safe =
        ctx.consistency >= MICRO_MIN_CONS &&
            ctx.iobRatio <= MICRO_IOB_MAX &&
            ctx.recentDelta5m > 0.0

    if (!safe) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO none (safe=false)")
    }

    // Tier detectie
    val fast =
        ctx.recentDelta5m >= FAST_RISE_DELTA5M * mul ||
            (ctx.recentSlope >= FAST_RISE_SLOPE_HR * mul && ctx.acceleration >= FAST_RISE_ACCEL * mul)

    val meal =
        // alleen zinvol als we echt boven target zitten
        ctx.deltaToTarget >= 0.8 * mul && (
            // eerder triggeren op fast-lane rise
            ctx.recentDelta5m >= 0.06 * mul ||
            ctx.recentSlope >= 0.60 * mul ||
            ( ctx.recentSlope >= MEAL_RISE_SLOPE_HR * mul &&  ctx.acceleration >= MEAL_RISE_ACCEL * mul  ) ||
             ctx.recentDelta5m >= MEAL_RISE_DELTA5M * mul  )


    if (!fast && !meal) {
        return MicroRampResult(false, 0.0, "NONE", "MICRO none")
    }

    // Extra strakke abort alleen voor FAST-tier
    if (fast) {
        val fastAbort =
            ctx.recentDelta5m <= FAST_ABORT_DELTA5M ||
                ctx.recentSlope <= FAST_ABORT_SLOPE_HR ||
                ctx.acceleration <= FAST_ABORT_ACCEL

        if (fastAbort) {
            return MicroRampResult(false, 0.0, "FAST", "FAST-MICRO blocked (fast abort)")
        }
    }

    // Schalen: we schalen op recentDelta5m (stabielste snelle indicator)
    val minU: Double
    val maxU: Double
    val t0: Double
    val t1: Double
    val tierName: String

    if (fast) {
        minU = FAST_MICRO_MIN_U
        maxU = FAST_MICRO_MAX_U
        t0 = FAST_RISE_DELTA5M
        t1 = 0.55
        tierName = "FAST"
    } else {
        minU = MEAL_MICRO_MIN_U
        maxU = MEAL_MICRO_MAX_U
        t0 = MEAL_RISE_DELTA5M
        t1 = 0.35
        tierName = "MEAL"
    }


    val tt = smooth01((ctx.recentDelta5m - t0) / (t1 - t0))
    val micro = ((minU + (maxU - minU) * tt) * config.microDoseMul).coerceIn(minU * 0.5, maxU * 1.5)


    return MicroRampResult(
        active = true,
        microU = micro,
        tier = tierName,
        reason = "MICRO-$tierName: Δ5m=${"%.2f".format(ctx.recentDelta5m)} slope=${"%.2f".format(ctx.recentSlope)} accel=${"%.2f".format(ctx.acceleration)} iobR=${"%.2f".format(ctx.iobRatio)}"
    )
}




private fun smooth01(x: Double): Double {
    val t = x.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)   // smoothstep
}

private fun invSmooth01(x: Double): Double = 1.0 - smooth01(x)

private fun lerp(a: Double, b: Double, t: Double): Double =
    a + (b - a) * t.coerceIn(0.0, 1.0)

private data class EarlyDoseDecision(
    val active: Boolean,
    val stageToFire: Int,          // 0=none, 1=probe, 2=boost
    val confidence: Double,        // 0..1
    val targetU: Double,           // floor target for commandedDose
    val reason: String
)

private fun computeEarlyDoseDecision(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    trend: TrendDecision,
    trendConfirmedPersistent: Boolean, // <-- nieuw
    bgZone : BgZone,
    now: DateTime,
    config: FCLvNextConfig
    ): EarlyDoseDecision {

    if (ctx.consistency < 0.45) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: low consistency")
    }

    // Fastlane veto: CGM laat al dip zien → geen early push
    if (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: fastlane dip")
    }

    if ((bgZone == BgZone.LOW || bgZone == BgZone.IN_RANGE) && ctx.iobRatio >= 0.55) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY blocked: low BG zone")
    }


    if (peak.state == PeakPredictionState.CONFIRMED) {
        return EarlyDoseDecision(false, 0, 0.0, 0.0, "EARLY: peak confirmed")
    }

    val slopeScore = smooth01((ctx.slope - 0.20) / (1.20 - 0.20))
    val accelScore = smooth01((ctx.acceleration - (-0.02)) / (0.15 - (-0.02)))
    val deltaScore = smooth01((ctx.deltaToTarget - 0.0) / 1.6)
    val consistScore = smooth01((ctx.consistency - 0.45) / 0.35)
    val iobRoom = invSmooth01((ctx.iobRatio - 0.20) / 0.50)

    val watchingBonus =
        if (peak.state == PeakPredictionState.WATCHING) 0.10 else 0.0

    val mealBonus = when (mealSignal.state) {
        MealState.CONFIRMED -> 0.18
        MealState.UNCERTAIN -> 0.10
        MealState.NONE -> 0.0
    }

    var conf =
        0.32 * slopeScore +
            0.30 * accelScore +
            0.18 * deltaScore +
            0.10 * consistScore +
            0.10 * iobRoom +
            watchingBonus +
            mealBonus

    val peakEscalation =
        if (peak.predictedPeak >= 12.5 &&
            ctx.iobRatio <= 0.45 &&
            ctx.consistency >= config.minConsistency
        ) config.earlyPeakEscalationBonus else 0.0

    conf += peakEscalation

    conf = conf.coerceIn(0.0, 1.0)

    val baseStage1Min = 0.28
// ✅ Stage2 eerder als de voorspelde piek groot is (timing fix, niet meer totaal)
    val stage2Min =
        if (peak.predictedPeak >= 16.0) 0.48 else 0.55

// jouw bestaande fast-carb versneller (blijft bestaan)
    val fastCarbStage1Mul =
        if (ctx.acceleration >= 0.35 && ctx.iobRatio <= 0.25) 0.75 else 1.0

// profiel beïnvloedt alleen timing (threshold)
    var dynamicStage1Min = (baseStage1Min * fastCarbStage1Mul * config.earlyStage1ThresholdMul).coerceIn(0.12, 0.45)

    // 🔹 EXTREME-zone: stage-1 iets eerder toestaan
    if (
        bgZone == BgZone.EXTREME &&
        ctx.slope >= 0.8 &&
        ctx.acceleration >= 0.25 &&
        ctx.consistency >= 0.45 &&
        ctx.deltaToTarget >= 2.5
    ) {
        dynamicStage1Min = (dynamicStage1Min / config.mealConfidenceSpeedMul).coerceIn(0.10, 0.50)
    }


    val minutesSinceLastFire =
        minutesSince(earlyDose.lastFireAt, now)

    val allowLarge = allowsLargeActions(trend.state, trendConfirmedPersistent)

// stageToFire:
    val stageToFire = when {
        earlyDose.stage == 0 && conf >= dynamicStage1Min -> 1
        earlyDose.stage == 1 && conf >= stage2Min &&
            minutesSinceLastFire >= 5 && allowLarge -> 2
        else -> 0
    }
    if (earlyDose.stage == 1 && conf >= stage2Min && minutesSinceLastFire >= 5 && !allowLarge) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: stage2 blocked (trend=${trend.state})")
    }

    if (stageToFire == 0) {
        return EarlyDoseDecision(false, 0, conf, 0.0, "EARLY: no fire")
    }

    val (minF, maxF) =
        if (stageToFire == 1) 0.40 to 0.70 else 0.55 to 0.90

    var factor = lerp(minF, maxF, conf)

    val iobPenalty = smooth01((ctx.iobRatio - 0.35) / 0.40)
    factor *= (1.0 - 0.35 * iobPenalty)
// iets meer iob demping als de piek te hoog is
 /*   val iobPenalty = smooth01((ctx.iobRatio - 0.30) / 0.40)
    factor *= (1.0 - 0.45 * iobPenalty)  */

  //  if (ctx.input.isNight && ctx.iobRatio >= 0.55) factor *= 0.88


  //  val targetU = (config.maxSMB * factor * config.doseStrengthMul).coerceIn(0.0, config.maxSMB)

    val minEarlyFrac =
        if (
            stageToFire == 1 &&
            ctx.deltaToTarget >= 0.8 &&
            ctx.slope >= 0.35 &&
            ctx.consistency >= 0.45
        ) {
            // EARLY stage-1 floor:
            // slope ~0.35  -> ~0.20 * maxSMB
            // slope >=1.0  -> ~0.30 * maxSMB
            val t = smooth01((ctx.slope - 0.35) / (1.00 - 0.35))
            0.20 + t * 0.10
        } else {
            0.0
        }

    val minEarlyU =
        (config.maxSMB * minEarlyFrac)
            .coerceIn(0.10, config.maxSMB * 0.35)   // absolute safety rails


    val targetU =
        maxOf(
            config.maxSMB * factor * config.doseStrengthMul,
            minEarlyU
        )


    return EarlyDoseDecision(
        active = true,
        stageToFire = stageToFire,
        confidence = conf,
        targetU = targetU,
        reason = "EARLY: stage=$stageToFire conf=${"%.2f".format(conf)}"
    )
}

private data class PostPeakSummary(
    val suppress: Boolean,
    val lockout: Boolean,
    val commitBlocked: Boolean,
    val commitFactor: Double,
    val noStash: Boolean,
    val reason: String
)

private fun evaluatePostPeak(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    now: DateTime,
    config: FCLvNextConfig
): PostPeakSummary {

    val inAbsorption = isInAbsorptionWindow(now, config)
    val reliable = ctx.consistency >= config.minConsistency

    // Basale post-peak kenmerken
    val flattening = ctx.acceleration <= 0.05
    val notRising = ctx.slope < 0.60
    val highIob = ctx.iobRatio >= 0.55

    // SUPPRESS: mild, alleen tijdens absorption
    val suppress =
        inAbsorption && reliable &&
            (ctx.slope <= config.peakSlopeThreshold || ctx.acceleration <= config.peakAccelThreshold)

    // LOCKOUT: hard, ook tijdens absorption
    val dynamicIobThreshold =
        (0.30 + 0.07 * ctx.deltaToTarget).coerceIn(0.30, 0.70)
    val lockout =
        inAbsorption && reliable &&
            ((ctx.slope <= config.peakSlopeThreshold) || (ctx.acceleration <= config.peakAccelThreshold)) &&
            (ctx.iobRatio >= dynamicIobThreshold)

    // COMMIT BLOCK: voorkomen van “commit na omkeer”
    val commitBlocked =
        ctx.acceleration < -0.05 &&
            ctx.iobRatio >= 0.45 &&
            reliable

    // Commit damp factor (oude computePostPeakDamp logica, maar compact)
    val episodeLike =
        mealSignal.state != MealState.NONE || peakEstimator.active || peak.state != PeakPredictionState.IDLE

    val commitFactor =
        if (!episodeLike) 1.0
        else if (!(highIob && flattening && notRising && reliable)) 1.0
        else {
            val iobSeverity = smooth01((ctx.iobRatio - 0.55) / 0.35)
            val accelSeverity = smooth01((0.05 - ctx.acceleration) / 0.15)
            val slopeSeverity = smooth01((0.60 - ctx.slope) / 0.80)
            val severity = (0.45 * iobSeverity + 0.35 * accelSeverity + 0.20 * slopeSeverity).coerceIn(0.0, 1.0)
            (1.0 - 0.65 * severity).coerceIn(0.35, 1.0)
        }

    // NO-STASH window: als je al in post-peak afvlak zit, niet nog extra potjes maken
    val noStash =
        highIob && flattening && reliable && ctx.recentSlope <= 0.20

    val reason =
        "POSTPEAK: suppress=$suppress lockout=$lockout commitBlocked=$commitBlocked " +
            "commitFactor=${"%.2f".format(commitFactor)} noStash=$noStash " +
            "iobR=${"%.2f".format(ctx.iobRatio)} slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)}"

    return PostPeakSummary(
        suppress = suppress,
        lockout = lockout,
        commitBlocked = commitBlocked,
        commitFactor = commitFactor,
        noStash = noStash,
        reason = reason
    )
}


private fun trajectoryDampingFactor(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    bgZone: BgZone,
    config: FCLvNextConfig
): Double {



    // Betrouwbaarheid
    if (ctx.consistency < config.minConsistency) return 1.0

    val delta = ctx.deltaToTarget        // mmol boven target
    val iobR  = ctx.iobRatio             // 0.. ~1+
    val slope = ctx.slope                // mmol/L/h
    val accel = ctx.acceleration         // mmol/L/h^2


    // 1) BG/delta: hoe hoger boven target, hoe minder remming
    //    delta=0 -> 0, delta>=6 -> 1
    val deltaScore = smooth01((delta - 0.0) / 6.0)

    // 2) IOB: hoe hoger, hoe meer remming
    //    iob<=0.35 -> ~0 rem, iob>=0.85 -> ~1 rem
    val iobPenalty = smooth01((iobR - 0.35) / (0.85 - 0.35))

    // 3) Slope: dalend/flat geeft remming, stijgend haalt remming weg
    //    slope<=-0.6 -> 1 rem, slope>=+1.0 -> 0 rem
    val slopePenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            invSmooth01((slope - (-0.8)) / (1.2 - (-0.8)))

        BgZone.MID ->
            invSmooth01((slope - (-0.6)) / (1.0 - (-0.6)))

        BgZone.IN_RANGE, BgZone.LOW ->
            invSmooth01((slope - (-0.2)) / (0.6 - (-0.2)))
    }

    // 4) Accel: negatief (afremmen/omkeren) geeft remming
    //    accel<=-0.10 -> 1 rem, accel>=+0.15 -> 0 rem
    val accelPenalty = when (bgZone) {
        BgZone.EXTREME, BgZone.HIGH ->
            invSmooth01((accel - (-0.15)) / (0.20 - (-0.15)))

        BgZone.MID ->
            invSmooth01((accel - (-0.10)) / (0.15 - (-0.10)))

        BgZone.IN_RANGE, BgZone.LOW ->
            invSmooth01((accel - (-0.02)) / (0.08 - (-0.02)))
    }

    // 5) Meal: als we in meal staan, minder streng (want stijging kan “legit” zijn)
    val mealRelax = when (mealSignal.state) {
        MealState.NONE -> 1.0
        MealState.UNCERTAIN -> 0.75
        MealState.CONFIRMED -> 0.55
    }

    // Combineer:
    // - Penalties versterken elkaar
    // - deltaScore werkt “tegen” penalties in (hoge delta laat meer toe)
    val combinedPenalty =
        (0.20 * iobPenalty + 0.45 * slopePenalty + 0.35 * accelPenalty)
            .coerceIn(0.0, 1.0)

    // Baseline factor: 1 - penalty
    var factor = (1.0 - combinedPenalty).coerceIn(0.0, 1.0)

    // Delta laat factor weer oplopen (bij hoge delta minder rem)
    // deltaScore=0 -> geen uplift, deltaScore=1 -> uplift tot +0.35
    factor = (factor + 0.35 * deltaScore).coerceIn(0.0, 1.0)

    // Meal relax (vermindert penalties) -> factor omhoog
    factor = (factor / mealRelax).coerceAtMost(1.0)

    // Extra bescherming: hoge IOB + geen meal + geen echte stijging → factor sterk omlaag
    if (mealSignal.state == MealState.NONE &&
        ctx.iobRatio >= 0.65 &&
        ctx.slope < 0.8
    ) {
        factor *= 0.25
    }

    // 🔒 LOW-BG HARD CLAMP
    if (bgZone == BgZone.LOW && ctx.slope <= 0.0) {
        return 0.0
    }

    return factor
}

private fun isEarlyProtectionActive(
    earlyStage: Int,
    ctx: FCLvNextContext,
    peak: PeakEstimate
): Boolean {

    if (earlyStage <= 0) return false

    // ❗ ZODRA AFREM MEN BEGINT → early protection UIT
    if (ctx.acceleration < 0.0) return false

    if (peak.state == PeakPredictionState.CONFIRMED) return false

    return true
}

private fun updateDowntrendGate(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peak: PeakEstimate,
    config: FCLvNextConfig
): DowntrendGate {

    // --- Drempels (startwaarden; later eventueel in config) ---
    val minCons = 0.45

    // “dipje” → pauze (1 cycle), maar niet locken
    val pauseSlopeHr = -0.25          // mmol/L/h
    val pauseDelta5m = -0.10          // mmol/5m

    // “echte daling ingezet” → LOCKED na confirm cycles
    val lockSlopeHr = -0.60           // mmol/L/h
    val lockDelta5m = -0.20           // mmol/5m
    val lockConfirmCycles = 2

    // “plateau” → unlock (hysterese)
    val plateauSlopeAbs = 0.15        // |mmol/L/h|
    val plateauDelta5mAbs = 0.05      // |mmol/5m|
    val plateauConfirmCycles = 2

    val reliable = ctx.consistency >= minCons

    // We baseren daling op fast lane:
    val fallingHard = reliable &&
        (ctx.recentSlope <= lockSlopeHr || ctx.recentDelta5m <= lockDelta5m) &&
        ctx.deltaToTarget > 0.3 // vermijd lock rond target door mini-ruis

    val fallingSoft = reliable &&
        (ctx.recentSlope <= pauseSlopeHr || ctx.recentDelta5m <= pauseDelta5m) &&
        ctx.deltaToTarget > 0.3

    val plateau = reliable &&
        kotlin.math.abs(ctx.recentSlope) <= plateauSlopeAbs &&
        kotlin.math.abs(ctx.recentDelta5m) <= plateauDelta5mAbs

    val risingAgain = reliable && (ctx.recentSlope >= 0.20 || ctx.recentDelta5m >= 0.06)

    // --- state machine ---
    when (downtrendLock) {

        DowntrendLock.OFF -> {
            if (fallingHard) {
                downtrendConfirm++
                if (downtrendConfirm >= lockConfirmCycles) {
                    downtrendLock = DowntrendLock.LOCKED
                    plateauConfirm = 0
                    return DowntrendGate(
                        pauseThisCycle = false,
                        locked = true,
                        reason = "DOWNTREND LOCKED: recentSlope=${"%.2f".format(ctx.recentSlope)} recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                    )
                }
            } else {
                downtrendConfirm = 0
            }

            // dipje: pauze 1 cycle, maar geen lock
            if (fallingSoft) {
                return DowntrendGate(
                    pauseThisCycle = true,
                    locked = false,
                    reason = "DOWNTREND PAUSE: recentSlope=${"%.2f".format(ctx.recentSlope)} recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                )
            }

            return DowntrendGate(false, false, "DOWNTREND OFF")
        }

        DowntrendLock.LOCKED -> {

            // unlock pas bij plateau OF duidelijke hernieuwde stijging
            if (risingAgain) {
                downtrendLock = DowntrendLock.OFF
                downtrendConfirm = 0
                plateauConfirm = 0
                return DowntrendGate(false, false, "DOWNTREND UNLOCK (rising again)")
            }

            if (plateau) {
                plateauConfirm++
                if (plateauConfirm >= plateauConfirmCycles) {
                    downtrendLock = DowntrendLock.OFF
                    downtrendConfirm = 0
                    plateauConfirm = 0
                    return DowntrendGate(false, false, "DOWNTREND UNLOCK (plateau)")
                }
            } else {
                plateauConfirm = 0
            }

            return DowntrendGate(
                pauseThisCycle = false,
                locked = true,
                reason = "DOWNTREND LOCKED (holding)"
            )
        }
    }
}




private fun shouldHardBlockTrajectory(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    earlyStage: Int,
    peak: PeakEstimate,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {

    // ✅ Extra harde regel: duidelijke omkeer + al redelijk IOB -> meteen blokkeren
    if (isInAbsorptionWindow(now, config) &&
        ctx.acceleration <= -0.10 && ctx.iobRatio >= 0.35 && ctx.consistency >= 0.45
    ) return true
    // early bescherming alleen zolang we nog niet afremmen / piek hebben
    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false

    // nooit hard block bij meal
    if (mealSignal.state != MealState.NONE) return false

    val highIob = ctx.iobRatio >= 0.70
    val notReallyRising = ctx.slope < 0.6
    val decelerating = ctx.acceleration <= -0.05
    val reliable = ctx.consistency >= 0.5

    return highIob && notReallyRising && decelerating && reliable
}

private fun shouldBlockMicroCorrections(
    ctx: FCLvNextContext,
    mealSignal: MealSignal,
    peakCategory: PeakCategory,
    earlyStage: Int,
    peak: PeakEstimate,
    config: FCLvNextConfig
): Boolean {

    if (isEarlyProtectionActive(earlyStage, ctx, peak)) return false


    // Alleen voor "geen-meal" correcties
    if (mealSignal.state != MealState.NONE) return false

    // Als het echt een meal/high episode is, niet blokkeren
    if (peakCategory >= PeakCategory.MEAL) return false

    val fallingOrFlat =
        ctx.slope <= config.correctionHoldSlopeMax &&   // bv <= -0.20
            ctx.acceleration <= config.correctionHoldAccelMax && // bv <= 0.05
            ctx.consistency >= config.minConsistency

    // Als BG nog maar weinig boven target zit -> zeker wachten
    val notFarAboveTarget =
        ctx.deltaToTarget <= config.correctionHoldDeltaMax  // bv <= 1.5

    return fallingOrFlat && notFarAboveTarget
}

/**
 * Re-entry: tweede gang / dessert.
 * Alleen toestaan als:
 * - genoeg tijd sinds commit
 * - én duidelijke nieuwe stijging (slope/accel/delta)
 * - én reentry cooldown gerespecteerd
 */
private fun isReentrySignal(
    ctx: FCLvNextContext,
    now: DateTime,
    config: FCLvNextConfig
): Boolean {
    val sinceCommit = minutesSince(lastCommitAt, now)
    if (sinceCommit < config.reentryMinMinutesSinceCommit) return false

    val sinceReentry = minutesSince(lastReentryCommitAt, now)
    if (sinceReentry < config.reentryCooldownMinutes) return false

    val rising = ctx.slope >= config.reentrySlopeMin
    val accelerating = ctx.acceleration >= config.reentryAccelMin
    val aboveTarget = ctx.deltaToTarget >= config.reentryDeltaMin
    val reliable = ctx.consistency >= config.minConsistency

    return reliable && aboveTarget && rising && accelerating
}


class FCLvNext(
    private val preferences: Preferences
) {


    private val profileParamLogger =
        FCLvNextParameterLogger(
            fileName = "FCLvNext_ProfileParameters.csv"
        ) {
            FCLvNextProfileParameterSnapshot.collect(preferences)
        }




    private fun buildContext(input: FCLvNextInput, config: FCLvNextConfig): FCLvNextContext {
        val filteredHistory = FCLvNextBgFilter.ewma(
            data = input.bgHistory,
            alpha = config.bgSmoothingAlpha
        )
        val sortedHistory = input.bgHistory.sortedBy { it.first.millis }

        val rawPoints = sortedHistory.map { (t, bg) -> FCLvNextTrends.BGPoint(t, bg) }
        val filteredPoints = filteredHistory.sortedBy { it.first.millis }
            .map { (t, bg) -> FCLvNextTrends.BGPoint(t, bg) }


        val trends = FCLvNextTrends.calculateTrends(
            rawData = rawPoints,
            filteredData = filteredPoints
        )


        val iobRatio = if (input.maxIOB > 0.0) {
            (input.currentIOB / input.maxIOB).coerceIn(0.0, 1.5)
        } else 0.0

        return FCLvNextContext(
            input = input,
            slope = trends.firstDerivative,
            acceleration = trends.secondDerivative,
            consistency = trends.consistency,
            recentSlope = trends.recentSlope,
            recentDelta5m = trends.recentDelta5m,
            iobRatio = iobRatio,
            deltaToTarget = input.bgNow - input.targetBG
        )
    }

    private fun handleReserveResetAndTtl(
        didCommitThisCycle: Boolean,
        now: DateTime,
        status: StringBuilder
    ) {
        if (reservedInsulinU <= 0.0) return

        if (didCommitThisCycle) {
            status.append("RESERVE RESET: new commit this cycle\n")
            reservedInsulinU = 0.0
            reserveAddedAt = null
            reserveCause = null
            return
        }

        val ageMin = minutesSince(reserveAddedAt, now)
        if (ageMin >= RESERVE_TTL_MIN) {
            status.append(
                "RESERVE EXPIRED: age=${ageMin}m >= ${RESERVE_TTL_MIN}m " +
                    "(cause=${reserveCause})\n"
            )
            reservedInsulinU = 0.0
            reserveAddedAt = null
            reserveCause = null
        }
    }


    @SuppressLint("SuspiciousIndentation") fun getAdvice(input: FCLvNextInput): FCLvNextAdvice {
         // reset reserve logging per cycle
        reserveActionThisCycle = "NONE"
        reserveDeltaThisCycle = 0.0

        val logRow = FCLvNextCsvLogRow(
            ts = DateTime.now(),
            isNight = input.isNight,
            bg = input.bgNow,
            target = input.targetBG
        )

        
        // ─────────────────────────────────────────────
        // 1️⃣ Config & context (trends, IOB, delta)
        // ─────────────────────────────────────────────
        val config = loadFCLvNextConfig(preferences, input.isNight)




        val ctx = buildContext(input, config)
        val pred60 = predictBg60(ctx)

        val zoneEnum = computeBgZone(ctx)
        val bgZone = zoneEnum.name

        logRow.heightIntent = "NONE"
        logRow.profielNaam = config.profielNaam
        logRow.mealDetectSpeed = config.mealDetectSpeed
        logRow.correctionStyle = config.correctionStyle
        logRow.doseDistributionStyle = config.doseDistributionStyle

        logRow.bgZone = bgZone
        logRow.iob = input.currentIOB
        logRow.iobRatio = ctx.iobRatio

        logRow.slope = ctx.slope
        logRow.accel = ctx.acceleration
        logRow.recentSlope = ctx.recentSlope
        logRow.recentDelta5m = ctx.recentDelta5m
        logRow.consistency = ctx.consistency


        val status = StringBuilder()
        status.append("PROFILE=${config.profielNaam}\n")
        status.append("MEAL_SPEED=${config.mealDetectSpeed}\n")
        status.append("CORRECTION=${config.correctionStyle}\n")



        // ─────────────────────────────────────────────
        // 2️⃣ Persistent HIGH BG detectie
        //     (los van maaltijdlogica)
        // ─────────────────────────────────────────────


        // ─────────────────────────────────────────────
        // 3️⃣ Energie-model (positie + snelheid + versnelling)
        // ─────────────────────────────────────────────

        var energy = calculateEnergy(
            ctx = ctx,
            kDelta = config.kDelta,
            kSlope = config.kSlope,
            kAccel = config.kAccel,
            config = config
        )

        // ─────────────────────────────────────────────
       // 🔒 ENERGY EXHAUSTION GATE (post-rise hard stop)
       // ─────────────────────────────────────────────
        val energyExhausted =
            ctx.deltaToTarget >= 4.5 &&            // duidelijk boven target
                ctx.acceleration in -0.05..0.05 &&             // versnelling vrijwel weg
                ctx.slope >= 0.8 &&                     // slope hoog door historie
                ctx.iobRatio >= 0.55 &&                 // al voldoende insulin aan boord
                ctx.consistency >= config.minConsistency



        if (energyExhausted) {
            status.append("ENERGY EXHAUSTED (log-only)\n")
            // energy blijft onaangetast
        }

        val stagnationBoost =
            calculateStagnationBoost(ctx, config)

        val energyTotal = energy + stagnationBoost

        status.append(
            "StagnationBoost=${"%.2f".format(stagnationBoost)}\n"
        )


        // ─────────────────────────────────────────────
        // 4️⃣ Ruwe dosis uit energie
        // ─────────────────────────────────────────────
        val rawDose = energyToInsulin(
            energy = energyTotal,
            effectiveISF = input.effectiveISF,
            config = config
        )
        status.append("RawDose=${"%.2f".format(rawDose)}U\n")

        // ─────────────────────────────────────────────
        // 5️⃣ Beslissingslaag (hard stop / force / soft allow)
        // ─────────────────────────────────────────────
        val decision = decide(ctx)

        val decidedDose = when {
            !decision.allowed -> 0.0
            decision.force -> rawDose
            else -> rawDose * decision.dampening
        }

        status.append(
            "Decision=${decision.reason} → ${"%.2f".format(decidedDose)}U\n"
        )

        // ─────────────────────────────────────────────
        // 6️⃣ IOB-remming (centraal, altijd toepassen)
        // ─────────────────────────────────────────────
        // ─────────────────────────────────────────────
        // 6a️⃣ Peak prediction (voor IOB-remming)
        // ─────────────────────────────────────────────

        val now = DateTime.now()
        val mealSignal = detectMealSignal(ctx, config)
        // Peak-estimator mag ook actief worden zonder mealSignal,
        // maar niet bij lage betrouwbaarheid
        if (ctx.consistency < config.minConsistency) {
            peakEstimator.active = false
        }
        val peak = updatePeakEstimate(config, ctx, mealSignal, now)
        val peakState = peak.state
        val predictedPeak = peak.predictedPeak
        val peakCategory = classifyPeak(predictedPeak)

        // ─────────────────────────────────────────────
       // 🧯 DOWN-TREND GATE (short-term trend lockout)
       // ─────────────────────────────────────────────
        val downGate = updateDowntrendGate(ctx, mealSignal, peak, config)
        status.append(downGate.reason + "\n")

        val trend = classifyTrendState(ctx, config)
        status.append(trend.reason + "\n")

        // ─────────────────────────────────────────────
        // 🔴 LONG-SLOPE SAFETY BLOCK (anti-hypo)
        // Blokkeer dosing bij sterke structurele daling,
        // ook als short-term ruis het maskeert
        // ─────────────────────────────────────────────



        val hardNoDelivery =
            downGate.pauseThisCycle ||
                (
                    ctx.slope <= -1.0 &&
                        ctx.deltaToTarget <= 3.0 &&
                        ctx.consistency >= 0.55
                    )

        if (hardNoDelivery) {

            val reason =
                if (downGate.pauseThisCycle) {
                    "SHORT-TERM DIP: recentSlope=${"%.2f".format(ctx.recentSlope)} " +
                        "recentΔ5m=${"%.2f".format(ctx.recentDelta5m)}"
                } else {
                    "LONG-SLOPE BLOCK: slope=${"%.2f".format(ctx.slope)} " +
                        "delta=${"%.2f".format(ctx.deltaToTarget)}"
                }

            status.append("$reason → handoff to AAPS\n")

            // ⬇️ LOGROW VULLEN
            logRow.decisionReason = reason
            logRow.finalDose = 0.0
            logRow.commandedDose = 0.0
            logRow.deliveredTotal = 0.0
            logRow.bolus = 0.0
            logRow.basalRate = 0.0
            logRow.shouldDeliver = false

            // ⬇️ LOGGEN
            FCLvNextCsvLogger.log(logRow)

            // ⬇️ ÉÉN return
            return FCLvNextAdvice(
                bolusAmount = 0.0,
                basalRate = 0.0,
                shouldDeliver = false,
                effectiveISF = input.effectiveISF,
                targetAdjustment = 0.0,
                statusText = status.toString()
            )
        }



// downGate.locked: NIET returnen, maar later dose=0 afdwingen (zie last line)

    val trendConfirmed = (trend.state == TrendState.RISING_CONFIRMED)

        status.append(
            "PeakEstimate=${peak.state} " +
                "pred=${"%.2f".format(peak.predictedPeak)} " +
                "cat=$peakCategory " +
                "band=${peak.peakBand} " +
                "maxSlope=${"%.2f".format(peak.maxSlope)} " +
                "mom=${"%.2f".format(peak.momentum)}\n"
        )

        // ✅ Pre-peak commit window: helpt IOB eerder opbouwen vóór de top
        val prePeakCommitWindow =
            peak.state == PeakPredictionState.WATCHING &&
                peak.predictedPeak >= 15.0 &&
                ctx.consistency >= 0.55 &&
                ctx.iobRatio <= 0.50 &&
                ctx.acceleration >= 0.00    // niet tijdens afremmen

        status.append("PrePeakCommitWindow=${if (prePeakCommitWindow) "YES" else "NO"}\n")

        val peakIobBoost = when (peakCategory) {
            PeakCategory.EXTREME -> 1.55
            PeakCategory.HIGH    -> 1.40
            PeakCategory.MEAL    -> 1.25
            PeakCategory.MILD    -> 1.10
            PeakCategory.NONE    -> 1.00
        }

        val boostedIobRatio =
            (ctx.iobRatio / peakIobBoost).coerceAtLeast(0.0)

        val iobPower = if (input.isNight) 2.3 else 2.1
        val iobFactor = iobDampingFactor(
            iobRatio = boostedIobRatio,
            config = config,
            power = iobPower
        )

        val commitIobFactor = iobDampingFactor(
            iobRatio = ctx.iobRatio,
            config = config,
            power = config.commitIobPower   // NIEUW, milder
        )


        var finalDose =
                 (decidedDose * iobFactor * config.doseStrengthMul)
                 .coerceAtLeast(0.0)

        if (mealSignal.state == MealState.NONE && ctx.acceleration > 0.2 && ctx.iobRatio >= 0.75) {
            status.append("RISING IOB CAP → finalDose limited\n")
            finalDose = minOf(finalDose, 0.6 * config.maxSMB)
        }


        var accessLevel = computeDoseAccessLevel(ctx, zoneEnum)
        val effectiveMealLike =
            mealSignal.state != MealState.NONE || prePeakCommitWindow
        if (effectiveMealLike && accessLevel == DoseAccessLevel.BLOCKED && zoneEnum != BgZone.LOW) {
            status.append("ACCESS OVERRIDE: meal-like in-range → MICRO_ONLY\n")
            accessLevel = DoseAccessLevel.MICRO_ONLY
        }

        status.append("DoseAccess=$accessLevel\n")


// Universele caps als fractie van maxSMB
        val microCap = maxOf(0.05, config.microCapFracOfMaxSmb * config.maxSMB)
        val smallCap = maxOf(0.10, config.smallCapFracOfMaxSmb * config.maxSMB)

        val accessCap = when (accessLevel) {
            DoseAccessLevel.BLOCKED -> 0.0
            DoseAccessLevel.MICRO_ONLY -> microCap
            DoseAccessLevel.SMALL -> smallCap
            DoseAccessLevel.NORMAL -> Double.POSITIVE_INFINITY
        }

        when (accessLevel) {

            DoseAccessLevel.BLOCKED -> {
                status.append("ACCESS BLOCKED → finalDose=0\n")
                finalDose = 0.0
            }

            DoseAccessLevel.MICRO_ONLY -> {
                if (finalDose > microCap) {
                    status.append("ACCESS MICRO cap ${"%.2f".format(microCap)}\n")
                    finalDose = microCap
                }
            }

            DoseAccessLevel.SMALL -> {
                if (finalDose > smallCap) {
                    status.append("ACCESS SMALL cap ${"%.2f".format(smallCap)}\n")
                    finalDose = smallCap
                }
            }

            DoseAccessLevel.NORMAL -> {
                // geen beperking
            }
        }


// ─────────────────────────────────────────────
// 🚀 EARLY DOSE CONTROLLER (move earlier in pipeline)
// ─────────────────────────────────────────────

        val early = computeEarlyDoseDecision(
            ctx = ctx,
            mealSignal = mealSignal,
            peak = peak,
            trend = trend,
            trendConfirmedPersistent = trendConfirmed,
            bgZone = zoneEnum,
            now = now,
            config = config
        )

        status.append(early.reason + "\n")

// candidate stage (zodat micro-hold early niet per ongeluk blokkeert)
        val earlyStageCandidate = maxOf(earlyDose.stage, early.stageToFire)

// ── Micro-correction hold: niet drip-feeden als BG al daalt/vlak is ──
        if (shouldBlockMicroCorrections(
                ctx,
                mealSignal,
                peakCategory,
                earlyStageCandidate,
                peak,
                config
            )
        ) {
            status.append(
                "HoldCorrections: slope=${"%.2f".format(ctx.slope)} accel=${"%.2f".format(ctx.acceleration)} " +
                    "delta=${"%.2f".format(ctx.deltaToTarget)} → finalDose=0\n"
            )
            finalDose = 0.0
        }

        val postPeak = evaluatePostPeak(ctx, mealSignal, peak, now, config)
        status.append(postPeak.reason + "\n")

        val suppressForPeak = postPeak.suppress

// ─────────────────────────────────────────────
// 🍽️ MICRO RAMP (earlier IOB, no commit)
// ─────────────────────────────────────────────
        val microRamp = computeMicroRamp(ctx, config)
        status.append(microRamp.reason + "\n")

        if (
            microRamp.active &&
            !suppressForPeak &&
            !postPeak.lockout
        ) {
            // Respecteer ACCESS-cap (MICRO_ONLY/SMALL/NORMAL)
            val microCapped = minOf(microRamp.microU, accessCap)

            if (microCapped > 0.0 && finalDose < microCapped) {
                status.append(
                    "MICRO APPLY (${microRamp.tier}): ${"%.2f".format(finalDose)}→${"%.2f".format(microCapped)}U\n"
                )
                finalDose = microCapped
            }
        }


        // ─────────────────────────────────────────────
        // 🟡 PRE-MEAL RISE MICRO-FLOOR (laatste vangnet)
        // ─────────────────────────────────────────────
        val preMealFloor = preMealRiseFloorU(
            ctx = ctx,
            mealSignal = mealSignal,
            bgZone = zoneEnum,
            suppressForPeak = suppressForPeak,
            stagnationActive = (stagnationBoost > 0.0),
            accessLevel = accessLevel,
            maxBolus = config.maxSMB
        )

        if (preMealFloor > 0.0 && finalDose < preMealFloor) {
            status.append(
                "PRE-MEAL FLOOR: ${"%.2f".format(finalDose)}→${"%.2f".format(preMealFloor)}U\n"
            )
            finalDose = preMealFloor
        }

// ── Trajectory damping: continu remmen o.b.v. BG / IOB / slope / accel ──
        if (shouldHardBlockTrajectory(
                ctx,
                mealSignal,
                earlyStageCandidate,
                peak,
                now,
                config
            )
        ) {
            status.append("Trajectory HARD BLOCK → finalDose=0\n")
            finalDose = 0.0
        } else {
         //   val zoneEnum = computeBgZone(ctx)
            val trajFactor = trajectoryDampingFactor(ctx, mealSignal, zoneEnum, config)
            val before = finalDose
            finalDose *= trajFactor
            status.append(
                "TrajectoryFactor=${"%.2f".format(trajFactor)} " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }




        if (early.active && early.targetU > accessCap) {
            status.append("EARLY capped by ACCESS (${accessLevel})\n")
        }
 // ===============================================================================

// Apply early floor AFTER dampers (maar vóór cap/commit)
// ✅ NIET toepassen als we al aan het afremmen zijn (accel < 0)
        if (early.active && early.targetU > 0.0 && ctx.acceleration >= 0.0) {

            val cappedEarly = minOf(early.targetU, accessCap)

            if (cappedEarly < early.targetU) {
                status.append(
                    "EARLY capped by ACCESS ($accessLevel): " +
                        "${"%.2f".format(early.targetU)}→${"%.2f".format(cappedEarly)}U\n"
                )
            }

            val before = finalDose
            finalDose = maxOf(finalDose, cappedEarly)

            earlyDose.stage = maxOf(earlyDose.stage, early.stageToFire)
            earlyDose.lastFireAt = now
            earlyDose.lastConfidence = early.confidence

            status.append(
                "EARLY FLOOR: ${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        } else if (early.active && early.targetU > 0.0 && ctx.acceleration < 0.0) {
            status.append("EARLY FLOOR skipped (accel<0)\n")
        }

        // ─────────────────────────────────────────────
       // 🟦 PERSISTENT CORRECTION LOOP (dag + nacht)
       // ─────────────────────────────────────────────
        val baseMinDelta =
            if (ctx.input.isNight) 1.7 else 1.5

        val baseConfirmCycles = 2
        val pMul = config.persistentAggressionMul
        val effectiveMinDelta = (baseMinDelta / pMul).coerceIn(0.8, baseMinDelta)
        val effectiveConfirmCycles = (baseConfirmCycles / pMul).roundToInt().coerceAtLeast(1)

        val persistResult = persistCtrl.tickAndMaybeFire(
            tsMillis = now.millis,
            bgMmol = ctx.input.bgNow,
            targetMmol = ctx.input.targetBG,
            deltaToTarget = ctx.deltaToTarget,
            slope = ctx.slope,
            accel = ctx.acceleration,
            consistency = ctx.consistency,
            iob = ctx.input.currentIOB,
            iobRatio = ctx.iobRatio,

            maxBolusU = config.maxSMB,

            minDeltaToTarget = effectiveMinDelta,
            stableSlopeAbs = 0.25,
            stableAccelAbs = 0.06,
            minConsistency = 0.45,
            confirmCycles = effectiveConfirmCycles,

            minDoseU = 0.05,
            iobRatioHardStop = 0.45
        )



        if (persistResult.active) {
            status.append("PERSIST: ${persistResult.reason}\n")

            if (persistResult.fired) {
                // 🔑 Persistent neemt hier expliciet de leiding
                finalDose = persistResult.doseU
                status.append(
                    "PERSIST APPLY: finalDose=${"%.2f".format(finalDose)}U\n"
                )
            } else {
                // cooldown / hold → bewust niets doen
                finalDose = 0.0
                status.append("PERSIST HOLD: finalDose=0\n")
            }
        }

        // 🔒 markeer dat persistent de leiding heeft
        val persistentOverrideActive = persistResult.active

        if (persistentOverrideActive) {
            status.append(
                "PERSIST MODE: HARD (cooldown=${persistResult.cooldownLeft})\n"
            )
        }




        // ─────────────────────────────────────────────
       // ⚡ EARLY CONFIRM IMPULSE (bridges early → commit)
      // ─────────────────────────────────────────────
        val earlyConfirm =
            !earlyConfirmDone &&
                earlyDose.stage >= 2 &&                 // we hadden al een early boost
                allowsLargeActions(trend.state, trendConfirmed) &&     // <-- nieuw: harde gate
                ctx.slope >= 1.0 &&                     // duidelijke stijging
                ctx.acceleration >= 0.20 &&             // versnelling bevestigd
                ctx.deltaToTarget >= 2.0 &&             // echt boven target
                ctx.iobRatio <= 0.45 &&                 // nog ruimte
                ctx.consistency >= config.minConsistency &&
                peak.state != PeakPredictionState.CONFIRMED

        if (earlyConfirm) {
            val impulse =
                (0.6 * config.maxSMB)
                    .coerceAtLeast(0.3)
                    .coerceAtMost(config.maxSMB)

            val before = finalDose
            finalDose = maxOf(finalDose, impulse)

            earlyConfirmDone = true

            status.append(
                "EARLY-CONFIRM IMPULSE: slope=${"%.2f".format(ctx.slope)} " +
                    "accel=${"%.2f".format(ctx.acceleration)} → " +
                    "${"%.2f".format(before)}→${"%.2f".format(finalDose)}U\n"
            )
        }


        // ─────────────────────────────────────────────
        // 8️⃣ Absolute max SMB cap
        // ─────────────────────────────────────────────
        if (finalDose > config.maxSMB) {
            status.append(
                "Cap maxSMB ${"%.2f".format(finalDose)} → ${"%.2f".format(config.maxSMB)}U\n"
            )
            finalDose = config.maxSMB
        }



// ─────────────────────────────────────────────
// 9️⃣ Meal detectie & commit/observe + peak suppression + re-entry
// ─────────────────────────────────────────────

        val commitAllowed = canCommitNow(now, config)

// ─────────────────────────────────────────────
// 🧠 LEARNING: commit fraction (single source)
// ─────────────────────────────────────────────

        val commitFraction =
            if (mealSignal.state != MealState.NONE) {
                computeCommitFraction(
                    signal = mealSignal,
                    config = config
                )
            } else {
                0.0
            }


        var didCommitThisCycle = false

        status.append(mealSignal.reason + "\n")
        status.append("CommitAllowed=${if (commitAllowed) "YES" else "NO"}\n")

        var commandedDose =
            if (persistentOverrideActive) {
                // 🔒 persistent beslist volledig
                finalDose
            } else {
                finalDose
            }

        // ── Anti-drip: kleine correcties niet elke cyclus ──
        if (commandedDose > 0.0 &&
            commandedDose <= config.smallCorrectionMaxU &&
            mealSignal.state == MealState.NONE &&
            earlyDose.stage == 0   // ⬅️ EARLY DOSE UITZONDEREN
        ) {
            val minutesSinceSmall = minutesSince(lastSmallCorrectionAt, now)
            if (minutesSinceSmall < config.smallCorrectionCooldownMinutes) {
                status.append("SmallCorrectionCooldown: ${minutesSinceSmall}m < ${config.smallCorrectionCooldownMinutes}m → dose=0\n")
                commandedDose = 0.0
            } else {
                lastSmallCorrectionAt = now
            }
        }


// 9a) Peak/absorption suppression: stop of reduce rond/na piek

        if (suppressForPeak) {
            val reduced = (finalDose * config.absorptionDoseFactor).coerceAtLeast(0.0)
            commandedDose = reduced
            status.append("ABSORBING/PEAK: suppression → ${"%.2f".format(commandedDose)}U\n")
        }

// 9b) Re-entry: tweede gang (mag suppression overrulen als het écht weer stijgt)
        val reentry = isReentrySignal(ctx, now, config)
        if (reentry) {
            // nieuw segment binnen episode
            prePeakImpulseDone = false
            lastSegmentAt = now
            earlyDose = EarlyDoseContext()
            earlyConfirmDone = false

            status.append("SEGMENT: re-entry → new impulse window\n")
        }
// 🔒 POST-PEAK COMMIT BLOCK: nooit committen na curve-omkeer
        val postPeakCommitBlocked = postPeak.commitBlocked && !reentry
        if (postPeakCommitBlocked) status.append("POST-PEAK: commit blocked\n")

// 9c) Commit logic (alleen als we niet in peak-suppress zitten, OF als re-entry waar is)
        val allowCommitPath =
            ((!suppressForPeak) || reentry) &&
                !postPeakCommitBlocked



        val fastCarbOverride =
            config.enableFastCarbOverride &&
            peak.predictedPeak >= 12.0 &&
                ctx.slope >= 1.5 &&
                ctx.acceleration >= 0.25 &&
                ctx.consistency >= config.minConsistency


        val effectiveMeal =
            mealSignal.state != MealState.NONE || fastCarbOverride

        if (fastCarbOverride) {
            status.append("FAST-CARB override: effectiveMeal=TRUE\n")
        }


        if (downGate.locked) {
            status.append("DOWNTREND: commit skipped (LOCKED)\n")
        } else if (allowCommitPath && effectiveMeal) {

            // ✅ Commit boost mag óók via pre-peak window (timing fix)
            val allowCommitBoost =
                allowsLargeActions(trend.state, trendConfirmed) || prePeakCommitWindow

            if (!allowCommitBoost) {
                status.append("COMMIT gated (trend=${trend.state}, prePeakWindow=${prePeakCommitWindow})\n")
            } else if (prePeakCommitWindow && !trendConfirmed) {
                status.append("COMMIT boost via PRE-PEAK window (trend not persistent yet)\n")
            }


            val effectiveCommitAllowed =
                if (reentry) true else commitAllowed

            status.append(
                "EffectiveCommitAllowed=${if (effectiveCommitAllowed) "YES" else "NO"}\n"
            )

            if (effectiveCommitAllowed) {


                val zoneFactor = commitFractionZoneFactor(zoneEnum)
                val fraction =
                    (commitFraction * zoneFactor * config.maxCommitFractionMul)
                        .coerceIn(0.0, 1.0)

                status.append(
                    "CommitFraction: base=${"%.2f".format(commitFraction)} zoneFactor=${"%.2f".format(zoneFactor)} → ${"%.2f".format(fraction)}\n"
                )

                val commitAccessOk = (accessLevel == DoseAccessLevel.NORMAL)

                if (!commitAccessOk) {
                    status.append("COMMIT limited by ACCESS ($accessLevel)\n")
                }

                val prePeakMul = if (prePeakCommitWindow) 0.85 else 1.0

                val commitDose =
                    if (allowCommitBoost && commitAccessOk)
                        (config.maxSMB * fraction * commitIobFactor * prePeakMul * postPeak.commitFactor)
                            .coerceAtMost(config.maxSMB)
                    else 0.0





                val committedDose =
                    if (peakCategory >= PeakCategory.HIGH)
                        maxOf(finalDose, commitDose * 1.15)
                    else
                        maxOf(finalDose, commitDose)

                if (committedDose >= config.minCommitDose) {

                    commandedDose = committedDose

                    lastCommitAt = now
                    lastCommitDose = committedDose
                    lastCommitReason =
                        "${mealSignal.state} frac=${"%.2f".format(fraction)}"

                    didCommitThisCycle = true

                    if (reentry) {
                        lastReentryCommitAt = now
                        status.append("RE-ENTRY COMMIT set\n")
                    }

                    status.append(
                        "COMMIT ${"%.2f".format(committedDose)}U " +
                            "(${mealSignal.state}, conf=${"%.2f".format(mealSignal.confidence)})\n"
                    )

                } else {
                    status.append("COMMIT skipped (below minCommitDose)\n")
                }

            } else {
                status.append("OBSERVE (commit cooldown)\n")
            }
        }
// ─────────────────────────────────────────────
// 🟧 RESERVE POOL LOGIC
// 1) Reset / TTL
// 2) Controlled release
// 3) Capture (stash)
// ─────────────────────────────────────────────
// 0) TTL + commit reset (houdbaarheid)
        handleReserveResetAndTtl(
            didCommitThisCycle = didCommitThisCycle,
            now = now,
            status = status
        )



// 1) Release rule: als reserve bestaat en trend draait weer omhoog → geef gecontroleerd vrij
        if (reservedInsulinU > 0.0) {

            val risingAgain =
                ctx.recentDelta5m >= 0.06 || ctx.recentSlope >= 0.20

            val safeToRelease =
                ctx.acceleration >= 0.0 &&                 // niet vrijgeven tijdens afremmen
                    ctx.input.bgNow >= 4.8 &&              // simpele hypo-veiligheidsgrens
                    ctx.consistency >= config.minConsistency

            if (risingAgain && safeToRelease) {

                // per cycle: max "extra" reserve die we willen toestaan
                val perCycleCap = (config.maxSMB * RESERVE_RELEASE_CAP_FRAC).coerceAtLeast(0.05)

                // ✅ NIET stapelen bovenop een nieuwe commandedDose:
                // release alleen als er headroom is t.o.v. perCycleCap
                val headroom = (perCycleCap - commandedDose).coerceAtLeast(0.0)

                val releaseU = minOf(reservedInsulinU, headroom)

                if (releaseU > 0.0) {
                    val before = commandedDose
                    commandedDose += releaseU

                    reservedInsulinU -= releaseU
                    reserveActionThisCycle = "RELEASE"
                    reserveDeltaThisCycle -= releaseU

                    if (reservedInsulinU <= 1e-9) {
                        reservedInsulinU = 0.0
                        reserveAddedAt = null
                        reserveCause = null
                    }

                    status.append(
                        "RESERVE RELEASE: +${"%.2f".format(releaseU)}U " +
                            "cmd ${"%.2f".format(before)}→${"%.2f".format(commandedDose)}U " +
                            "remain=${"%.2f".format(reservedInsulinU)}U " +
                            "cause=${reserveCause}\n"
                    )
                } else {
                    status.append(
                        "RESERVE RELEASE SKIP: no headroom " +
                            "(cmd=${"%.2f".format(commandedDose)} cap=${"%.2f".format(perCycleCap)} " +
                            "remain=${"%.2f".format(reservedInsulinU)}U cause=${reserveCause})\n"
                    )
                }

            } else {
                status.append(
                    "RESERVE HOLD: remain=${"%.2f".format(reservedInsulinU)}U " +
                        "(recentΔ5m=${"%.2f".format(ctx.recentDelta5m)} " +
                        "recentSlope=${"%.2f".format(ctx.recentSlope)} " +
                        "accel=${"%.2f".format(ctx.acceleration)} " +
                        "cause=${reserveCause})\n"
                )
            }

        }


// 2) Capture rule: als we nu willen doseren, maar top/dip-condities → stash in reserve
        if (commandedDose > 0.0) {

            // Alleen relevant in “meal-like” situaties (waar jij dit vooral wil)
            val mealLike =
                mealSignal.state != MealState.NONE ||
                    prePeakCommitWindow ||
                    earlyDose.stage > 0

            // Niet stashen als we in harde stijging zitten
            val strongRisingNow =
                ctx.recentDelta5m >= 0.10 || ctx.recentSlope >= 0.45

            // A) klassieke false-dip / korte terugval
            val shortTermDip =
                (ctx.recentDelta5m <= -0.06 || ctx.recentSlope <= -0.20) &&
                    ctx.acceleration <= 0.0 &&
                    ctx.consistency >= config.minConsistency

            // 🟠 NIEUW: topvorming zonder dip (zoals 09:42 / 09:48)
            val peakTopForming =
                peak.state == PeakPredictionState.WATCHING &&
                    ctx.acceleration <= -0.02 &&                 // afremmen begint
                    ctx.iobRatio >= 0.35 &&                       // al insuline aan boord
                    predictedPeak >= ctx.input.bgNow + 0.6 &&    // piek ligt nog boven ons
                    ctx.consistency >= config.minConsistency


            // B) ✅ TOPVORMING / "net over de top" situatie (jouw issue rond 09:42 / 09:48):
            // - relatief hoge IOB
            // - accel zwakt af (maar je bent nog niet per se dalend)
            // - nog boven target
            val topForming =
                ctx.iobRatio >= 0.60 &&
                    ctx.acceleration <= 0.10 &&
                    ctx.deltaToTarget >= 1.2 &&
                    ctx.consistency >= config.minConsistency &&
                    ctx.recentSlope <= 0.35          // ✅ short-term stijging moet al “weg” zijn


            val postPeakNoStash = postPeak.noStash
            if (postPeakNoStash) {
                status.append("POSTPEAK → NO-STASH window\n")
            }



            // stash-conditie: dip OF topvorming, maar niet tijdens sterke hernieuwde stijging
            val shouldStash =
                !postPeakNoStash &&
                    mealLike &&
                    (shortTermDip || peakTopForming || topForming) &&
                    !strongRisingNow


            if (shouldStash) {

                reservedInsulinU += commandedDose
                reserveAddedAt = reserveAddedAt ?: now
                reserveActionThisCycle = "STASH"
                reserveDeltaThisCycle += commandedDose

                reserveCause =
                    when {
                        topForming || peakTopForming -> ReserveCause.POST_PEAK_TOP
                        else -> ReserveCause.SHORT_TERM_DIP
                    }

                status.append(
                    "RESERVE STASH (${reserveCause}): " +
                        "${"%.2f".format(commandedDose)}U → " +
                        "reserved=${"%.2f".format(reservedInsulinU)}U\n"
                )

                commandedDose = 0.0
            }

        }

// ─────────────────────────────────────────────
// 🟠 PRE-COMMIT RESERVE SPLIT (early overshoot protection)
// ─────────────────────────────────────────────
        run {
            val preReserve = computePreReserveSplit(
                ctx = ctx,
                mealSignal = mealSignal,
                commandedDose = commandedDose,
                config = config,
                bgZone = zoneEnum
            )

            if (preReserve.active && preReserve.stash > 0.0) {

                status.append(preReserve.reason + "\n")

                // stash deel
                reservedInsulinU += preReserve.stash
                reserveAddedAt = reserveAddedAt ?: now
                reserveCause = ReserveCause.PRE_UNCERTAIN_MEAL
                reserveActionThisCycle = "PRE-STASH"
                reserveDeltaThisCycle += preReserve.stash

                // reduceer wat we NU gaan leveren
                commandedDose = preReserve.deliverNow
            }
        }




// ─────────────────────────────────────────────
// HARD SAFETY BLOCKS (final gate before delivery)
// ─────────────────────────────────────────────
        val hypoBlock = hypoGuardBlock(pred60)
        if (hypoBlock.active) {
            status.append(hypoBlock.reason + " → commandedDose=0\n")
            commandedDose = 0.0
        }

        if (postPeak.lockout) {
            status.append("POSTPEAK LOCKOUT → commandedDose=0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }



        // ─────────────────────────────────────────────
        // 🧯 DOWN-TREND FINAL DOSE GATE (last line of defense)
        // ─────────────────────────────────────────────
        if (downGate.locked) {
            status.append("DOWNTREND LOCKED: commandedDose forced to 0\n")
            commandedDose = 0.0
            earlyConfirmDone = false
        }




        // ─────────────────────────────────────────────
        // 🔟 Execution: SMB / hybride bolus + basaal
        // ─────────────────────────────────────────────
        val execution = executeDelivery(
            dose = commandedDose,
            hybridPercentage = config.hybridPercentage,
            cycleMinutes = config.deliveryCycleMinutes,
            maxTempBasalRate = config.maxTempBasalRate,
            smallDoseThreshold = config.smallDoseThresholdU
        )
        val deliveredNow = execution.deliveredTotal
        val minDeliveryU = config.minDeliverDose

        // ✅ NEW: als het onder de zichtbare/werkelijke delivery drempel is, behandel als 0
        val effectiveDeliveredNow =
            if (deliveredNow >= minDeliveryU) deliveredNow else 0.0

        val effectiveBolus =
            if (deliveredNow >= minDeliveryU) execution.bolus else 0.0

        val effectiveBasalRate =
            if (deliveredNow >= minDeliveryU) execution.basalRate else 0.0

       // ✅ Alleen echte, zichtbare afleveringen loggen
        if (effectiveDeliveredNow >= minDeliveryU) {
            deliveryHistory.addFirst(DateTime.now() to effectiveDeliveredNow)
            while (deliveryHistory.size > MAX_DELIVERY_HISTORY) {
                deliveryHistory.removeLast()
            }
        }




        status.append(
            "DELIVERY: dose=${"%.2f".format(commandedDose)}U " +
                "basal=${"%.2f".format(effectiveBasalRate)}U/h " +
                "bolus=${"%.2f".format(effectiveBolus)}U " +
                "(${config.deliveryCycleMinutes}m)\n"
        )

        // minimaal 0.08U per cycle voordat we "deliver" zeggen
    //    val minDeliveryU = config.minDeliverDose

        val shouldDeliver =
            if (persistentOverrideActive) {
                persistResult.fired && effectiveDeliveredNow >= minDeliveryU
            } else {
                effectiveDeliveredNow >= minDeliveryU
            }

        val rescueSignal = updateRescueDetection(
            ctx = ctx,
            now = now,
            config = config,
            deliveredThisCycle = effectiveDeliveredNow,
            pred60 = pred60
        )


        status.append("RESCUE: state=${rescueSignal.state} conf=${"%.2f".format(rescueSignal.confidence)} pred60=${"%.2f".format(rescueSignal.pred60)}\n")
        if (rescueSignal.armed || rescueSignal.confirmed) {
            status.append("RESCUE: ${rescueSignal.reason}\n")
        }



        val minutesSinceCommit =
            if (lastCommitAt != null)
                org.joda.time.Minutes.minutesBetween(lastCommitAt, now).minutes
            else
                -1


        // ─────────────────────────────────────────────
       // Parameter snapshot logging (laagfrequent)
       // ─────────────────────────────────────────────

        profileParamLogger.maybeLog()


        // ─────────────────────────────────────────────
        // CSV logging (analyse / tuning)
        // ─────────────────────────────────────────────

        logRow.effectiveISF = input.effectiveISF
        logRow.gain = config.gain
        logRow.energyBase = energy
        logRow.energyTotal = energyTotal



        logRow.stagnationActive = stagnationBoost > 0.0
        logRow.stagnationBoost = stagnationBoost
        logRow.stagnationAccel = ctx.acceleration
        logRow.stagnationAccelLimit = config.stagnationAccelMaxAbs

        logRow.rawDose = rawDose
        logRow.iobFactor = iobFactor
        logRow.normalDose = finalDose

        logRow.earlyStage = earlyDose.stage
        logRow.earlyConfidence = earlyDose.lastConfidence
        logRow.earlyTargetU = early.targetU

        logRow.mealState = mealSignal.state.name
        logRow.commitFraction = commitFraction

        logRow.minutesSinceCommit = minutesSinceCommit

        logRow.peakState = peakState.name
        logRow.predictedPeak = predictedPeak
        logRow.peakIobBoost = peakIobBoost
        logRow.effectiveIobRatio = boostedIobRatio
        logRow.peakBand = peak.peakBand
        logRow.peakMaxSlope = peak.maxSlope
        logRow.peakMomentum = peak.momentum
        logRow.peakRiseSinceStart = peak.riseSinceStart
        logRow.peakEpisodeActive = peakEstimator.active

        logRow.suppressForPeak = suppressForPeak
        logRow.absorptionActive = isInAbsorptionWindow(now, config)
        logRow.reentrySignal = reentry
        logRow.decisionReason = decision.reason


        logRow.pred60 = rescueSignal.pred60
        logRow.rescueState = rescueSignal.state.name
        logRow.rescueConfidence = rescueSignal.confidence
        logRow.rescueReason = rescueSignal.reason

        logRow.finalDose = finalDose
        logRow.commandedDose = commandedDose
        logRow.deliveredTotal = effectiveDeliveredNow
        logRow.bolus = effectiveBolus
        logRow.basalRate = effectiveBasalRate

        logRow.reserveU = reservedInsulinU
        logRow.reserveAction = reserveActionThisCycle
        logRow.reserveDeltaU = reserveDeltaThisCycle
        logRow.reserveAgeMin =
            if (reserveAddedAt != null)
                org.joda.time.Minutes.minutesBetween(reserveAddedAt, now).minutes
            else -1

        logRow.shouldDeliver = shouldDeliver

        FCLvNextCsvLogger.log(logRow)


        // ─────────────────────────────────────────────
        // RETURN
        // ─────────────────────────────────────────────
        return FCLvNextAdvice(
            bolusAmount = effectiveBolus,
            basalRate = effectiveBasalRate,
            shouldDeliver = shouldDeliver,
            effectiveISF = input.effectiveISF,
            targetAdjustment = 0.0,
            statusText = status.toString()
        )
    }


}


