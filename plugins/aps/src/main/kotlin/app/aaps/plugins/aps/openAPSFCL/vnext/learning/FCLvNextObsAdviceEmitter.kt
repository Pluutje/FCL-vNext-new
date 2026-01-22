package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

/**
 * Stap 5: AdviceEmitter
 *
 * Doel:
 * - Neem ConfidenceAccumulator output
 * - Emit read-only adviezen (tekst + debug) voor UI/console/logging
 * - GEEN parameter updates, GEEN invloed op dosing
 *
 * Let op:
 * - AdviceEmitter beslist alleen "wat melden we"
 * - ConfidenceAccumulator beslist "hoe zeker zijn we structureel"
 */

data class FCLvNextObsAdvice(
    val createdAt: DateTime,
    val axis: Axis,
    val outcome: AxisOutcome,
    val confidence: Double,      // 0..1
    val title: String,
    val message: String,
    val debug: String
)

data class FCLvNextObsAdviceBundle(
    val createdAt: DateTime,
    val advices: List<FCLvNextObsAdvice>,
    val debugSummary: String
)

data class FCLvNextObsAdviceEmitterConfig(
    val minConfidenceToEmit: Double = 0.70,
    val maxAdvices: Int = 4,

    // als confidence extreem hoog is, mogen we iets "harder" formuleren
    val strongConfidence: Double = 0.85
)

class FCLvNextObsAdviceEmitter(
    private val emitterCfg: FCLvNextObsAdviceEmitterConfig = FCLvNextObsAdviceEmitterConfig()
) {

    /**
     * Gebruik dit vanuit de plek waar je episode-finish afhandelt:
     * - accumulator.getTopSignals(...)
     * - daarna emitter.emit(...)
     */
    fun emit(
        now: DateTime,
        topSignals: List<AxisOutcomeConfidence>
    ): FCLvNextObsAdviceBundle {

        val eligible = topSignals
            .filter { it.confidence >= emitterCfg.minConfidenceToEmit }
            .sortedByDescending { it.confidence }
            .take(emitterCfg.maxAdvices)

        val advices = eligible.map { s ->
            val text = mapToText(s.axis, s.outcome, s.confidence)
            FCLvNextObsAdvice(
                createdAt = now,
                axis = s.axis,
                outcome = s.outcome,
                confidence = s.confidence,
                title = text.title,
                message = text.message,
                debug = buildString {
                    append("conf=${fmt(s.confidence)} ")
                    append("supportScore=${fmt(s.supportScore)} ")
                    append("supportCount=${s.supportCount} ")
                    append("lastSeen=${s.lastSeenAt?.toString("dd-MM HH:mm") ?: "n/a"} ")
                    append("notes=${s.notes}")
                }
            )
        }

        val debugSummary = buildString {
            append("OBS-ADVICES: emitted=${advices.size} ")
            if (advices.isEmpty()) {
                append("(none >= ${fmt(emitterCfg.minConfidenceToEmit)})")
            } else {
                append("-> ")
                append(
                    advices.joinToString(" | ") {
                        "${it.axis}/${it.outcome} conf=${fmt(it.confidence)}"
                    }
                )
            }
        }

        return FCLvNextObsAdviceBundle(
            createdAt = now,
            advices = advices,
            debugSummary = debugSummary
        )
    }

    // ─────────────────────────────────────────────
    // Mapping: Axis/Outcome -> menselijk advies
    // (Read-only hints; geen parameter namen, geen updates)
    // ─────────────────────────────────────────────

    private data class AdviceText(val title: String, val message: String)

    private fun mapToText(axis: Axis, outcome: AxisOutcome, confidence: Double): AdviceText {

        val strong = confidence >= emitterCfg.strongConfidence
        val confTxt = "confidence ${fmt(confidence)}"

        return when (axis) {

            Axis.TIMING -> when (outcome) {
                AxisOutcome.EARLY -> AdviceText(
                    title = if (strong) "Timing: vaak te vroeg" else "Timing: mogelijk te vroeg",
                    message =
                        "Episodes laten structureel zien dat de eerste betekenisvolle insulin vaak te vroeg komt. " +
                            "Controleer of dit samenvalt met post-meal dips of snelle dalingen na vroege toediening. " +
                            "($confTxt)"
                )

                AxisOutcome.LATE -> AdviceText(
                    title = if (strong) "Timing: vaak te laat" else "Timing: mogelijk te laat",
                    message =
                        "Episodes laten structureel zien dat insulin vaak pas laat op gang komt t.o.v. de stijging/peak. " +
                            "Let op tijd boven 10 mmol/L en time-to-peak. " +
                            "($confTxt)"
                )

                else -> AdviceText(
                    title = "Timing",
                    message = "Geen duidelijk timing-signaal. ($confTxt)"
                )
            }

            Axis.HEIGHT -> when (outcome) {
                AxisOutcome.TOO_HIGH -> AdviceText(
                    title = if (strong) "Hoogte: pieken te hoog" else "Hoogte: pieken mogelijk te hoog",
                    message =
                        "Werkelijke piekhoogtes komen structureel te hoog uit (boven jouw voorkeur, vaak >10 mmol/L). " +
                            "Dit kan betekenen dat de totale insulin respons (over episode) te zwak is, " +
                            "of dat piek-predictie/commit te terughoudend is. " +
                            "($confTxt)"
                )

                AxisOutcome.TOO_STRONG -> AdviceText(
                    title = if (strong) "Hoogte: respons te sterk" else "Hoogte: respons mogelijk te sterk",
                    message =
                        "Episodes wijzen erop dat de totale insulin respons te sterk is (risico op overshoot / post-peak dips). " +
                            "Let op rescue-events en snelle dalingen na piek. " +
                            "($confTxt)"
                )

                else -> AdviceText(
                    title = "Hoogte",
                    message = "Geen duidelijk hoogte-signaal. ($confTxt)"
                )
            }

            Axis.PERSISTENCE -> when (outcome) {
                AxisOutcome.TOO_SHORT -> AdviceText(
                    title = if (strong) "Volharding: te kort" else "Volharding: mogelijk te kort",
                    message =
                        "De episode laat structureel zien dat dosing/correctie te vroeg stopt: " +
                            "BG blijft te lang boven target of zakt te langzaam terug. " +
                            "Kijk naar persistent-correction gedrag en tail dosing. " +
                            "($confTxt)"
                )

                AxisOutcome.TOO_LONG -> AdviceText(
                    title = if (strong) "Volharding: te lang" else "Volharding: mogelijk te lang",
                    message =
                        "De episode laat structureel zien dat dosing/correctie te lang doorloopt: " +
                            "risico op post-peak dalingen en low drift. " +
                            "Kijk naar lockouts, post-peak suppress en reserve-release. " +
                            "($confTxt)"
                )

                else -> AdviceText(
                    title = "Volharding",
                    message = "Geen duidelijk persistentie-signaal. ($confTxt)"
                )
            }
        }
    }

    private fun fmt(x: Double): String = String.format("%.2f", x)
}

