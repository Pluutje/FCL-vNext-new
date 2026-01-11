package app.aaps.plugins.aps.openAPSFCL

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LongSparseArray
import androidx.core.util.forEach
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.preference.Preference
import app.aaps.core.data.aps.SMBDefaults
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.OapsProfileFCL
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.Constraint
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profiling.Profiler
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.IntentKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.UnitDoubleKey
// import app.aaps.core.objects.aps.DetermineBasalResult
//import app.aaps.implementation.aps.DetermineBasalResult
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.convertedToAbsolute
import app.aaps.core.objects.extensions.getPassedDurationToTimeInMinutes
import app.aaps.core.objects.extensions.plannedRemainingMinutes
import app.aaps.core.objects.extensions.put
import app.aaps.core.objects.extensions.store
import app.aaps.core.objects.extensions.target
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import app.aaps.core.validators.preferences.AdaptiveIntentPreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveTimePreference
import app.aaps.core.validators.preferences.AdaptiveCsvMultiSelectPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.core.validators.preferences.AdaptiveUnitPreference
import app.aaps.plugins.aps.OpenAPSFragment
import app.aaps.plugins.aps.R

import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import app.aaps.plugins.aps.events.EventResetOpenAPSGui
import app.aaps.plugins.aps.openAPS.TddStatus
import dagger.android.HasAndroidInjector
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.floor
import kotlin.math.ln

