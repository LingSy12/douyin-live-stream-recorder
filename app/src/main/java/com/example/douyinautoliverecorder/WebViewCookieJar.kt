package com.example.douyinautoliverecorder

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Bridges OkHttp to the system WebView [CookieManager] so the HTTP probe path and the
 * WebView probe path share one cookie store (ttwid, __ac_nonce, msToken, ...).
 *
 * Without a shared store the OkHttp probe is permanently cookieless, which is exactly the
 * fingerprint Douyin risk control flags. The WebView probe naturally fills this store; the
 * OkHttp fallback then reuses it.
 */
object WebViewCookieJar : CookieJar {

    fun ensureEnabled() {
        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) {
            return
        }
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        val urlString = url.toString()
        cookies.forEach { cookie ->
            runCatching { manager.setCookie(urlString, cookie.toString()) }
        }
        runCatching { manager.flush() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        val raw = runCatching { manager.getCookie(url.toString()) }.getOrNull()
        if (raw.isNullOrBlank()) {
            return emptyList()
        }
        return raw.split(';').mapNotNull { pair ->
            runCatching { Cookie.parse(url, pair.trim()) }.getOrNull()
        }
    }
}
