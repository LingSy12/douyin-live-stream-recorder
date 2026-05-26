package com.example.douyinautoliverecorder

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

object MonitorWindow {
    private const val MINUTES_PER_DAY = 24 * 60
    private val timeRegex = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    fun parseMinutes(raw: String): Int? {
        val match = timeRegex.matchEntire(raw.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    fun formatMinutes(totalMinutes: Int): String {
        val normalized = ((totalMinutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val hour = normalized / 60
        val minute = normalized % 60
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    fun isWithinWindow(
        enabled: Boolean,
        startMinutes: Int,
        endMinutes: Int,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (!enabled) {
            return true
        }

        val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) {
            return true
        }

        val current = Instant.ofEpochMilli(nowMillis)
            .atZone(zoneId)
            .toLocalTime()
            .let { it.hour * 60 + it.minute }

        return if (start < end) {
            current in start..end
        } else {
            current >= start || current <= end
        }
    }

    fun isWithinWindow(
        settings: AppSettings,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        return isWithinWindow(
            enabled = settings.scheduleEnabled,
            startMinutes = settings.monitorWindowStartMinutes,
            endMinutes = settings.monitorWindowEndMinutes,
            nowMillis = nowMillis,
            zoneId = zoneId
        )
    }
}


