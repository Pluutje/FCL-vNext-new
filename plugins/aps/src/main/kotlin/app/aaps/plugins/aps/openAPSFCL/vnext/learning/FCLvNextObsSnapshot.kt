package app.aaps.plugins.aps.openAPSFCL.vnext.learning

import org.joda.time.DateTime

enum class SnapshotStatus {
    INIT,              // 0 episodes afgerond
    OBSERVING,         // episodes gezien, maar nog geen structureel signaal
    SIGNAL_PRESENT     // ≥1 axis met STRUCTURAL_SIGNAL
}

enum class AxisStatus {
    NO_DIRECTION,     // vooral OK / UNKNOWN
    WEAK_SIGNAL,      // bias zichtbaar maar < emit-drempel
    STRUCTURAL_SIGNAL // confidence ≥ emitMinConfidence
}

data class DeliveryGateStatus(
    val confidence: Double,
    val ok: Boolean,
    val reason: String?
)

data class AxisSnapshot(
    val axis: Axis,
    val percentages: Map<AxisOutcome, Double>, // 0..100, som = 100
    val dominantOutcome: AxisOutcome?,
    val dominantConfidence: Double,
    val status: AxisStatus,
    val episodesSeen: Int,
    val lastEpisodeAt: DateTime?
)

data class FCLvNextObsSnapshot(
    val createdAt: DateTime,

    // Globaal
    val totalEpisodes: Long,
    val activeEpisode: Boolean,
    val activeEpisodeStartedAt: DateTime?,
    val deliveryConfidence: Double,
    val status: SnapshotStatus,

    // Per as
    val axes: List<AxisSnapshot>,

    // ⬇️ laatste episode
    val lastEpisodeStart: DateTime?,
    val lastEpisodeEnd: DateTime?,
    val deliveryGateStatus: DeliveryGateStatus?
)
