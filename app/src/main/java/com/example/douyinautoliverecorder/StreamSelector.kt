package com.example.douyinautoliverecorder

import java.util.LinkedHashSet

object StreamSelector {
    fun orderedUrls(
        streamUrls: Map<String, String>,
        quality: RecordQuality,
        bitrate: BitratePreset? = null
    ): List<String> {
        if (streamUrls.isEmpty()) {
            return emptyList()
        }

        val normalized = streamUrls.entries.associate { it.key.uppercase() to it.value.trim() }
        val ordered = LinkedHashSet<String>()

        quality.orderedKeys.forEach { key ->
            normalized["HLS_$key"]?.takeIf(::isVideoStreamUrl)?.let(ordered::add)
            normalized[key]?.takeIf(::isVideoStreamUrl)?.let(ordered::add)
        }

        normalized.entries
            .filter { isVideoStreamUrl(it.value) }
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { if (it.key.startsWith("HLS_")) 1 else 0 }
                    .thenByDescending { totalScore(it.key, it.value, quality, bitrate) }
                    .thenBy { it.key }
            )
            .forEach { entry ->
                ordered += entry.value
            }

        return ordered.toList()
    }

    fun primaryUrl(
        streamUrls: Map<String, String>,
        quality: RecordQuality,
        bitrate: BitratePreset? = null
    ): String? {
        return orderedUrls(streamUrls, quality, bitrate).firstOrNull()
    }

    private fun totalScore(
        key: String,
        url: String,
        quality: RecordQuality,
        bitrate: BitratePreset?
    ): Int {
        return scoreForQuality(key, url, quality) * 1000 + scoreForBitrate(key, url, bitrate)
    }

    private fun scoreForQuality(key: String, url: String, quality: RecordQuality): Int {
        val tier = detectTier(key, url)
        return when (quality) {
            RecordQuality.P1080 -> when (tier) {
                StreamTier.P1080 -> 400
                StreamTier.P720 -> 300
                StreamTier.P480 -> 200
                StreamTier.P360 -> 100
                StreamTier.UNKNOWN -> 0
            }

            RecordQuality.P720 -> when (tier) {
                StreamTier.P720 -> 400
                StreamTier.P1080 -> 300
                StreamTier.P480 -> 200
                StreamTier.P360 -> 100
                StreamTier.UNKNOWN -> 0
            }

            RecordQuality.P480 -> when (tier) {
                StreamTier.P480 -> 400
                StreamTier.P720 -> 300
                StreamTier.P1080 -> 200
                StreamTier.P360 -> 100
                StreamTier.UNKNOWN -> 0
            }

            RecordQuality.P360 -> when (tier) {
                StreamTier.P360 -> 400
                StreamTier.P480 -> 300
                StreamTier.P720 -> 200
                StreamTier.P1080 -> 100
                StreamTier.UNKNOWN -> 0
            }
        }
    }

    private fun scoreForBitrate(key: String, url: String, bitrate: BitratePreset?): Int {
        val tier = detectTier(key, url)
        return when (bitrate ?: BitratePreset.MBPS_8) {
            BitratePreset.MBPS_16 -> when (tier) {
                StreamTier.P1080 -> 400
                StreamTier.P720 -> 300
                StreamTier.P480 -> 200
                StreamTier.P360 -> 100
                StreamTier.UNKNOWN -> 0
            }

            BitratePreset.MBPS_8 -> when (tier) {
                StreamTier.P720 -> 400
                StreamTier.P1080 -> 300
                StreamTier.P480 -> 200
                StreamTier.P360 -> 100
                StreamTier.UNKNOWN -> 0
            }

            BitratePreset.MBPS_4 -> when (tier) {
                StreamTier.P480 -> 400
                StreamTier.P720 -> 300
                StreamTier.P360 -> 200
                StreamTier.P1080 -> 100
                StreamTier.UNKNOWN -> 0
            }

            BitratePreset.MBPS_2 -> when (tier) {
                StreamTier.P360 -> 400
                StreamTier.P480 -> 300
                StreamTier.P720 -> 200
                StreamTier.P1080 -> 100
                StreamTier.UNKNOWN -> 0
            }
        }
    }

    private fun detectTier(key: String, url: String): StreamTier {
        val text = "${key.lowercase()} ${url.lowercase()}"
        return when {
            containsAny(text, listOf("full_hd1", "origin", "1080", "or4", "t000or", "t00108")) -> StreamTier.P1080
            containsAny(text, listOf("hd1", "720", "_hd", "/hd/", "t000hd", " md", "_md", "/md/", "540")) -> StreamTier.P720
            containsAny(text, listOf("sd1", "480", "_sd", "/sd/", "t000sd")) -> StreamTier.P480
            containsAny(text, listOf("ld1", "360", "_ld", "/ld/", "t000ld", "low")) -> StreamTier.P360
            else -> StreamTier.UNKNOWN
        }
    }

    private fun containsAny(text: String, needles: List<String>): Boolean {
        return needles.any { needle -> needle in text }
    }

    private fun isVideoStreamUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        if (normalized.isBlank()) {
            return false
        }
        if ("only_audio=1" in normalized || "audio_only=1" in normalized) {
            return false
        }
        return normalized.startsWith("http")
    }

    private enum class StreamTier {
        P1080,
        P720,
        P480,
        P360,
        UNKNOWN
    }
}