@Singleton
open class OpenAPSFCLPlugin @Inject constructor(
    private val injector: HasAndroidInjector,
    aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    private val constraintsChecker: ConstraintsChecker,
    rh: ResourceHelper,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val config: Config,
    private val activePlugin: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val hardLimits: HardLimits,
    private val preferences: Preferences,
    protected val dateUtil: DateUtil,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val persistenceLayer: PersistenceLayer,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val glucoseStatusCalculatorFCL: GlucoseStatusCalculatorFCL,
    private val tddCalculator: TddCalculator,
    private val bgQualityCheck: BgQualityCheck,
    private val uiInteraction: UiInteraction,
    private val determineBasalFCL: DetermineBasalFCL,
    private val profiler: Profiler,
    private val apsResultProvider: Provider<APSResult>
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass(OpenAPSFragment::class.java.name)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.openaps_fcl)
        .shortName(app.aaps.core.ui.R.string.fcl_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .preferencesVisibleInSimpleMode(false)
        .showInList(showInList = { config.APS })
        .description(R.string.description_smb)
        .setDefault(),
    aapsLogger, rh
), APS, PluginConstraints {



    // last values
    override var lastAPSRun: Long = 0
    override val algorithm = APSResult.Algorithm.SMB
   // override var lastAPSResult: DetermineBasalResult? = null
    override var lastAPSResult: APSResult? = null
//    override fun supportsDynamicIsf(): Boolean = preferences.get(BooleanKey.ApsUseDynamicSensitivity)


    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorFCL.getGlucoseStatusData(allowOldData)


    override fun specialEnableCondition(): Boolean {
        return try {
            activePlugin.activePump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            // may fail during initialization
            true
        }
    }

    override fun specialShowInListCondition(): Boolean {
        try {
            val pump = activePlugin.activePump
            return pump.pumpDescription.isTempBasalCapable
        } catch (_: Exception) {
            return true
        }
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val smbEnabled = true //preferences.get(BooleanKey.ApsUseSmb)
        val smbAlwaysEnabled = true // preferences.get(BooleanKey.ApsUseSmbAlways)
        val uamEnabled = true //preferences.get(BooleanKey.ApsUseUam)
        val advancedFiltering = activePlugin.activeBgSource.advancedFilteringSupported()

        val autoSensOrDynIsfSensEnabled = false

        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAlways.key)?.isVisible = smbEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithCob.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbWithLowTt.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering || smbEnabled && !advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsUseSmbAfterCarbs.key)?.isVisible = smbEnabled && !smbAlwaysEnabled && advancedFiltering
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsResistanceLowersTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<SwitchPreference>(BooleanKey.ApsSensitivityRaisesTarget.key)?.isVisible = autoSensOrDynIsfSensEnabled
        preferenceFragment.findPreference<AdaptiveIntPreference>(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb.key)?.isVisible = smbEnabled && uamEnabled
    }



    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "invoke from $initiator tempBasalFallback: $tempBasalFallback")
        lastAPSResult = null
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val pump = activePlugin.activePump
        if (profile == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(app.aaps.core.ui.R.string.no_profile_set)))
            aapsLogger.debug(LTag.APS, rh.gs(app.aaps.core.ui.R.string.no_profile_set))
            return
        }
        if (!isEnabled()) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_disabled)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_disabled))
            return
        }
        if (glucoseStatus == null) {
            rxBus.send(EventResetOpenAPSGui(rh.gs(R.string.openapsma_no_glucose_data)))
            aapsLogger.debug(LTag.APS, rh.gs(R.string.openapsma_no_glucose_data))
            return
        }

        val inputConstraints = ConstraintObject(0.0, aapsLogger) // fake. only for collecting all results

        if (!hardLimits.checkHardLimits(profile.dia, app.aaps.core.ui.R.string.profile_dia, hardLimits.minDia(), hardLimits.maxDia())) return
        if (!hardLimits.checkHardLimits(
                profile.getIcTimeFromMidnight(MidnightUtils.secondsFromMidnight()),
                app.aaps.core.ui.R.string.profile_carbs_ratio_value,
                hardLimits.minIC(),
                hardLimits.maxIC()
            )
        ) return
        if (!hardLimits.checkHardLimits(profile.getIsfMgdl("OpenAPSFCLPlugin"), app.aaps.core.ui.R.string.profile_sensitivity_value, HardLimits.MIN_ISF, HardLimits.MAX_ISF)) return
        if (!hardLimits.checkHardLimits(profile.getMaxDailyBasal(), app.aaps.core.ui.R.string.profile_max_daily_basal_value, 0.02, hardLimits.maxBasal())) return
        if (!hardLimits.checkHardLimits(pump.baseBasalRate, app.aaps.core.ui.R.string.current_basal_value, 0.01, hardLimits.maxBasal())) return

        // End of check, start gathering data

        // val dynIsfMode = preferences.get(BooleanKey.ApsUseDynamicSensitivity) && hardLimits.checkHardLimits(preferences.get(IntKey.ApsDynIsfAdjustmentFactor).toDouble(), R.string.dyn_isf_adjust_title, IntKey.ApsDynIsfAdjustmentFactor.min.toDouble(), IntKey.ApsDynIsfAdjustmentFactor.max.toDouble())
        val dynIsfMode = false
        val smbEnabled = preferences.get(BooleanKey.ApsUseSmb)
        val advancedFiltering = constraintsChecker.isAdvancedFilteringEnabled().also { inputConstraints.copyReasons(it) }.value()

        val now = dateUtil.now()
        val tb = processedTbrEbData.getTempBasalIncludingConvertedExtended(now)
        val currentTemp = CurrentTemp(
            duration = tb?.plannedRemainingMinutes ?: 0,
            rate = tb?.convertedToAbsolute(now, profile) ?: 0.0,
            minutesrunning = tb?.getPassedDurationToTimeInMinutes(now)
        )
        var minBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetLowMgdl(), 0.1), app.aaps.core.ui.R.string.profile_low_target, HardLimits.LIMIT_MIN_BG[0], HardLimits.LIMIT_MIN_BG[1])
        var maxBg = hardLimits.verifyHardLimits(Round.roundTo(profile.getTargetHighMgdl(), 0.1), app.aaps.core.ui.R.string.profile_high_target, HardLimits.LIMIT_MAX_BG[0], HardLimits.LIMIT_MAX_BG[1])
        var targetBg = hardLimits.verifyHardLimits(profile.getTargetMgdl(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TARGET_BG[0], HardLimits.LIMIT_TARGET_BG[1])
        var isTempTarget = false
        persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())?.let { tempTarget ->
            isTempTarget = true
            minBg = hardLimits.verifyHardLimits(tempTarget.lowTarget, app.aaps.core.ui.R.string.temp_target_low_target, HardLimits.LIMIT_TEMP_MIN_BG[0], HardLimits.LIMIT_TEMP_MIN_BG[1])
            maxBg = hardLimits.verifyHardLimits(tempTarget.highTarget, app.aaps.core.ui.R.string.temp_target_high_target, HardLimits.LIMIT_TEMP_MAX_BG[0], HardLimits.LIMIT_TEMP_MAX_BG[1])
            targetBg = hardLimits.verifyHardLimits(tempTarget.target(), app.aaps.core.ui.R.string.temp_target_value, HardLimits.LIMIT_TEMP_TARGET_BG[0], HardLimits.LIMIT_TEMP_TARGET_BG[1])
        }

        var autosensResult = AutosensResult()


        @Suppress("KotlinConstantConditions")
        val iobArray = iobCobCalculator.calculateIobArrayForSMB(autosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget)
        val mealData = iobCobCalculator.getMealDataWithWaitingForCalculationFinish()

        @Suppress("KotlinConstantConditions")
        val oapsProfile = OapsProfileFCL(
            dia = 0.0, // not used
            min_5m_carbimpact = 0.0, // not used
            max_iob = preferences.get(DoubleKey.fcl_vnext_MaxIOB), //constraintsChecker.getMaxIOBAllowed().also { inputConstraints.copyReasons(it) }.value(),
            max_daily_basal = profile.getMaxDailyBasal(),
            max_basal = 25.0, //constraintsChecker.getMaxBasalAllowed(profile).also { inputConstraints.copyReasons(it) }.value(),
            min_bg = minBg,
            max_bg = maxBg,
            target_bg = targetBg,
            carb_ratio = profile.getIc(),
            sens = profile.getIsfMgdl("OpenAPSFCLPlugin"),
            autosens_adjust_targets = false, // not used
            max_daily_safety_multiplier = 500.0, //preferences.get(DoubleKey.ApsMaxDailyMultiplier),
            current_basal_safety_multiplier = 500.0, // preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier),
            lgsThreshold = profileUtil.convertToMgdlDetect(preferences.get(UnitDoubleKey.ApsLgsThreshold)).toInt(),
            high_temptarget_raises_sensitivity = false,
            low_temptarget_lowers_sensitivity = false,
            sensitivity_raises_target = false, //preferences.get(BooleanKey.ApsSensitivityRaisesTarget),
            resistance_lowers_target = false, //preferences.get(BooleanKey.ApsResistanceLowersTarget),
            adv_target_adjustments = SMBDefaults.adv_target_adjustments,
            exercise_mode = SMBDefaults.exercise_mode,
            half_basal_exercise_target = SMBDefaults.half_basal_exercise_target,
            maxCOB = SMBDefaults.maxCOB,
            skip_neutral_temps = pump.setNeutralTempAtFullHour(),
            remainingCarbsCap = SMBDefaults.remainingCarbsCap,
            enableUAM = constraintsChecker.isUAMEnabled().also { inputConstraints.copyReasons(it) }.value(),
            A52_risk_enable = SMBDefaults.A52_risk_enable,
            SMBInterval = 3, //preferences.get(IntKey.ApsMaxSmbFrequency),
            enableSMB_with_COB = true, // smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithCob),
            enableSMB_with_temptarget = true, //smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithLowTt),
            allowSMB_with_high_temptarget = false, //smbEnabled && preferences.get(BooleanKey.ApsUseSmbWithHighTt),
            enableSMB_always = true, //smbEnabled && preferences.get(BooleanKey.ApsUseSmbAlways) && advancedFiltering,
            enableSMB_after_carbs = true, //smbEnabled && preferences.get(BooleanKey.ApsUseSmbAfterCarbs) && advancedFiltering,
            maxSMBBasalMinutes = 240, //preferences.get(IntKey.ApsMaxMinutesOfBasalToLimitSmb),
            maxUAMSMBBasalMinutes = 240, //preferences.get(IntKey.ApsUamMaxMinutesOfBasalToLimitSmb),
            bolus_increment = pump.pumpDescription.bolusStep,
            carbsReqThreshold = 20, //preferences.get(IntKey.ApsCarbsRequestThreshold),
            current_basal = activePlugin.activePump.baseBasalRate,
            temptargetSet = isTempTarget,
            autosens_max = preferences.get(DoubleKey.AutosensMax),
            out_units = if (profileFunction.getUnits() == GlucoseUnit.MMOL) "mmol/L" else "mg/dl",
            variable_sens = 0.0,
            insulinDivisor = 0,
            TDD = 0.0

        )
        val microBolusAllowed = true //constraintsChecker.isSMBModeEnabled(ConstraintObject(tempBasalFallback.not(), aapsLogger)).also { inputConstraints.copyReasons(it) }.value()
        val flatBGsDetected = bgQualityCheck.state == BgQualityCheck.State.FLAT

        aapsLogger.debug(LTag.APS, ">>> Invoking determine_basal SMB <<<")
        aapsLogger.debug(LTag.APS, "Glucose status:     $glucoseStatus")
        aapsLogger.debug(LTag.APS, "Current temp:       $currentTemp")
        aapsLogger.debug(LTag.APS, "IOB data:           ${iobArray.joinToString()}")
        aapsLogger.debug(LTag.APS, "Profile:            $oapsProfile")
        aapsLogger.debug(LTag.APS, "Autosens data:      $autosensResult")
        aapsLogger.debug(LTag.APS, "Meal data:          $mealData")
        aapsLogger.debug(LTag.APS, "MicroBolusAllowed:  $microBolusAllowed")
        aapsLogger.debug(LTag.APS, "flatBGsDetected:    $flatBGsDetected")
        aapsLogger.debug(LTag.APS, "DynIsfMode:         $dynIsfMode")

        determineBasalFCL.determine_basal(
            glucose_status = glucoseStatus,
            currenttemp = currentTemp,
            iob_data_array = iobArray,
            profile = oapsProfile,
            autosens_data = autosensResult,
            meal_data = mealData,
            microBolusAllowed = microBolusAllowed,
            currentTime = now,
            flatBGsDetected = flatBGsDetected,
            dynIsfMode = false
        ).also { rt ->

            val determineBasalResult = apsResultProvider.get().with(rt)

            // Preserve input data
            determineBasalResult.inputConstraints = inputConstraints
            determineBasalResult.iobData = iobArray
            determineBasalResult.glucoseStatus = glucoseStatus
            determineBasalResult.currentTemp = currentTemp
            determineBasalResult.oapsProfileFCL = oapsProfile
            determineBasalResult.mealData = mealData

            // ✅ 1) Zet variableSens EXACT 1x met fallback
            val usedIsfMgdl = rt.variable_sens?.takeIf { it > 0.0 } ?: oapsProfile.sens
            determineBasalResult.variableSens = usedIsfMgdl



            // ✅ 2) Dummy autosens voor consistentie (APSResult-niveau)
            determineBasalResult.autosensResult = AutosensResult(
                ratio = 1.0,
                ratioFromTdd = 1.0,
                ratioFromCarbs = 1.0,
                sensResult = "FCLvNext"
            )

            lastAPSResult = determineBasalResult
            lastAPSRun = now

            aapsLogger.debug(LTag.APS, "FCL variableSens mg/dl: $usedIsfMgdl | profile sens mg/dl: ${oapsProfile.sens}")
            rxBus.send(EventAPSCalculationFinished())
        }

        rxBus.send(EventOpenAPSUpdateGui())
    }

    override fun isSuperBolusEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        value.set(false)
        return value
    }

    override fun applyMaxIOBConstraints(maxIob: Constraint<Double>): Constraint<Double> {
        if (isEnabled()) {
            val maxIobPref = preferences.get(DoubleKey.ApsSmbMaxIob)
            maxIob.setIfSmaller(maxIobPref, rh.gs(R.string.limiting_iob, maxIobPref, rh.gs(R.string.maxvalueinpreferences)), this)
            maxIob.setIfSmaller(hardLimits.maxIobSMB(), rh.gs(R.string.limiting_iob, hardLimits.maxIobSMB(), rh.gs(R.string.hardlimit)), this)
        }
        return maxIob
    }

    override fun applyBasalConstraints(absoluteRate: Constraint<Double>, profile: Profile): Constraint<Double> {
        if (isEnabled()) {
            var maxBasal = 25.0 //preferences.get(DoubleKey.ApsMaxBasal)
            if (maxBasal < profile.getMaxDailyBasal()) {
                maxBasal = profile.getMaxDailyBasal()
                absoluteRate.addReason(rh.gs(R.string.increasing_max_basal), this)
            }
            absoluteRate.setIfSmaller(maxBasal, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxBasal, rh.gs(R.string.maxvalueinpreferences)), this)

            // Check percentRate but absolute rate too, because we know real current basal in pump
            val maxBasalMultiplier = 500.0 // preferences.get(DoubleKey.ApsMaxCurrentBasalMultiplier)
            val maxFromBasalMultiplier = floor(maxBasalMultiplier * profile.getBasal() * 100) / 100
            absoluteRate.setIfSmaller(
                maxFromBasalMultiplier,
                rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromBasalMultiplier, rh.gs(R.string.max_basal_multiplier)),
                this
            )
            val maxBasalFromDaily = 500.0 //preferences.get(DoubleKey.ApsMaxDailyMultiplier)
            val maxFromDaily = floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100
            absoluteRate.setIfSmaller(maxFromDaily, rh.gs(app.aaps.core.ui.R.string.limitingbasalratio, maxFromDaily, rh.gs(R.string.max_daily_basal_multiplier)), this)
        }
        return absoluteRate
    }

    override fun isSMBModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = true //preferences.get(BooleanKey.ApsUseSmb)
        if (!enabled) value.set(false, rh.gs(R.string.smb_disabled_in_preferences), this)
        return value
    }

    override fun isUAMEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        val enabled = true // preferences.get(BooleanKey.ApsUseUam)
        if (!enabled) value.set(false, rh.gs(R.string.uam_disabled_in_preferences), this)
        return value
    }

    override fun isAutosensModeEnabled(value: Constraint<Boolean>): Constraint<Boolean> {
        if (preferences.get(BooleanKey.ApsUseDynamicSensitivity)) {
            // DynISF mode
            if (!preferences.get(BooleanKey.ApsDynIsfAdjustSensitivity))
                value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        } else {
            // SMB mode
            // val enabled = preferences.get(BooleanKey.ApsUseAutosens)
            val enabled = false
            if (!enabled) value.set(false, rh.gs(R.string.autosens_disabled_in_preferences), this)
        }
        return value
    }

    override fun configuration(): JSONObject =
        JSONObject()
            .put(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .put(IntKey.ApsDynIsfAdjustmentFactor, preferences)

    override fun applyConfiguration(configuration: JSONObject) {
        configuration
            .store(BooleanKey.ApsUseDynamicSensitivity, preferences)
            .store(IntKey.ApsDynIsfAdjustmentFactor, preferences)
    }

    override fun addPreferenceScreen(
        preferenceManager: PreferenceManager,
        parent: PreferenceScreen,
        context: Context,
        requiredKey: String?
    ) {

        // =================================================
        // INTRO
        // =================================================
        parent.addPreference(
            Preference(context).apply {
                key = "FCLvNextIntro"
                isSelectable = false
                summary = context.getString(R.string.fcl_vnext_intro)
            }
        )
        parent.addPreference(
            Preference(context).apply {
                key = "FCLvNextIntroText"
                isSelectable = false
                summary = context.getString(R.string.fcl_vnext_intro_text1)
            }
        )
        parent.addPreference(
            Preference(context).apply {
                key = "FCLvNextIntroText"
                isSelectable = false
                summary = context.getString(R.string.fcl_vnext_intro_text2)
            }
        )

        parent.addPreference(
            AdaptiveIntentPreference(
                ctx = context,
                intentKey = IntentKey.ApsLinkToDocs,
                intent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(context.getString(R.string.fcl_vnext_0_FCLvNext_algemeen_url))
                },
                title = R.string.fcl_vnext_0_FCLvNext_algemeen_title,
                summary = R.string.fcl_vnext_0_FCLvNext_algemeen_summary
            )
        )

        // =================================================
        // 1️⃣ ALGEMEEN GEDRAG
        // =================================================
        val GENERAL = preferenceManager.createPreferenceScreen(context).apply {
            key = "FCLvNextGeneral"
            title = "⚙️ Algemeen gedrag"
            initialExpandedChildrenCount = Int.MAX_VALUE

            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(context.getString(R.string.fcl_vnext_1_ALGEMEEN_GEDRAG_url))
                    },
                    title = R.string.fcl_vnext_1_ALGEMEEN_GEDRAG_title,
                    summary = R.string.fcl_vnext_1_ALGEMEEN_GEDRAG_summary
                )
            )

            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.fcl_vnext_gain_day,
                    dialogMessage = R.string.fcl_vnext_gain_day_summary,
                    title = R.string.fcl_vnext_gain_day_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.max_bolus_day,
                    dialogMessage = R.string.max_bolus_day_summary,
                    title = R.string.max_bolus_day_title
                )
            )

            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.fcl_vnext_gain_night,
                    dialogMessage = R.string.fcl_vnext_gain_night_summary,
                    title = R.string.fcl_vnext_gain_night_title
                )
            )

            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.max_bolus_night,
                    dialogMessage = R.string.max_bolus_night_summary,
                    title = R.string.max_bolus_night_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context,
                    doubleKey = DoubleKey.fcl_vnext_MaxIOB,
                    dialogMessage = R.string.fcl_vnext_MaxIOB_summary,
                    title = R.string.fcl_vnext_MaxIOB_title
                )
            )


        }

        // =================================================
        // 2️⃣ PROFIEL
        // =================================================
        val PROFILES = preferenceManager.createPreferenceScreen(context).apply {
            key = "FCLvNextProfiles"
            title = "🧬 Profiel"
            initialExpandedChildrenCount = Int.MAX_VALUE

            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(context.getString(R.string.fcl_vnext_2_PROFIEL_url))
                    },
                    title = R.string.fcl_vnext_1_PROFIEL_title,
                    summary = R.string.fcl_vnext_1_PROFIEL_summary
                )
            )

            // Profielkeuze
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = StringKey.fcl_vnext_profile,
                    title = R.string.fcl_vnext_profile_title,
                    summary = R.string.fcl_vnext_profile_summary,
                    entries = context.resources
                        .getStringArray(R.array.fcl_vnext_profile_entries)
                        .map { it as CharSequence }
                        .toTypedArray(),
                    entryValues = context.resources
                        .getStringArray(R.array.fcl_vnext_profile_values)
                        .map { it as CharSequence }
                        .toTypedArray()
                )
            )

            // Meal detect speed (timing)
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = StringKey.fcl_vnext_meal_detect_speed,
                    title = R.string.fcl_vnext_meal_detect_speed_title,
                    summary = R.string.fcl_vnext_meal_detect_speed_summary,
                    entries = context.resources
                        .getStringArray(R.array.fcl_vnext_meal_detect_speed_entries)
                        .map { it as CharSequence }
                        .toTypedArray(),
                    entryValues = context.resources
                        .getStringArray(R.array.fcl_vnext_meal_detect_speed_values)
                        .map { it as CharSequence }
                        .toTypedArray()
                )
            )
            // Correction style (post-meal / persistent behavior)
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = StringKey.fcl_vnext_correction_style,
                    title = R.string.fcl_vnext_correction_style_title,
                    summary = R.string.fcl_vnext_correction_style_summary,
                    entries = context.resources
                        .getStringArray(R.array.fcl_vnext_correction_style_entries)
                        .map { it as CharSequence }
                        .toTypedArray(),
                    entryValues = context.resources
                        .getStringArray(R.array.fcl_vnext_correction_style_values)
                        .map { it as CharSequence }
                        .toTypedArray()
                )
            )
            // Insulin distribution
            addPreference(
                AdaptiveListPreference(
                    ctx = context,
                    stringKey = StringKey.fcl_vnext_dose_distribution_style,
                    title = R.string.fcl_vnext_dose_distribution_title,
                    summary = R.string.fcl_vnext_dose_distribution_summary,
                    entries = context.resources
                        .getStringArray(R.array.fcl_vnext_dose_distribution_entries)
                        .map { it as CharSequence }
                        .toTypedArray(),
                    entryValues = context.resources
                        .getStringArray(R.array.fcl_vnext_dose_distribution_values)
                        .map { it as CharSequence }
                        .toTypedArray()
                )
            )


        }

        // =================================================
        // 2️⃣ CONTEXT
        // =================================================
        val CONTEXT = preferenceManager.createPreferenceScreen(context).apply {
            key = "FCLvNextContext"
            title = "🌙  🛡️  🚶  Context"
            initialExpandedChildrenCount = Int.MAX_VALUE

            addPreference(
                AdaptiveIntentPreference(
                    ctx = context,
                    intentKey = IntentKey.ApsLinkToDocs,
                    intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(context.getString(R.string.fcl_vnext_3_CONTEXT_url))
                    },
                    title = R.string.fcl_vnext_1_CONTEXT_title,
                    summary = R.string.fcl_vnext_1_CONTEXT_summary
                )
            )


             // ─────────────────────────────────────────────
            // 🌙 DAG / NACHT (robust input: picker + multiselect)
            // ─────────────────────────────────────────────
            addPreference(
                preferenceManager.createPreferenceScreen(context).apply {
                    key = "FCLvNextDayNight"
                    title = "🌙 Dag / nacht"

                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_intro)
                        }
                    )

                    addPreference(
                        AdaptiveCsvMultiSelectPreference(
                            ctx = context,
                            preferences = preferences,
                            stringKey = StringKey.WeekendDagen,
                            titleRes = R.string.WeekendDagen_title,
                            summaryRes = R.string.WeekendDagen_summary,
                            entriesRes = R.array.weekday_entries,
                            entryValuesRes = R.array.weekday_values
                        )
                    )

                    addPreference(
                        AdaptiveTimePreference(
                            ctx = context,
                            preferences = preferences,
                            stringKey = StringKey.OchtendStart,
                            titleRes = R.string.OchtendStart_title,
                            summaryRes = R.string.OchtendStart_summary
                        )
                    )

                    addPreference(
                        AdaptiveTimePreference(
                            ctx = context,
                            preferences = preferences,
                            stringKey = StringKey.OchtendStartWeekend,
                            titleRes = R.string.OchtendStartWeekend_title,
                            summaryRes = R.string.OchtendStartWeekend_summary
                        )
                    )

                    addPreference(
                        AdaptiveTimePreference(
                            ctx = context,
                            preferences = preferences,
                            stringKey = StringKey.NachtStart,
                            titleRes = R.string.NachtStart_title,
                            summaryRes = R.string.NachtStart_summary
                        )
                    )
                }
            )



            // ─────────────────────────────────────────────
           // 🛡️ RESISTENTIE (VOLLEDIG)
          // ─────────────────────────────────────────────
            addPreference(
                preferenceManager.createPreferenceScreen(context).apply {
                    key = "FCLvNextResistance"
                    title = "🛡️ AutoSens"

                    // Algemene context-intro (optioneel)
                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_intro)
                        }
                    )

                    // Specifieke AutoSens uitleg
                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_resistance_intro)
                        }
                    )
                    // Specifieke AutoSens behavior uitleg
                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_resistance_behavior_summary)
                        }
                    )

                    // Gedrag
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.fcl_vnext_resistance_behavior,
                            title = R.string.fcl_vnext_resistance_behavior_title,
                            entries = context.resources
                                .getStringArray(R.array.fcl_vnext_resistance_behavior_entries)
                                .map { it as CharSequence }
                                .toTypedArray(),
                            entryValues = context.resources
                                .getStringArray(R.array.fcl_vnext_resistance_behavior_values)
                                .map { it as CharSequence }
                                .toTypedArray()
                        )
                    )

                    // Specifieke AutoSens Stabiliteit uitleg
                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_resistance_stability_summary)
                        }
                    )

                    // Stabiliteit
                    addPreference(
                        AdaptiveListPreference(
                            ctx = context,
                            stringKey = StringKey.fcl_vnext_resistance_stability,
                            title = R.string.fcl_vnext_resistance_stability_title,
                            entries = context.resources
                                .getStringArray(R.array.fcl_vnext_resistance_stability_entries)
                                .map { it as CharSequence }
                                .toTypedArray(),
                            entryValues = context.resources
                                .getStringArray(R.array.fcl_vnext_resistance_stability_values)
                                .map { it as CharSequence }
                                .toTypedArray()
                        )
                    )
                }
            )


            // ─────────────────────────────────────────────
            // 🚶 ACTIVITEIT (VOLLEDIG)
            // ─────────────────────────────────────────────
            addPreference(
                preferenceManager.createPreferenceScreen(context).apply {
                    key = "FCLvNextActivity"
                    title = "🚶 Activiteit & beweging"

                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_intro)
                        }
                    )
                    // Specifieke activity uitleg
                    addPreference(
                        Preference(context).apply {
                            isSelectable = false
                            summary = context.getString(R.string.fcl_vnext_activity_intro)
                        }
                    )

                    addPreference(
                    AdaptiveListPreference(
                        ctx = context,
                        stringKey = StringKey.fcl_vnext_activity_behavior,
                        title = R.string.fcl_vnext_activity_behavior_title,
                        summary = R.string.fcl_vnext_activity_behavior_summary,
                        entries = context.resources
                            .getStringArray(R.array.fcl_vnext_activity_behavior_entries)
                            .map { it as CharSequence }
                            .toTypedArray(),
                        entryValues = context.resources
                            .getStringArray(R.array.fcl_vnext_activity_behavior_values)
                            .map { it as CharSequence }
                            .toTypedArray()
                    )
                    )

                }
            )
        }


        // =================================================
        // ROOT
        // =================================================
        parent.addPreference(GENERAL)
        parent.addPreference(PROFILES)
        parent.addPreference(CONTEXT)
     //   parent.addPreference(SAFETY)
    }







}
