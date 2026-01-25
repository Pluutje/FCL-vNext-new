package app.aaps.plugins.aps.openAPSFCL.vnext

import org.joda.time.DateTime
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSFCL.vnext.learning.*


class FCLvNextStatusFormatter(private val prefs: Preferences) {


    private fun formatDeliveryHistory(
        history: List<Pair<DateTime, Double>>?
    ): String {
        if (history.isNullOrEmpty()) return "Geen recente afleveringen"

        return history.joinToString("\n") { (ts, dose) ->
            "${ts.toString("HH:mm")}  ${"%.2f".format(dose)}U"
        }
    }
    private fun profileLabel(value: String): String =
        when (value) {
            "VERY_STRICT" -> "Zeer voorzichtig"
            "STRICT"      -> "Voorzichtig"
            "BALANCED"    -> "Gebalanceerd"
            "AGGRESSIVE"   -> "Actief"
            "VERY_AGGRESSIVE"  -> "Zeer actief"
            else          -> value
        }

    private fun mealDetectLabel(value: String): String =
        when (value) {
            "VERY_SLOW"  -> "Zeer laat"
            "SLOW"       -> "Laat"
            "MODERATE"   -> "Normaal"
            "FAST"       -> "Snel"
            "VERY_FAST" -> "Zeer snel"
            else         -> value
        }

    private fun correctionStyleLabel(value: String): String =
        when (value) {
            "VERY_CAUTIOUS" -> "Zeer terughoudend"
            "CAUTIOUS"      -> "Voorzichtig"
            "NORMAL"        -> "Normaal"
            "PERSISTENT"    -> "Vasthoudend"
            "VERY_PERSISTENT" -> "Zeer vasthoudend"
            else            -> value
        }

