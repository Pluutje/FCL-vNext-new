package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.LearningParameterSpecs
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.LearningDomain
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.LearningAdvice

class FCLvNextStatusFormatter(private val prefs: Preferences) {


    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }


    /**
     * Haal de blokregels onder "LEARNING ADVICE:" eruit.
     * In jouw FCLvNext wordt dat zo opgebouwd:
     *   LEARNING ADVICE:
     *    - param ↑ conf=.. n=..
     *    - ...
     */
    private fun extractLearningAdviceLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val lines = statusText.split("\n")
        var inBlock = false
        val out = ArrayList<String>()

        for (raw in lines) {
            val line = raw.trim()

            if (!inBlock) {
                if (line == "LEARNING ADVICE:") {
                    inBlock = true
                }
                continue
            }

            // we zitten in het block
            // stopcriteria: lege regel of een duidelijke sectiewissel
            if (line.isEmpty()) break

            // jouw advice regels beginnen met "-"
            if (line.length >= 1 && line[0] == '-') {
                out.add(line)
            } else {
                // als het geen advice-regel meer is, stoppen we om rommel te voorkomen
                break
            }
        }

        return out
    }

    private fun extractProfileAdviceLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 14 && t.substring(0, 14) == "PROFILE ADVICE:") {
                return t
            }
        }
        return null
    }

    private fun extractProfileReasonLine(statusText: String?): String? {
        if (statusText.isNullOrBlank()) return null
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 15 && t.substring(0, 15) == "PROFILE REASON:") {
                return t
            }
        }
        return null
    }

    private fun extractPersistLines(statusText: String?): List<String> {
        if (statusText.isNullOrBlank()) return emptyList()

        val out = ArrayList<String>()
        for (line in statusText.split("\n")) {
            val t = line.trim()
            if (t.length >= 7 && t.substring(0, 7) == "PERSIST") {
                out.add(t)
            }
        }
        return out
    }

    private fun buildLearningAdviceBlock(
        adviceList: List<LearningAdvice>
    ): String {

        if (adviceList.isEmpty()) {
            return """
📌 Learning adviezen
─────────────────────
Nog geen data verzameld
""".trimIndent()
        }

        val byDomain =
            adviceList.groupBy { advice ->
                LearningParameterSpecs
                    .specs[advice.parameter]
                    ?.domain
                    ?: LearningDomain.HEIGHT
            }

        val sb = StringBuilder()

        sb.append("📌 Learning adviezen\n")
        sb.append("─────────────────────\n")
        sb.append("Legenda: ↑/↓ richting • conf = sterkte • × = effect (preview)\n\n")

        fun appendDomain(
            title: String,
            subtitle: String,
            domain: LearningDomain
        ) {

            val items = byDomain[domain] ?: return
            if (items.isEmpty()) return

            val maxLabelWidth =
                items
                    .map { a ->
                        LearningParameterSpecs.specs[a.parameter]!!.uiLabel.length
                    }
                    .maxOrNull()
                    ?.coerceAtMost(20)   // harde cap → voorkomt extreem brede UI
                    ?: 20

            sb.append("• ").append(title).append("\n")
            sb.append("  ").append(subtitle).append("\n")

            items
                .sortedByDescending { it.confidence }
                .forEach { a ->

                    val dir =
                        when {
                            a.direction > 0 -> "↑"
                            a.direction < 0 -> "↓"
                            else -> "→"
                        }

                    val step =
                        when (domain) {
                            LearningDomain.TIMING -> 0.15
                            LearningDomain.HEIGHT -> 0.07
                        }

                    val spec = LearningParameterSpecs.specs[a.parameter]
                    val previewMul =
                        if (spec == null || a.direction == 0) 1.00
                        else (1.0 + a.direction * a.confidence * step)
                            .coerceIn(spec.minMultiplier, spec.maxMultiplier)

                    val label = LearningParameterSpecs.specs[a.parameter]!!.uiLabel

                    sb.append(
                        "   $label  $dir   conf ${"%.2f".format(a.confidence)}" +
                            "   n ${a.evidenceCount}   ×${"%.2f".format(previewMul)}\n"
                    )


                }

            sb.append("\n")
        }

        appendDomain(
            title = "Timing",
            subtitle = "(commitgedrag, detectiesnelheid – read-only tot voldoende vertrouwen)",
            domain = LearningDomain.TIMING
        )

        appendDomain(
            title = "Hoogte",
            subtitle = "(dosishoogte, piekonderdrukking – toegepast bij voldoende vertrouwen)",
            domain = LearningDomain.HEIGHT
        )

        return sb.toString().trimEnd()
    }





    /**
     * Maak statusText compacter:
     * - toont eerst profiel + learning advice (als aanwezig)
     * - daarna eventueel de rest van statusText (optioneel, compact)
     */
    private fun buildFclBlock(advice: FCLvNextAdvice?): String {
        if (advice == null) return "Geen FCL advies"

        val statusText = advice.statusText ?: ""
        val profileAdviceLine = extractProfileAdviceLine(statusText)
        val profileReasonLine = extractProfileReasonLine(statusText)
     //   val learningLines = extractLearningAdviceLines(statusText)

        val persistLines = extractPersistLines(statusText)

        val sb = StringBuilder()

        sb.append("🧠 FCL vNext\n")
        sb.append("─────────────────────\n")


        if (profileAdviceLine != null) {
            sb.append("• ").append(profileAdviceLine).append("\n")
            if (profileReasonLine != null) {
                sb.append("• ").append(profileReasonLine).append("\n")
            }
        }





        if (persistLines.isNotEmpty()) {
            sb.append("\n")
            sb.append("🔁 Persistente correctie\n")
            sb.append("─────────────────────\n")
            persistLines.forEach { line ->
                val human = when {
                    line.contains("building") ->
                        "Opbouw: glucose blijft gedurende meerdere metingen verhoogd"

                    line.contains("fire") ->
                        "Correctie gegeven wegens aanhoudend hoge glucose"

                    line.contains("cooldown") ->
                        "Wachttijd actief na correctie (veiligheidsinterval)"

                    line.contains("HOLD") ->
                        "Correctie bewust uitgesteld (stabiliteitsfase)"

                    else ->
                        line   // fallback: toon originele tekst
                }

                sb.append("• ").append(human).append("\n")
            }

        }

        // Optioneel: als je tóch nog debug wil zien, laat hier een compacte excerpt zien.
        // Nu: alleen de eerste ~25 regels om UI netjes te houden.
        val lines = statusText.split("\n").map { it.trim() }

        fun section(title: String, filter: (String) -> Boolean) {
            val block = lines.filter(filter)
            if (block.isNotEmpty()) {
                sb.append("\n")
                sb.append(title).append("\n")
                sb.append("─────────────────────\n")
                block.forEach { sb.append(it).append("\n") }
            }
        }

// 📈 Trends & dynamiek
        section("📈 Trend & dynamiek") {
            it.startsWith("TREND") ||
                it.startsWith("TrendPersistence") ||
                it.startsWith("PeakEstimate")
        }

// 💉 Dosering
        section("💉 Dosering & beslissingen") {
            it.startsWith("RawDose") ||
                it.startsWith("Decision=") ||
                it.startsWith("Trajectory") ||
                it.startsWith("ACCESS")
        }

// ⏳ Timing / commits
        section("⏳ Timing & commits") {
            it.startsWith("Commit") ||
                it.startsWith("OBSERVE") ||
                it.startsWith("DELIVERY")
        }


        return sb.toString().trimEnd()
    }

    fun buildStatus(
        isNight: Boolean,
        advice: FCLvNextAdvice?,
        bolusAmount: Double,
        basalRate: Double,
        shouldDeliver: Boolean,
        activityLog: String?,
        resistanceLog: String?,
        metricsText: String?,
        learningStatusText: String?,
        learningAdvice: List<LearningAdvice>  , // 👈 NIEUW
        learningPhase: String
    ): String {

        val coreStatus = """
STATUS: (${if (isNight) "'S NACHTS" else "OVERDAG"})
─────────────────────
• Laatste update: ${DateTime.now().toString("HH:mm:ss")}
• Advies actief: ${if (shouldDeliver) "JA" else "NEE"}
• Bolus: ${"%.2f".format(bolusAmount)} U
• Basaal: ${"%.2f".format(basalRate)} U/h

🧪 LAATSTE DOSIS
─────────────────────
${formatDeliveryHistory(advice?.let { deliveryHistory.toList() })}
""".trimIndent()

        val fclCore = buildFclBlock(advice)


        val phaseLabel =
            when (learningPhase) {
                "TIMING_ONLY" -> "TIMING_ONLY (alleen timing, read-only)"
                "TIMING_AND_HEIGHT" -> "TIMING_AND_HEIGHT (timing + hoogte)"
                else -> learningPhase
            }

        val learningAdviceBlock = """
🧠 Learning status
─────────────────────
• Fase: $phaseLabel

${buildLearningAdviceBlock(learningAdvice)}
""".trimIndent()






        val activityStatus = """
🏃 ACTIVITEIT
─────────────────────
${activityLog ?: "Geen activiteitdata"}
""".trimIndent()

        val resistanceStatus = """
🧬 AUTO-SENS
─────────────────────
${resistanceLog ?: "Geen resistentie-log"}
""".trimIndent()

        val metricsStatus = """
📊 GLUCOSE STATISTIEKEN
─────────────────────
${metricsText ?: "Nog geen data"}
""".trimIndent()

        return """
════════════════════════
 🧠 FCL vNext v2.5.0
════════════════════════
• Profiel              : ${prefs.get(StringKey.fcl_vnext_profile)}
• Meal Detect Speed  : ${prefs.get(StringKey.fcl_vnext_meal_detect_speed)}
• Correction style   : ${prefs.get(StringKey.fcl_vnext_correction_style)}
• Insulin distribution : ${prefs.get(StringKey.fcl_vnext_dose_distribution_style)}


$coreStatus

$fclCore

$learningAdviceBlock

$activityStatus

$resistanceStatus

$metricsStatus
""".trimIndent()
    }
}
