package com.example.douyinautoliverecorder

enum class RecordQuality(
    val label: String,
    val orderedKeys: List<String>,
    val outputHeight: Int
) {
    P1080(
        label = "1080p",
        orderedKeys = listOf("FULL_HD1", "ORIGIN", "HD1", "SD1", "LD1"),
        outputHeight = 1080
    ),
    P720(
        label = "720p",
        orderedKeys = listOf("HD1", "FULL_HD1", "ORIGIN", "SD1", "LD1"),
        outputHeight = 720
    ),
    P480(
        label = "480p",
        orderedKeys = listOf("SD1", "HD1", "FULL_HD1", "ORIGIN", "LD1"),
        outputHeight = 480
    ),
    P360(
        label = "360p",
        orderedKeys = listOf("LD1", "SD1", "HD1", "FULL_HD1", "ORIGIN"),
        outputHeight = 360
    );

    companion object {
        fun fromName(value: String?): RecordQuality {
            return entries.firstOrNull { it.name == value } ?: P1080
        }
    }
}

enum class BitratePreset(
    val label: String,
    val videoBitrateKbps: Int
) {
    MBPS_16("16 Mbps", 16000),
    MBPS_8("8 Mbps", 8000),
    MBPS_4("4 Mbps", 4000),
    MBPS_2("2 Mbps", 2000);

    companion object {
        fun fromName(value: String?): BitratePreset {
            return entries.firstOrNull { it.name == value } ?: MBPS_8
        }
    }
}

enum class StorageMode {
    PUBLIC_MEDIA,
    DOCUMENT_TREE;

    companion object {
        fun fromName(value: String?): StorageMode {
            return entries.firstOrNull { it.name == value } ?: PUBLIC_MEDIA
        }
    }
}

enum class SaveOutputMode {
    RAW_ONLY,
    RAW_AND_DANMU;

    companion object {
        fun fromName(value: String?): SaveOutputMode {
            return entries.firstOrNull { it.name == value } ?: RAW_AND_DANMU
        }
    }
}

data class MonitoredRoom(
    val id: String,
    val input: String,
    val normalizedInput: String,
    val enabled: Boolean = true,
    val scheduleEnabled: Boolean = false,
    val monitorWindowStartMinutes: Int = 0,
    val monitorWindowEndMinutes: Int = 23 * 60 + 59,
    val displayName: String? = null,
    val douyinId: String? = null,
    val avatarUrl: String? = null
)

data class AppSettings(
    val monitorEnabled: Boolean = false,
    val checkIntervalSeconds: Int = 45,
    val quality: RecordQuality = RecordQuality.P1080,
    val bitrate: BitratePreset = BitratePreset.MBPS_8,
    val storageMode: StorageMode = StorageMode.PUBLIC_MEDIA,
    val saveOutputMode: SaveOutputMode = SaveOutputMode.RAW_AND_DANMU,
    val liveCueEnabled: Boolean = true,
    val storageTreeUri: String? = null,
    val scheduleEnabled: Boolean = false,
    val monitorWindowStartMinutes: Int = 0,
    val monitorWindowEndMinutes: Int = 23 * 60 + 59
)

data class ProbeResult(
    val isLive: Boolean,
    val streamUrls: Map<String, String>,
    val roomDisplayName: String?,
    val douyinId: String?,
    val resolvedRoomId: String?,
    val avatarUrl: String?,
    val message: String?,
    val isReliable: Boolean = true,
    val blockedByVerification: Boolean = false
)

fun ProbeResult.shouldShowOfflineStatus(): Boolean {
    if (blockedByVerification) {
        return false
    }
    if (isLive) {
        return false
    }
    if (isReliable) {
        return true
    }
    return when (message?.trim()?.lowercase()) {
        "live status could not be confirmed.",
        "offline or stream url not found." -> true
        else -> false
    }
}

enum class RoomStatus {
    IDLE,
    CHECKING,
    OFFLINE,
    LIVE,
    RECORDING,
    ERROR
}

data class RoomRuntimeState(
    val status: RoomStatus = RoomStatus.IDLE,
    val message: String = "",
    val recordingPath: String? = null,
    val lastCheckedAtMs: Long = 0L,
    val startedAtMs: Long? = null,
    val saveProgressPercent: Int? = null,
    val lastLiveStartAtMs: Long? = null,
    val lastLiveEndAtMs: Long? = null
)