    private fun doseDistributionLabel(value: String): String =
        when (value) {
            "VERY_SMOOTH"        -> "Ultra smooth"
            "SMOOTH"      -> "Smooth"
            "BALANCED"        -> "Gebalanceerd"
            "PULSED" -> "Pulsed"
            "VERY_PULSED" -> "Ultra pulsed"
            else            -> value
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

    private fun humanLearningStatus(status: SnapshotStatus): String =
        when (status) {
            SnapshotStatus.INIT ->
                "⏳ Initialiseren"
            SnapshotStatus.OBSERVING ->
                "👀 Observerend"
            SnapshotStatus.SIGNAL_PRESENT ->
                "🧠 Lerend"
        }


    private fun humanEpisodePhase(
        snapshot: FCLvNextObsSnapshot
    ): String {
        // Heuristisch, veilig
        if (!snapshot.activeEpisode || snapshot.activeEpisodeStartedAt == null)
            return "—"

        return when {
            snapshot.deliveryConfidence < 0.6 ->
                "🟡 TAIL (voorzichtig afronden)"
            else ->
                "🟢 ACTIEF (insuline-effect verwacht)"
        }
    }

    private fun minutesBetween(a: DateTime, b: DateTime): Long =
        (b.millis - a.millis) / 60000

    private fun buildDeliveryGateBlock(
        snapshot: FCLvNextObsSnapshot?
    ): String {

        val gate = snapshot?.deliveryGateStatus
            ?: return """
💉 INSULINE CONTROLE
─────────────────────
Nog geen delivery-analyse
""".trimIndent()

        val status =
            if (gate.confidence >= 0.9) "🟢 OK"
            else if (gate.confidence >= 0.6) "🟡 Onzeker"
            else "🔴 Onbetrouwbaar"

        return """
💉 INSULINE CONTROLE
─────────────────────
• Status      : $status
• Confidence  : ${"%.2f".format(gate.confidence)}
${gate.reason?.let { "• Opmerking   : $it" } ?: ""}
""".trimIndent()
    }



    private fun buildLearningSnapshotBlock(
        snapshot: FCLvNextObsSnapshot?
    ): String {

        if (snapshot == null) {
            return """
📚 LEARNING
─────────────────────
Nog geen observaties beschikbaar
""".trimIndent()
        }

        val sb = StringBuilder()

        // ── LEARNING STATUS ─────────────────────
        sb.append("📚 LEARNING\n")
        sb.append("─────────────────────\n")
        sb.append("• Status        : ${humanLearningStatus(snapshot.status)}\n")
        sb.append("• Episodes      : ${snapshot.totalEpisodes}\n")
        sb.append("• Delivery conf : ${"%.2f".format(snapshot.deliveryConfidence)}\n")
        sb.append("• Laatste check : ${snapshot.createdAt.toString("HH:mm:ss")}\n")

        // ── EPISODE STATUS ──────────────────────
        sb.append("\n🧩 EPISODE\n")
        sb.append("─────────────────────\n")

        if (snapshot.activeEpisode && snapshot.activeEpisodeStartedAt != null) {
            val mins =
                minutesBetween(snapshot.activeEpisodeStartedAt, DateTime.now())

            sb.append("• Status        : 🟢 ACTIEF\n")
            sb.append("• Gestart       : ${snapshot.activeEpisodeStartedAt.toString("HH:mm")}\n")
            sb.append("• Duur          : ${mins} min\n")
            sb.append("• Fase          : ${humanEpisodePhase(snapshot)}\n")
        } else {
            sb.append("• Status        : ⚪ Geen actieve episode\n")
        }

        if (snapshot.lastEpisodeStart != null && snapshot.lastEpisodeEnd != null) {
            val mins =
                minutesBetween(snapshot.lastEpisodeStart, snapshot.lastEpisodeEnd)

            sb.append(
                "\n• Laatste episode : " +
                    "${snapshot.lastEpisodeStart.toString("HH:mm")} → " +
                    "${snapshot.lastEpisodeEnd.toString("HH:mm")} " +
                    "(${mins} min)\n"
            )
        }

        // ── AXES ────────────────────────────────
        snapshot.axes.forEach { axis ->
            sb.append("\n${axis.axis}\n")
            sb.append("─────────────────────\n")

            val total = snapshot.totalEpisodes.toInt()
            val nonOk = axis.episodesSeen
            val ok = (total - nonOk).coerceAtLeast(0)

            val statusText = when (axis.status) {
                AxisStatus.NO_DIRECTION -> "nog geen richting"
                AxisStatus.WEAK_SIGNAL -> "zwak signaal"
                AxisStatus.STRUCTURAL_SIGNAL -> "structureel signaal"
            }

            sb.append("• ${statusText}\n")

            val details = mutableListOf<String>()

            if (ok > 0 && nonOk > 0) {
                details.add("${ok}× OK")
            }

            axis.percentages
                .toList()
                .sortedByDescending { it.second }
                .forEach { (outcome, pct) ->
                    val count =
                        ((pct / 100.0) * nonOk)
                            .toInt()
                            .coerceAtLeast(1)
                    details.add("${count}× ${outcome.name}")
                }

            if (details.isNotEmpty()) {
                sb.append("  (")
                    .append(details.joinToString(", "))
                    .append(")\n")
            }

            if (axis.dominantOutcome != null &&
                axis.status != AxisStatus.NO_DIRECTION
            ) {
                sb.append(
                    "  ↳ dominant: ${axis.dominantOutcome} " +
                        "(conf ${"%.2f".format(axis.dominantConfidence)})\n"
                )
            }
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
        learningSnapshot: FCLvNextObsSnapshot?

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

        // Huidige versie FCL V3

        return """
════════════════════════
 🧠 FCL vNext V3 v1.7.0
════════════════════════
• Profiel              : ${profileLabel(prefs.get(StringKey.fcl_vnext_profile))}
• Meal detect          : ${mealDetectLabel(prefs.get(StringKey.fcl_vnext_meal_detect_speed))}
• Correctiestijl       : ${correctionStyleLabel(prefs.get(StringKey.fcl_vnext_correction_style))}
• Insulineverdeling    : ${doseDistributionLabel(prefs.get(StringKey.fcl_vnext_dose_distribution_style))}


$coreStatus

$fclCore

$activityStatus

$resistanceStatus

${buildLearningSnapshotBlock(learningSnapshot)}

${buildDeliveryGateBlock(learningSnapshot)}

$metricsStatus
""".trimIndent()
    }
}
