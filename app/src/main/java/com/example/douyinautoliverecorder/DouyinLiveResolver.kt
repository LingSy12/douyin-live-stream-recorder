package com.example.douyinautoliverecorder

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

class DouyinLiveResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(WebViewCookieJar)
        .build(),
    private val webProbe: WebViewLiveProbe? = null
) {
    private data class ProbeAttempt(
        val sourceUrl: String,
        val finalUrl: String,
        val result: ProbeResult
    )

    suspend fun probe(input: String, enrichProfile: Boolean = true): ProbeResult = withContext(Dispatchers.IO) {
        val normalized = RoomInputNormalizer.normalize(input)
        val inputDisplay = RoomInputNormalizer.extractDisplayHint(input)
        val inputDouyinId = RoomInputNormalizer.extractIdHint(input)
        if (normalized.isBlank()) {
            return@withContext ProbeResult(
                isLive = false,
                streamUrls = emptyMap(),
                roomDisplayName = inputDisplay,
                douyinId = inputDouyinId,
                resolvedRoomId = null,
                avatarUrl = null,
                message = "Invalid room input",
                isReliable = false
            )
        }

        return@withContext runCatching {
            var bestAttempt = probeSingleUrl(
                sourceUrl = normalized,
                inputDisplay = inputDisplay,
                inputDouyinId = inputDouyinId
            )

            if (shouldRetryProbe(bestAttempt.result)) {
                for (fallbackUrl in buildFallbackProbeUrls(normalized, inputDouyinId, bestAttempt.result)) {
                    val attempt = probeSingleUrl(
                        sourceUrl = fallbackUrl,
                        inputDisplay = inputDisplay,
                        inputDouyinId = inputDouyinId
                    )
                    bestAttempt = selectBetterAttempt(bestAttempt, attempt)
                    if (bestAttempt.result.isLive && bestAttempt.result.streamUrls.isNotEmpty()) {
                        break
                    }
                }
            }

            bestAttempt = maybeConfirmWeakAttempt(
                current = bestAttempt,
                inputDisplay = inputDisplay,
                inputDouyinId = inputDouyinId
            )
            val selected = bestAttempt.result
            val enriched = if (enrichProfile && !selected.blockedByVerification) {
                maybeEnrichProfile(selected, inputDisplay, inputDouyinId)
            } else {
                selected
            }
            logProbe(input, normalized, bestAttempt.finalUrl, enriched)
            enriched
        }.getOrElse { error ->
            val failed = ProbeResult(
                isLive = false,
                streamUrls = emptyMap(),
                roomDisplayName = inputDisplay,
                douyinId = inputDouyinId,
                resolvedRoomId = extractRoomId(normalized),
                avatarUrl = null,
                message = error.message ?: "Probe failed",
                isReliable = false
            )
            logProbe(input, normalized, normalized, failed)
            failed
        }
    }

    private suspend fun probeSingleUrl(
        sourceUrl: String,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeAttempt {
        return runCatching {
            val page = fetchPage(sourceUrl)
            when {
                page.blockedByVerification -> ProbeAttempt(
                    sourceUrl = sourceUrl,
                    finalUrl = page.finalUrl,
                    result = ProbeResult(
                        isLive = false,
                        streamUrls = emptyMap(),
                        roomDisplayName = inputDisplay,
                        douyinId = inputDouyinId,
                        resolvedRoomId = extractRoomId(page.finalUrl) ?: extractRoomId(sourceUrl),
                        avatarUrl = null,
                        message = "Blocked by verification",
                        isReliable = false,
                        blockedByVerification = true
                    )
                )

                page.httpErrorMessage != null -> ProbeAttempt(
                    sourceUrl = sourceUrl,
                    finalUrl = page.finalUrl,
                    result = ProbeResult(
                        isLive = false,
                        streamUrls = emptyMap(),
                        roomDisplayName = inputDisplay,
                        douyinId = inputDouyinId,
                        resolvedRoomId = extractRoomId(page.finalUrl) ?: extractRoomId(sourceUrl),
                        avatarUrl = null,
                        message = page.httpErrorMessage,
                        isReliable = false
                    )
                )

                else -> ProbeAttempt(
                    sourceUrl = sourceUrl,
                    finalUrl = page.finalUrl,
                    result = parseProbeResult(
                        sourceUrl = sourceUrl,
                        finalUrl = page.finalUrl,
                        html = page.text,
                        inputDisplay = inputDisplay,
                        inputDouyinId = inputDouyinId
                    )
                )
            }
        }.getOrElse { error ->
            ProbeAttempt(
                sourceUrl = sourceUrl,
                finalUrl = sourceUrl,
                result = ProbeResult(
                    isLive = false,
                    streamUrls = emptyMap(),
                    roomDisplayName = inputDisplay,
                    douyinId = inputDouyinId,
                    resolvedRoomId = extractRoomId(sourceUrl),
                    avatarUrl = null,
                    message = error.message ?: "Probe failed",
                    isReliable = false
                )
            )
        }
    }

    private data class FetchedPage(
        val text: String,
        val finalUrl: String,
        val blockedByVerification: Boolean,
        val httpErrorMessage: String?
    )

    /**
     * Fetches a room page, preferring the WebView probe (which runs Douyin's JS challenge) and
     * falling back to a plain HTTP request only when the WebView produced nothing usable.
     */
    private suspend fun fetchPage(sourceUrl: String): FetchedPage {
        webProbe?.let { probe ->
            val page = runCatching { probe.fetch(sourceUrl) }.getOrNull()
            if (page != null) {
                val finalUrl = page.finalUrl.ifBlank { sourceUrl }
                if (page.blockedByVerification) {
                    return FetchedPage("", finalUrl, blockedByVerification = true, httpErrorMessage = null)
                }
                if (page.ok && page.rawText.isNotBlank()) {
                    return FetchedPage(page.rawText, finalUrl, blockedByVerification = false, httpErrorMessage = null)
                }
            }
        }
        return fetchPageViaHttp(sourceUrl)
    }

    private fun fetchPageViaHttp(sourceUrl: String): FetchedPage {
        return client.newCall(buildRequest(sourceUrl)).execute().use { response ->
            val finalUrl = response.request.url.toString()
            if (!response.isSuccessful) {
                return@use FetchedPage("", finalUrl, blockedByVerification = false, httpErrorMessage = "HTTP ${response.code}")
            }
            val html = response.body?.string().orEmpty()
            FetchedPage(
                text = html,
                finalUrl = finalUrl,
                blockedByVerification = looksLikeVerification(html, finalUrl),
                httpErrorMessage = null
            )
        }
    }

    private fun buildRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .header("Pragma", "no-cache")
            .build()
    }

    private fun parseProbeResult(
        sourceUrl: String,
        finalUrl: String,
        html: String,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeResult {
        val candidates = buildCandidateTexts(html)
        val profileTexts = buildProfileTexts(candidates)
        val streamMap = extractStreamMap(candidates)
        val liveStatus = detectLiveStatus(candidates)
        val offlineStatus = detectOfflineStatus(candidates)
        val resolvedRoomId =
            extractRoomId(finalUrl) ?: extractRoomId(sourceUrl) ?: extractRoomIdFromText(candidates)
        val douyinId = extractDouyinId(profileTexts, candidates) ?: inputDouyinId ?: resolvedRoomId
        val displayName = extractDisplayName(profileTexts, candidates) ?: inputDisplay
        val avatarUrl = extractAvatarUrl(profileTexts, candidates)
        val isLive = liveStatus || (streamMap.isNotEmpty() && !offlineStatus)
        val isReliable = liveStatus || offlineStatus || streamMap.isNotEmpty()

        return ProbeResult(
            isLive = isLive,
            streamUrls = if (isLive) streamMap else emptyMap(),
            roomDisplayName = displayName,
            douyinId = douyinId,
            resolvedRoomId = resolvedRoomId,
            avatarUrl = avatarUrl,
            message = when {
                isLive && streamMap.isEmpty() -> "Live detected, but stream URL parse failed."
                !isReliable -> "Live status could not be confirmed."
                !isLive -> "Offline or stream URL not found."
                else -> null
            },
            isReliable = isReliable
        )
    }

    private suspend fun maybeEnrichProfile(
        primary: ProbeResult,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeResult {
        val roomId = primary.resolvedRoomId ?: return primary
        if (!needsProfileEnrichment(primary)) {
            return primary
        }

        val fallbackUrl = "https://live.douyin.com/$roomId"
        return runCatching {
            val page = fetchPage(fallbackUrl)
            if (page.blockedByVerification || page.text.isBlank()) {
                return@runCatching primary
            }
            val fallback = parseProbeResult(
                sourceUrl = fallbackUrl,
                finalUrl = page.finalUrl,
                html = page.text,
                inputDisplay = inputDisplay,
                inputDouyinId = inputDouyinId
            )
            mergeProbeResults(primary, fallback)
        }.getOrDefault(primary)
    }

    private fun needsProfileEnrichment(result: ProbeResult): Boolean {
        val weakDouyinId = result.douyinId.isNullOrBlank() || result.douyinId == result.resolvedRoomId
        return result.avatarUrl.isNullOrBlank() || weakDouyinId || result.roomDisplayName.isNullOrBlank()
    }

    private fun mergeProbeResults(primary: ProbeResult, fallback: ProbeResult): ProbeResult {
        val mergedDouyinId = when {
            primary.douyinId.isNullOrBlank() -> fallback.douyinId
            primary.douyinId == primary.resolvedRoomId && !fallback.douyinId.isNullOrBlank() -> fallback.douyinId
            else -> primary.douyinId
        }

        return primary.copy(
            isLive = primary.isLive || fallback.isLive,
            streamUrls = if (primary.streamUrls.isNotEmpty()) primary.streamUrls else fallback.streamUrls,
            roomDisplayName = primary.roomDisplayName ?: fallback.roomDisplayName,
            douyinId = mergedDouyinId,
            resolvedRoomId = primary.resolvedRoomId ?: fallback.resolvedRoomId,
            avatarUrl = primary.avatarUrl ?: fallback.avatarUrl,
            message = when {
                primary.isReliable || primary.isLive -> primary.message
                fallback.isReliable || fallback.isLive -> fallback.message
                else -> primary.message ?: fallback.message
            },
            isReliable = primary.isReliable || fallback.isReliable
        )
    }

    private fun logProbe(input: String, normalized: String, finalUrl: String, result: ProbeResult) {
        Log.d(
            TAG,
            "probe input=$input normalized=$normalized finalUrl=$finalUrl room=${result.resolvedRoomId} name=${result.roomDisplayName} douyinId=${result.douyinId} avatar=${result.avatarUrl} live=${result.isLive} reliable=${result.isReliable} streams=${result.streamUrls.keys}"
        )
    }

    private fun shouldRetryProbe(result: ProbeResult): Boolean {
        if (result.blockedByVerification) {
            return false
        }
        return !result.isReliable || !result.isLive || result.streamUrls.isEmpty()
    }

    private suspend fun maybeConfirmWeakAttempt(
        current: ProbeAttempt,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeAttempt {
        if (current.result.blockedByVerification) {
            return current
        }
        if (current.result.isLive && current.result.streamUrls.isNotEmpty()) {
            return current
        }

        val retryUrl = current.finalUrl.takeIf { it.isNotBlank() } ?: current.sourceUrl
        if (retryUrl.isBlank()) {
            return current
        }

        val retried = probeSingleUrl(
            sourceUrl = retryUrl,
            inputDisplay = inputDisplay,
            inputDouyinId = inputDouyinId
        )
        return selectBetterAttempt(current, retried)
    }

    private fun buildFallbackProbeUrls(
        normalized: String,
        inputDouyinId: String?,
        primary: ProbeResult
    ): List<String> {
        val result = LinkedHashSet<String>()
        val normalizedKey = RoomInputNormalizer.extractComparableKey(normalized)

        fun addCandidate(raw: String?) {
            val clean = raw?.takeIf { it.isNotBlank() }?.let(::cleanDouyinId).orEmpty()
            if (clean.isBlank()) {
                return
            }
            val url = "https://live.douyin.com/$clean"
            if (RoomInputNormalizer.extractComparableKey(url) != normalizedKey) {
                result += url
            }
        }

        addCandidate(inputDouyinId)
        addCandidate(RoomInputNormalizer.extractLiveRoomId(normalized))
        addCandidate(primary.douyinId)
        addCandidate(primary.resolvedRoomId)
        return result.toList()
    }

    private fun selectBetterAttempt(current: ProbeAttempt, candidate: ProbeAttempt): ProbeAttempt {
        val currentScore = scoreProbeResult(current.result)
        val candidateScore = scoreProbeResult(candidate.result)
        return if (candidateScore > currentScore) candidate else current
    }

    private fun scoreProbeResult(result: ProbeResult): Int {
        var score = 0
        if (result.isLive) {
            score += 400
        }
        if (result.streamUrls.isNotEmpty()) {
            score += 200
        }
        if (!result.isLive && result.isReliable) {
            score += 120
        }
        if (result.isReliable) {
            score += 80
        }
        if (!result.roomDisplayName.isNullOrBlank()) {
            score += 10
        }
        if (!result.douyinId.isNullOrBlank()) {
            score += 6
        }
        if (!result.avatarUrl.isNullOrBlank()) {
            score += 4
        }
        if (result.message?.startsWith("HTTP") == true) {
            score -= 20
        }
        return score
    }

    private fun buildCandidateTexts(html: String): List<String> {
        val result = LinkedHashSet<String>()
        val decodedHtml = decodeRenderData(html)
        result += html
        result += normalizeCandidateText(html)
        result += decodedHtml
        result += normalizeCandidateText(decodedHtml)
        extractRenderDataCandidates(html).forEach { candidate ->
            result += candidate
            result += normalizeCandidateText(candidate)
        }
        return result.filter { it.isNotBlank() }
    }

    private fun extractStreamMap(candidates: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        candidates.forEach { text ->
            parseMapByKey(text, "flv_pull_url").forEach { (quality, url) ->
                sanitizeCandidateUrl(url)?.let { result.putIfAbsent(quality.uppercase(), it) }
            }

            parseMapByKey(text, "hls_pull_url_map").forEach { (quality, url) ->
                sanitizeCandidateUrl(url)?.let { result.putIfAbsent("HLS_${quality.uppercase()}", it) }
            }
        }

        if (result.isEmpty()) {
            extractDirectStreamUrls(candidates).forEach { (quality, url) ->
                result.putIfAbsent(quality, url)
            }
        }

        return result
    }

    private fun parseMapByKey(text: String, key: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        val objectRegex = Regex(
            "[\"']?$key[\"']?\\s*:\\s*\\{(.*?)\\}",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val entryRegex = Regex("[\"']([^\"']+)[\"']\\s*:\\s*[\"']([^\"']+)[\"']")

        objectRegex.findAll(text).forEach { mapMatch ->
            val block = mapMatch.groupValues.getOrNull(1).orEmpty()
            entryRegex.findAll(block).forEach { entry ->
                val quality = entry.groupValues[1]
                val url = decodeEscapedValue(entry.groupValues[2])
                if (quality.isNotBlank()) {
                    result.putIfAbsent(quality, url)
                }
            }
        }

        val stringifiedRegex = Regex(
            "[\"']?$key[\"']?\\s*:\\s*[\"'](\\{.*?\\})[\"']",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        stringifiedRegex.findAll(text).forEach { match ->
            val rawBlock = decodeEscapedValue(match.groupValues.getOrNull(1).orEmpty())
            entryRegex.findAll(rawBlock).forEach { entry ->
                val quality = entry.groupValues[1]
                val url = decodeEscapedValue(entry.groupValues[2])
                if (quality.isNotBlank()) {
                    result.putIfAbsent(quality, url)
                }
            }
        }

        return result
    }

    private fun extractDirectStreamUrls(candidates: List<String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val urlRegex = Regex("https?:\\\\?/\\\\?/[^\"'\\s<>]+", RegexOption.IGNORE_CASE)

        candidates.forEach { text ->
            urlRegex.findAll(text).forEach { match ->
                val decoded = decodeEscapedValue(match.value)
                val lowered = decoded.lowercase()
                if (lowered.contains(".flv") || lowered.contains(".m3u8") || lowered.contains("pull-ts")) {
                    sanitizeCandidateUrl(decoded)?.let { sanitized ->
                        val key = buildFallbackQualityKey(sanitized, result.size)
                        result.putIfAbsent(key, sanitized)
                    }
                }
            }
        }

        return result
    }

    private fun buildFallbackQualityKey(url: String, index: Int): String {
        val upper = url.uppercase()
        val quality = when {
            "FULL_HD1" in upper -> "FULL_HD1"
            "HD1" in upper -> "HD1"
            "SD1" in upper -> "SD1"
            "ORIGIN" in upper -> "ORIGIN"
            else -> "AUTO_${index + 1}"
        }

        return if (url.lowercase().contains(".m3u8")) {
            "HLS_$quality"
        } else {
            quality
        }
    }

    private fun detectLiveStatus(candidates: List<String>): Boolean {
        val liveStatusRegex = Regex("[\"']live_status[\"']\\s*:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        val roomStatusRegex = Regex("[\"']room_status[\"']\\s*:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        val isLiveRegex = Regex("[\"']is_live[\"']\\s*:\\s*(true|1)", RegexOption.IGNORE_CASE)

        candidates.forEach { text ->
            liveStatusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { value ->
                if (value == 2) {
                    return true
                }
            }
            roomStatusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { value ->
                if (value == 2) {
                    return true
                }
            }
            if (isLiveRegex.containsMatchIn(text)) {
                return true
            }
        }

        return false
    }

    private fun detectOfflineStatus(candidates: List<String>): Boolean {
        val liveStatusRegex = Regex("[\"']live_status[\"']\\s*:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        val roomStatusRegex = Regex("[\"']room_status[\"']\\s*:\\s*([0-9]+)", RegexOption.IGNORE_CASE)
        val offlineTexts = listOf(
            "\u6682\u672a\u5f00\u64ad",
            "\u672a\u5f00\u64ad",
            "\u76f4\u64ad\u5df2\u7ed3\u675f",
            "\u4e3b\u64ad\u6682\u65f6\u79bb\u5f00",
            "\u4f11\u606f\u4e2d"
        )

        var sawExplicitOffline = false
        var sawExplicitLive = false

        candidates.forEach { text ->
            liveStatusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { value ->
                if (value == 2) sawExplicitLive = true else sawExplicitOffline = true
            }
            roomStatusRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { value ->
                if (value == 2) sawExplicitLive = true else sawExplicitOffline = true
            }
            if (offlineTexts.any { marker -> marker in text }) {
                sawExplicitOffline = true
            }
        }

        return sawExplicitOffline && !sawExplicitLive
    }

    private fun extractDisplayName(profileTexts: List<String>, candidates: List<String>): String? {
        val searchTexts = profileTexts + candidates
        val nicknamePatterns = listOf(
            "[\"']nickname[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']nickName[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']room_owner_name[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']owner_name[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']nick_name[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']anchor_name[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']display_name[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']displayName[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
        )
        val titlePatterns = listOf(
            "[\"']room_title[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']title[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
        )

        val nickname = extractWithRegex(searchTexts, nicknamePatterns)
        if (!nickname.isNullOrBlank()) {
            sanitizeDisplayNameCandidate(nickname)?.let { return it }
        }

        extractWithRegex(searchTexts, titlePatterns)
            ?.let(::sanitizeDisplayNameCandidate)
            ?.let { return it }

        extractMetaContent(candidates, listOf("og:title", "twitter:title"))
            ?.let(::sanitizeDisplayNameCandidate)
            ?.let { return it }

        extractHtmlTitle(candidates)
            ?.let(::sanitizeDisplayNameCandidate)
            ?.let { return it }

        return null
    }

    private fun extractDouyinId(profileTexts: List<String>, candidates: List<String>): String? {
        val searchTexts = profileTexts + candidates
        val primaryPatterns = listOf(
            "[\"']display_id[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']displayId[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']display_id_str[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']displayIdStr[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']douyin_id[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']douyinId[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']unique_id[\"']\\s*:\\s*[\"']((?!stream-)[^\"']+)[\"']",
            "[\"']uniqueId[\"']\\s*:\\s*[\"']((?!stream-)[^\"']+)[\"']",
            "\u6296\u97f3\u53f7[:\uFF1A]\\s*([A-Za-z0-9_.-]+)"
        )
        val fallbackPatterns = listOf(
            "[\"']short_id[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']shortId[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
        )

        return extractWithRegex(searchTexts, primaryPatterns)
            ?.let(::cleanDouyinId)
            ?.takeIf { it.isNotBlank() }
            ?: extractWithRegex(searchTexts, fallbackPatterns)
                ?.let(::cleanDouyinId)
                ?.takeIf { it.isNotBlank() }
    }

    private fun extractAvatarUrl(profileTexts: List<String>, candidates: List<String>): String? {
        val ownerTexts = (profileTexts + buildOwnerScopedAvatarTexts(candidates)).distinct()
        val searchTexts = (ownerTexts + candidates).distinct()
        val listPattern = Regex(
            "[\"'](?:avatar(?:_thumb|_medium|_large|_168x168|_300x300|_720x720)?|avatarThumb|avatarMedium|avatarLarge|head_url|headUrl|portrait)[\"']\\s*:\\s*\\{.*?[\"'](?:url_list|urlList)[\"']\\s*:\\s*\\[\\s*[\"']([^\"']+)[\"']",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        searchTexts.forEach { text ->
            listPattern.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
                sanitizeCandidateUrl(decodeEscapedValue(raw))?.let { return it }
            }
        }

        val simplePatterns = listOf(
            "[\"']avatar_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']avatarUrl[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']head_url[\"']\\s*:\\s*[\"']([^\"']+)[\"']",
            "[\"']headUrl[\"']\\s*:\\s*[\"']([^\"']+)[\"']"
        )

        extractWithRegex(searchTexts, simplePatterns)
            ?.let(::sanitizeCandidateUrl)
            ?.let { return it }

        return null
    }

    private fun cleanDouyinId(raw: String): String {
        return raw.trim()
            .removePrefix("@")
            .substringBefore("?")
            .substringBefore("&")
            .substringBefore("/")
            .trim()
            .takeIf {
                it.isNotBlank() &&
                    !it.startsWith("stream-", ignoreCase = true) &&
                    !it.startsWith("http", ignoreCase = true)
            }
            .orEmpty()
    }

    private fun extractMetaContent(candidates: List<String>, keys: List<String>): String? {
        keys.forEach { key ->
            val escaped = Regex.escape(key)
            val patterns = listOf(
                "<meta[^>]+(?:property|name)=[\"']$escaped[\"'][^>]+content=[\"']([^\"']+)[\"'][^>]*>",
                "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']$escaped[\"'][^>]*>"
            )
            patterns.forEach { pattern ->
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                candidates.forEach { text ->
                    regex.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
                        val decoded = decodeEscapedValue(raw)
                        if (decoded.isNotBlank()) {
                            return decoded
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractHtmlTitle(candidates: List<String>): String? {
        val regex = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE)
        candidates.forEach { text ->
            regex.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
                val decoded = decodeEscapedValue(raw)
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        }
        return null
    }

    private fun cleanDisplayName(raw: String): String {
        return repairPotentialMojibake(raw)
            .substringBefore(" - ")
            .substringBefore("|")
            .replace("#\u5728\u6296\u97f3\uff0c\u8bb0\u5f55\u7f8e\u597d\u751f\u6d3b#", "")
            .replace("\u6b63\u5728\u76f4\u64ad", "")
            .replace("\u7684\u76f4\u64ad\u95f4", "")
            .replace("\u76f4\u64ad\u95f4", "")
            .replace("\u6296\u97f3", "")
            .trim('\u3010', '\u3011', '-', '|', '_', ' ')
            .trim()
    }

    private fun sanitizeDisplayNameCandidate(raw: String): String? {
        val cleaned = cleanDisplayName(raw)
        if (cleaned.isBlank()) {
            return null
        }

        if (VERIFICATION_MARKERS.any { marker -> cleaned.contains(marker) }) {
            return null
        }
        if (cleaned.equals("undefined", ignoreCase = true) || cleaned.startsWith("$")) {
            return null
        }
        return cleaned
    }

    private fun repairPotentialMojibake(value: String): String {
        val looksMojibake = value.any { ch -> ch.code in 0x00C0..0x00FF }
        if (!looksMojibake) {
            return value
        }

        return runCatching {
            val repaired = value.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
            val repairedChineseCount = repaired.count { it in '\u4e00'..'\u9fff' }
            val originalChineseCount = value.count { it in '\u4e00'..'\u9fff' }
            if (repairedChineseCount >= originalChineseCount) repaired else value
        }.getOrDefault(value)
    }

    private fun buildProfileTexts(candidates: List<String>): List<String> {
        val keys = listOf(
            "owner_info",
            "ownerInfo",
            "room_owner",
            "owner",
            "anchor_info",
            "anchorInfo",
            "anchor",
            "author_info",
            "authorInfo",
            "author",
            "user_info",
            "userInfo",
            "user"
        )
        val result = LinkedHashSet<String>()

        candidates.forEach { text ->
            keys.forEach { key ->
                extractNamedObjectBlocks(text, key).forEach { block ->
                    if (looksLikeProfileBlock(block)) {
                        result += block
                    }
                }
            }
        }

        return result.toList()
    }

    private fun buildOwnerScopedAvatarTexts(candidates: List<String>): List<String> {
        val regex = Regex(
            "(?:owner|anchor|author|user_info|userInfo)[^\\{]{0,80}\\{.*?(?:avatar|head_url|headUrl|portrait).*?\\}",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val result = LinkedHashSet<String>()
        candidates.forEach { text ->
            regex.findAll(text).forEach { match ->
                result += match.value
            }
        }
        return result.toList()
    }

    private fun extractNamedObjectBlocks(text: String, key: String): List<String> {
        val result = mutableListOf<String>()
        val regex = Regex(
            "(?:[\"']${Regex.escape(key)}[\"']|\\b${Regex.escape(key)}\\b)\\s*:\\s*\\{",
            RegexOption.IGNORE_CASE
        )

        regex.findAll(text).forEach { match ->
            val relativeIndex = match.value.lastIndexOf('{')
            if (relativeIndex < 0) {
                return@forEach
            }
            val openBraceIndex = match.range.first + relativeIndex
            extractBalancedObject(text, openBraceIndex)?.let(result::add)
        }

        return result
    }

    private fun extractBalancedObject(text: String, openBraceIndex: Int): String? {
        if (openBraceIndex !in text.indices || text[openBraceIndex] != '{') {
            return null
        }

        var depth = 0
        var quote: Char? = null
        var escaped = false

        for (index in openBraceIndex until text.length) {
            val char = text[index]

            if (quote != null) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }

            if (char == '"' || char == '\'') {
                quote = char
                continue
            }

            if (char == '{') {
                depth += 1
            } else if (char == '}') {
                depth -= 1
                if (depth == 0) {
                    return text.substring(openBraceIndex, index + 1)
                }
            }
        }

        return null
    }

    private fun looksLikeProfileBlock(text: String): Boolean {
        val lowered = text.lowercase()
        return lowered.contains("nickname") ||
            lowered.contains("display_id") ||
            lowered.contains("displayid") ||
            lowered.contains("douyin_id") ||
            lowered.contains("douyinid") ||
            lowered.contains("unique_id") ||
            lowered.contains("uniqueid") ||
            lowered.contains("avatar") ||
            lowered.contains("head_url") ||
            lowered.contains("headurl") ||
            lowered.contains("portrait") ||
            text.contains("\u6296\u97f3\u53f7")
    }

    private fun extractWithRegex(candidates: List<String>, regexPatterns: List<String>): String? {
        regexPatterns.forEach { pattern ->
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            candidates.forEach { text ->
                regex.find(text)?.groupValues?.getOrNull(1)?.let { raw ->
                    val decoded = decodeEscapedValue(raw)
                    if (decoded.isNotBlank() && !decoded.startsWith("$") && !decoded.equals("undefined", ignoreCase = true)) {
                        return decoded
                    }
                }
            }
        }
        return null
    }

    private fun extractRoomIdFromText(candidates: List<String>): String? {
        val patterns = listOf(
            Regex("live\\.douyin\\.com/([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE),
            Regex("webcast\\.amemv\\.com/douyin/webcast/reflow/([0-9]{6,})", RegexOption.IGNORE_CASE),
            Regex("[?&]room_id=([0-9]{6,})", RegexOption.IGNORE_CASE),
            Regex("[\"']web_rid[\"']\\s*:\\s*[\"']([A-Za-z0-9_]+)[\"']", RegexOption.IGNORE_CASE),
            Regex("[\"']room_id[\"']\\s*:\\s*[\"']?([0-9]{6,})[\"']?", RegexOption.IGNORE_CASE),
            Regex("[\"']idStr[\"']\\s*:\\s*[\"']([0-9]{6,})[\"']", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { regex ->
            candidates.forEach { text ->
                regex.find(text)?.groupValues?.getOrNull(1)?.let { match ->
                    if (match.isNotBlank()) {
                        return match
                    }
                }
            }
        }

        return null
    }

    private fun extractRenderDataCandidates(html: String): List<String> {
        val regex = Regex(
            "<script[^>]*id=\\\"RENDER_DATA\\\"[^>]*>(.*?)</script>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        return regex.findAll(html)
            .mapNotNull { match ->
                val body = match.groupValues.getOrNull(1)?.trim().orEmpty()
                if (body.isEmpty()) {
                    null
                } else {
                    decodeRenderData(body)
                }
            }
            .toList()
    }

    private fun decodeRenderData(raw: String): String {
        val htmlDecoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        val unwrapped = htmlDecoded.removePrefix("\"").removeSuffix("\"")
        val step1 = unwrapped.replace("\\\\u002F", "/")
            .replace("\\\\u0026", "&")
            .replace("\\\\u003D", "=")
            .replace("\\/", "/")

        return runCatching {
            URLDecoder.decode(step1, StandardCharsets.UTF_8.name())
        }.getOrDefault(step1)
    }

    private fun normalizeCandidateText(value: String): String {
        return decodeUnicodeEscapes(value)
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
    }

    private fun decodeEscapedValue(value: String): String {
        var text = normalizeCandidateText(value)

        if (
            text.contains("%2F", ignoreCase = true) ||
            text.contains("%3A", ignoreCase = true) ||
            text.contains("%26", ignoreCase = true) ||
            text.contains("%3D", ignoreCase = true)
        ) {
            text = runCatching {
                URLDecoder.decode(text, StandardCharsets.UTF_8.name())
            }.getOrDefault(text)
        }

        return text
    }

    private fun decodeUnicodeEscapes(value: String): String {
        val output = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 5 < value.length && value[index + 1] == 'u') {
                val hex = value.substring(index + 2, index + 6)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    output.append(decoded.toChar())
                    index += 6
                    continue
                }
            }
            output.append(value[index])
            index += 1
        }
        return output.toString()
    }

    private fun sanitizeCandidateUrl(rawUrl: String): String? {
        var trimmed = rawUrl.trim()
            .trim('"', '\'', '\\')

        if (trimmed.startsWith("//")) {
            trimmed = "https:$trimmed"
        }
        if (!trimmed.startsWith("http", ignoreCase = true)) {
            return null
        }
        return trimmed
    }

    private fun extractRoomId(url: String): String? {
        val patterns = listOf(
            Regex("live\\.douyin\\.com/([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE),
            Regex("webcast\\.amemv\\.com/douyin/webcast/reflow/([0-9]{6,})", RegexOption.IGNORE_CASE),
            Regex("[?&]room_id=([0-9]{6,})", RegexOption.IGNORE_CASE)
        )
        patterns.forEach { regex ->
            regex.find(url)?.groupValues?.getOrNull(1)?.let { match ->
                if (match.isNotBlank()) {
                    return match
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DouyinLiveResolver"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        // Risk-control / human-verification page markers. When Douyin serves one of these
        // instead of the room page, the probe must report blockedByVerification rather than offline.
        val VERIFICATION_MARKERS: List<String> = listOf(
            "验证码",
            "安全验证",
            "人机验证",
            "滑块验证",
            "验证中心",
            "请完成验证",
            "访问过于频繁"
        )

        private val VERIFICATION_URL_TOKENS = listOf("/verify", "captcha", "secsdk", "/security/")

        /**
         * True when [text]/[finalUrl] look like a risk-control verification page (slider CAPTCHA,
         * "访问过于频繁", or the bare acrawler interstitial) instead of a real room page.
         */
        fun looksLikeVerification(text: String, finalUrl: String = ""): Boolean {
            val loweredUrl = finalUrl.lowercase()
            if (VERIFICATION_URL_TOKENS.any { it in loweredUrl }) {
                return true
            }
            if (VERIFICATION_MARKERS.any { it in text }) {
                return true
            }
            // The acrawler interstitial is a tiny page that ships the anti-crawler script and no room data.
            val lowered = text.lowercase()
            val hasAcrawler = "byted_acrawler" in lowered || "__ac_nonce" in lowered
            val hasRoomData = "live_status" in lowered ||
                "flv_pull_url" in lowered ||
                "render_data" in lowered
            return hasAcrawler && !hasRoomData && text.length < 20_000
        }
    }
}












