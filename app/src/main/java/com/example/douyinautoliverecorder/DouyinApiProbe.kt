package com.example.douyinautoliverecorder

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fast, direct-HTTP Douyin live probe using the web `enter` API + a [ABogus] signature.
 *
 * This is the API-first half of the hybrid detector: it is cheap (one JSON request) and safe to run
 * concurrently across many rooms, unlike the heavyweight [WebViewLiveProbe]. It shares the resolver's
 * OkHttp client (and therefore its [WebViewCookieJar], so the `ttwid` cookie obtained during WebView
 * warm-up is reused). On ANY uncertainty (network error, risk-control empty body, missing fields,
 * unexpected shape) it returns null so the caller can fall back to the WebView probe.
 */
class DouyinApiProbe(private val client: OkHttpClient) {

    /**
     * @return a confident [ProbeResult] (live with streams, or reliably offline), or null to signal
     *         "couldn't determine via API — fall back to the WebView probe".
     */
    suspend fun probe(
        webRid: String,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeResult? = withContext(Dispatchers.IO) {
        if (webRid.isBlank()) return@withContext null
        runCatching {
            // Param order must stay identical between the signed query and the sent query.
            val query = buildString {
                append("aid=6383")
                append("&app_name=douyin_web")
                append("&live_id=1")
                append("&device_platform=web")
                append("&language=zh-CN")
                append("&browser_language=zh-CN")
                append("&browser_platform=Win32")
                append("&browser_name=Chrome")
                append("&browser_version=116.0.0.0")
                append("&web_rid=").append(webRid)
                append("&msToken=")
            }
            val aBogus = ABogus.sign(query, USER_AGENT)
            // a_bogus (s4 alphabet) never contains '+', only '/','-','='; safe to append raw.
            val url = "https://live.douyin.com/webcast/room/web/enter/?$query&a_bogus=$aBogus"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://live.douyin.com/$webRid")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.d(TAG, "enter web_rid=$webRid http=${resp.code} -> fallback")
                    return@withContext null
                }
                resp.body?.string()
            }
            if (body.isNullOrBlank()) {
                Log.d(TAG, "enter web_rid=$webRid empty body (risk control?) -> fallback")
                return@withContext null
            }

            parseEnter(body, webRid, inputDisplay, inputDouyinId)
        }.getOrElse { error ->
            Log.d(TAG, "enter web_rid=$webRid error=${error.message} -> fallback")
            null
        }
    }

    private fun parseEnter(
        body: String,
        webRid: String,
        inputDisplay: String?,
        inputDouyinId: String?
    ): ProbeResult? {
        val data = JSONObject(body).optJSONObject("data") ?: return null
        val rooms = data.optJSONArray("data")
        if (rooms == null || rooms.length() == 0) {
            // Empty -> VR/unsupported/risk-control; let the WebView path decide.
            return null
        }
        val room = rooms.optJSONObject(0) ?: return null
        val status = room.optInt("status", -1)
        if (status == -1) return null

        val user = data.optJSONObject("user")
        val nickname = user?.optString("nickname").takeUnless { it.isNullOrBlank() }
        val avatar = user?.optJSONObject("avatar_thumb")
            ?.optJSONArray("url_list")?.optString(0).takeUnless { it.isNullOrBlank() }
        val resolvedRoomId = room.optString("id_str").takeUnless { it.isNullOrBlank() }
            ?: room.optLong("id").takeIf { it > 0 }?.toString()

        if (status != 2) {
            Log.d(TAG, "enter web_rid=$webRid status=$status -> offline (reliable)")
            return ProbeResult(
                isLive = false,
                streamUrls = emptyMap(),
                roomDisplayName = nickname ?: inputDisplay,
                douyinId = inputDouyinId ?: webRid,
                resolvedRoomId = resolvedRoomId,
                avatarUrl = avatar,
                message = "Offline or stream URL not found.",
                isReliable = true
            )
        }

        val streamMap = extractStreams(room.optJSONObject("stream_url"))
        if (streamMap.isEmpty()) {
            // Live but no parsable stream (e.g. PC-unsupported gameplay); fall back to WebView.
            Log.d(TAG, "enter web_rid=$webRid live but no streams -> fallback")
            return null
        }
        Log.d(TAG, "enter web_rid=$webRid status=2 live streams=${streamMap.keys}")
        return ProbeResult(
            isLive = true,
            streamUrls = streamMap,
            roomDisplayName = nickname ?: inputDisplay,
            douyinId = inputDouyinId ?: webRid,
            resolvedRoomId = resolvedRoomId,
            avatarUrl = avatar,
            message = null,
            isReliable = true
        )
    }

    private fun extractStreams(streamUrl: JSONObject?): Map<String, String> {
        if (streamUrl == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        // FLV: { "FULL_HD1": "http://.../...flv", ... }
        streamUrl.optJSONObject("flv_pull_url")?.let { flv ->
            val keys = flv.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = flv.optString(k)
                if (v.startsWith("http")) out[k.uppercase()] = v
            }
        }
        // HLS: { "FULL_HD1": "http://.../...m3u8", ... } -> prefix HLS_ so StreamSelector recognizes it
        streamUrl.optJSONObject("hls_pull_url_map")?.let { hls ->
            val keys = hls.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = hls.optString(k)
                if (v.startsWith("http")) out["HLS_${k.uppercase()}"] = v
            }
        }
        return out
    }

    companion object {
        private const val TAG = "DouyinApiProbe"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"

        /** Extracts the `web_rid` (the path segment after live.douyin.com/) from a normalized URL. */
        fun extractWebRid(normalized: String): String? {
            val marker = "live.douyin.com/"
            val idx = normalized.indexOf(marker)
            if (idx < 0) return null
            val rid = normalized.substring(idx + marker.length)
                .substringBefore('?')
                .substringBefore('/')
                .trim()
            return rid.takeIf { it.isNotBlank() && it.none { c -> c == ' ' } }
        }
    }
}
