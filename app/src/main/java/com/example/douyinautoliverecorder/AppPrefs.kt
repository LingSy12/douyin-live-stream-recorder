package com.example.douyinautoliverecorder

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AppPrefs {
    const val MIN_CHECK_INTERVAL_SECONDS = 10
    const val MAX_CHECK_INTERVAL_SECONDS = 600

    private const val PREFS_NAME = "douyin_live_recorder_prefs"
    private const val KEY_ROOMS_JSON = "rooms_json"
    private const val KEY_MONITOR_ENABLED = "monitor_enabled"
    private const val KEY_CHECK_INTERVAL_SECONDS = "check_interval_seconds"
    private const val KEY_QUALITY = "quality"
    private const val KEY_BITRATE = "bitrate"
    private const val KEY_STORAGE_MODE = "storage_mode"
    private const val KEY_SAVE_OUTPUT_MODE = "save_output_mode"
    private const val KEY_STORAGE_TREE_URI = "storage_tree_uri"
    private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
    private const val KEY_MONITOR_WINDOW_START = "monitor_window_start"
    private const val KEY_MONITOR_WINDOW_END = "monitor_window_end"

    fun getSettings(context: Context): AppSettings {
        val prefs = prefs(context)
        return AppSettings(
            monitorEnabled = prefs.getBoolean(KEY_MONITOR_ENABLED, false),
            checkIntervalSeconds = prefs.getInt(KEY_CHECK_INTERVAL_SECONDS, 45)
                .coerceIn(MIN_CHECK_INTERVAL_SECONDS, MAX_CHECK_INTERVAL_SECONDS),
            quality = RecordQuality.fromName(prefs.getString(KEY_QUALITY, RecordQuality.P1080.name)),
            bitrate = BitratePreset.fromName(prefs.getString(KEY_BITRATE, BitratePreset.MBPS_8.name)),
            storageMode = StorageMode.fromName(
                prefs.getString(KEY_STORAGE_MODE, StorageMode.PUBLIC_MEDIA.name)
            ),
            saveOutputMode = SaveOutputMode.fromName(
                prefs.getString(KEY_SAVE_OUTPUT_MODE, SaveOutputMode.RAW_AND_DANMU.name)
            ),
            storageTreeUri = prefs.getString(KEY_STORAGE_TREE_URI, null),
            scheduleEnabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false),
            monitorWindowStartMinutes = prefs.getInt(KEY_MONITOR_WINDOW_START, 0).coerceIn(0, 1439),
            monitorWindowEndMinutes = prefs.getInt(KEY_MONITOR_WINDOW_END, 23 * 60 + 59).coerceIn(0, 1439)
        )
    }

    fun setSettings(context: Context, settings: AppSettings) {
        prefs(context).edit()
            .putBoolean(KEY_MONITOR_ENABLED, settings.monitorEnabled)
            .putInt(
                KEY_CHECK_INTERVAL_SECONDS,
                settings.checkIntervalSeconds.coerceIn(
                    MIN_CHECK_INTERVAL_SECONDS,
                    MAX_CHECK_INTERVAL_SECONDS
                )
            )
            .putString(KEY_QUALITY, settings.quality.name)
            .putString(KEY_BITRATE, settings.bitrate.name)
            .putString(KEY_STORAGE_MODE, settings.storageMode.name)
            .putString(KEY_SAVE_OUTPUT_MODE, settings.saveOutputMode.name)
            .putString(KEY_STORAGE_TREE_URI, settings.storageTreeUri)
            .putBoolean(KEY_SCHEDULE_ENABLED, settings.scheduleEnabled)
            .putInt(KEY_MONITOR_WINDOW_START, settings.monitorWindowStartMinutes.coerceIn(0, 1439))
            .putInt(KEY_MONITOR_WINDOW_END, settings.monitorWindowEndMinutes.coerceIn(0, 1439))
            .apply()
    }

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }

    fun getRooms(context: Context): List<MonitoredRoom> {
        val raw = prefs(context).getString(KEY_ROOMS_JSON, "[]") ?: "[]"
        val json = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val rooms = mutableListOf<MonitoredRoom>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val input = item.optString("input")
            val normalized = item.optString("normalized_input")
            if (id.isBlank() || input.isBlank() || normalized.isBlank()) {
                continue
            }
            val storedDouyinId = item.optString("douyin_id").takeIf { it.isNotBlank() }
            val stableNormalized = storedDouyinId
                ?.takeIf { !it.startsWith("$") && !it.contains("undefined", ignoreCase = true) }
                ?.let { stableId ->
                    val currentId = RoomInputNormalizer.extractLiveRoomId(normalized)
                    if (!currentId.isNullOrBlank() && currentId != stableId) {
                        "https://live.douyin.com/$stableId"
                    } else {
                        normalized
                    }
                }
                ?: normalized

            val scheduleEnabled = item.optBoolean("schedule_enabled", false)
            val windowStart = item.optInt("monitor_window_start", 0).coerceIn(0, 1439)
            val windowEnd = item.optInt("monitor_window_end", 23 * 60 + 59).coerceIn(0, 1439)

            rooms += MonitoredRoom(
                id = id,
                input = input,
                normalizedInput = stableNormalized,
                enabled = item.optBoolean("enabled", true),
                scheduleEnabled = scheduleEnabled,
                monitorWindowStartMinutes = windowStart,
                monitorWindowEndMinutes = windowEnd,
                displayName = item.optString("display_name").takeIf { it.isNotBlank() },
                douyinId = storedDouyinId,
                avatarUrl = item.optString("avatar_url").takeIf { it.isNotBlank() }
            )
        }
        return rooms
    }

    fun addRoom(context: Context, rawInput: String): AddRoomResult {
        val normalized = RoomInputNormalizer.normalize(rawInput)
        if (normalized.isBlank()) {
            return AddRoomResult(error = AppText.roomRequired(context))
        }

        val current = getRooms(context).toMutableList()
        val targetKey = RoomInputNormalizer.extractComparableKey(normalized)

        if (current.any { RoomInputNormalizer.extractComparableKey(it.normalizedInput) == targetKey }) {
            return AddRoomResult(error = AppText.roomExists(context))
        }

        val seededDouyinId = RoomInputNormalizer.extractIdHint(rawInput)
            ?: RoomInputNormalizer.extractLiveRoomId(normalized)

        val room = MonitoredRoom(
            id = UUID.randomUUID().toString(),
            input = rawInput.trim(),
            normalizedInput = normalized,
            enabled = true,
            displayName = RoomInputNormalizer.extractDisplayHint(rawInput) ?: seededDouyinId,
            douyinId = seededDouyinId,
            avatarUrl = null
        )
        current += room
        saveRooms(context, current)
        return AddRoomResult(room = room)
    }

    fun setRoomEnabled(context: Context, roomId: String, enabled: Boolean) {
        val updated = getRooms(context).map {
            if (it.id == roomId) it.copy(enabled = enabled) else it
        }
        saveRooms(context, updated)
    }

    fun moveRoom(context: Context, roomId: String, offset: Int): Boolean {
        if (offset == 0) {
            return false
        }
        val current = getRooms(context).toMutableList()
        val fromIndex = current.indexOfFirst { it.id == roomId }
        if (fromIndex < 0) {
            return false
        }
        val toIndex = (fromIndex + offset).coerceIn(0, current.lastIndex)
        return moveRoomTo(context, roomId, toIndex)
    }

    fun moveRoomTo(context: Context, roomId: String, targetIndex: Int): Boolean {
        val current = getRooms(context).toMutableList()
        val fromIndex = current.indexOfFirst { it.id == roomId }
        if (fromIndex < 0) {
            return false
        }
        val toIndex = targetIndex.coerceIn(0, current.lastIndex)
        if (fromIndex == toIndex) {
            return false
        }
        val room = current.removeAt(fromIndex)
        current.add(toIndex, room)
        saveRooms(context, current)
        return true
    }

    fun updateRoomMetadata(
        context: Context,
        roomId: String,
        displayName: String?,
        douyinId: String?,
        avatarUrl: String?,
        resolvedRoomId: String?
    ) {

        val current = getRooms(context)
        var changed = false
        val updated = current.map { room ->
            if (room.id != roomId) {
                return@map room
            }

            val actualDouyinId = douyinId?.takeIf { it.isNotBlank() }
                ?: room.douyinId
                ?: RoomInputNormalizer.extractIdHint(room.input)
                ?: resolvedRoomId?.takeIf { it.isNotBlank() }
                ?: RoomInputNormalizer.extractLiveRoomId(room.normalizedInput)
            val displayCandidate = displayName?.takeIf { it.isNotBlank() }
            val shouldPreserveDisplay = !room.displayName.isNullOrBlank() && (
                displayCandidate.isNullOrBlank() ||
                    displayCandidate.equals(actualDouyinId, ignoreCase = true) ||
                    (!resolvedRoomId.isNullOrBlank() && displayCandidate == resolvedRoomId)
                )
            val display = when {
                shouldPreserveDisplay -> room.displayName
                !displayCandidate.isNullOrBlank() -> displayCandidate
                !room.displayName.isNullOrBlank() -> room.displayName
                else -> actualDouyinId
            }
            val avatar = avatarUrl?.takeIf { it.isNotBlank() } ?: room.avatarUrl
            val normalized = actualDouyinId
                ?.takeIf { it.isNotBlank() && !it.startsWith("$") && !it.contains("undefined", ignoreCase = true) }
                ?.let { "https://live.douyin.com/$it" }
                ?: room.normalizedInput
            val newRoom = room.copy(
                displayName = display,
                douyinId = actualDouyinId,
                avatarUrl = avatar,
                normalizedInput = normalized
            )
            if (newRoom != room) {
                changed = true
            }
            newRoom
        }

        if (changed) {
            saveRooms(context, updated)
            val updatedRoom = updated.firstOrNull { it.id == roomId }
            Log.d(
                "AppPrefs",
                "updateRoomMetadata roomId=$roomId display=${updatedRoom?.displayName} douyinId=${updatedRoom?.douyinId} avatar=${updatedRoom?.avatarUrl} normalized=${updatedRoom?.normalizedInput}"
            )
        }
    }

    fun removeRoom(context: Context, roomId: String) {
        val updated = getRooms(context).filterNot { it.id == roomId }
        saveRooms(context, updated)
    }

    fun setRoomSchedule(
        context: Context,
        roomId: String,
        enabled: Boolean,
        startMinutes: Int,
        endMinutes: Int
    ) {
        val updated = getRooms(context).map { room ->
            if (room.id == roomId) {
                room.copy(
                    scheduleEnabled = enabled,
                    monitorWindowStartMinutes = startMinutes.coerceIn(0, 1439),
                    monitorWindowEndMinutes = endMinutes.coerceIn(0, 1439)
                )
            } else {
                room
            }
        }
        saveRooms(context, updated)
    }

    private fun saveRooms(context: Context, rooms: List<MonitoredRoom>) {
        val json = JSONArray()
        rooms.forEach { room ->
            json.put(
                JSONObject()
                    .put("id", room.id)
                    .put("input", room.input)
                    .put("normalized_input", room.normalizedInput)
                    .put("enabled", room.enabled)
                    .put("schedule_enabled", room.scheduleEnabled)
                    .put("monitor_window_start", room.monitorWindowStartMinutes)
                    .put("monitor_window_end", room.monitorWindowEndMinutes)
                    .put("display_name", room.displayName)
                    .put("douyin_id", room.douyinId)
                    .put("avatar_url", room.avatarUrl)
            )
        }
        prefs(context).edit().putString(KEY_ROOMS_JSON, json.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

data class AddRoomResult(
    val room: MonitoredRoom? = null,
    val error: String? = null
)









