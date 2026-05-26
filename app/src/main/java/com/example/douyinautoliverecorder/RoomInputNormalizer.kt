package com.example.douyinautoliverecorder

import java.net.URI

object RoomInputNormalizer {
    private val urlRegex = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val liveRoomUrlPatterns = listOf(
        Regex("live\\.douyin\\.com/([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE),
        Regex("webcast\\.amemv\\.com/douyin/webcast/reflow/([0-9]{6,})", RegexOption.IGNORE_CASE),
        Regex("[?&]room_id=([0-9]{6,})", RegexOption.IGNORE_CASE)
    )
    private val bracketNameRegex = Regex("\u3010([^\u3011]+)\u3011\\s*\u6b63\u5728\u76f4\u64ad")
    private val liveNameRegex = Regex("([A-Za-z0-9_\u4e00-\u9fa5\u00B7_.-]{2,40})\\s*\u6b63\u5728\u76f4\u64ad")
    private val idRegex = Regex("^[A-Za-z0-9_.-]{2,64}$")
    private val numericIdRegex = Regex("^\\d{3,20}$")

    fun normalize(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return ""
        }

        extractUrlFromAnyText(trimmed)?.let { return normalizeUrl(it) }

        val cleaned = cleanIdLikeInput(trimmed)
        if (cleaned.isEmpty()) {
            return ""
        }

        if (cleaned.startsWith("www.", ignoreCase = true)) {
            return normalizeUrl("https://$cleaned")
        }

        if (cleaned.startsWith("v.douyin.com/", ignoreCase = true) ||
            cleaned.startsWith("live.douyin.com/", ignoreCase = true) ||
            cleaned.startsWith("douyin.com/", ignoreCase = true)
        ) {
            return normalizeUrl("https://$cleaned")
        }

        return "https://live.douyin.com/$cleaned"
    }

    fun extractComparableKey(normalizedInput: String): String {
        return normalizedInput
            .trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
    }

    fun extractDisplayHint(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        bracketNameRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let {
            return it
        }

        liveNameRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.takeIf {
            it.isNotBlank() && !it.startsWith("http", ignoreCase = true)
        }?.let { return it }

        return null
    }

    fun extractIdHint(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || extractUrlFromAnyText(trimmed) != null) {
            return null
        }

        val cleaned = cleanIdLikeInput(trimmed)
            .trimEnd(
                '\u3002',
                '\uFF0C',
                ',',
                '.',
                ';',
                '\uFF1B',
                '!',
                '?',
                '\uFF01',
                '\uFF1F'
            )

        return cleaned.takeIf {
            it.isNotBlank() &&
                !it.contains('/') &&
                (idRegex.matches(it) || numericIdRegex.matches(it))
        }
    }

    fun extractLiveRoomId(rawInput: String): String? {
        val target = extractUrlFromAnyText(rawInput.trim()) ?: rawInput.trim()
        liveRoomUrlPatterns.forEach { regex ->
            regex.find(target)?.groupValues?.getOrNull(1)?.let { match ->
                if (match.isNotBlank()) {
                    return match
                }
            }
        }
        return null
    }

    private fun extractUrlFromAnyText(text: String): String? {
        return urlRegex.find(text)
            ?.value
            ?.trim()
            ?.trimEnd(
                '\u3002',
                '\uFF0C',
                ',',
                '.',
                ';',
                '\uFF1B',
                '!',
                '?',
                '\uFF01',
                '\uFF1F',
                ')',
                '\uFF09'
            )
    }

    private fun cleanIdLikeInput(raw: String): String {
        val candidate = raw.trim().removePrefix("@").trim()
        val prefixSplit = candidate.split(':', '\uFF1A', limit = 2)
        return if (prefixSplit.size == 2 && prefixSplit[1].isNotBlank()) {
            prefixSplit[1].trim().removePrefix("@").trim()
        } else {
            candidate
        }
    }

    private fun normalizeUrl(raw: String): String {
        return try {
            val uri = URI(raw)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return raw.trim()
            val path = uri.path ?: ""
            val query = uri.query?.let { "?$it" } ?: ""
            "$scheme://$host$path$query".trimEnd('/')
        } catch (_: Throwable) {
            raw.trim()
        }
    }
}


