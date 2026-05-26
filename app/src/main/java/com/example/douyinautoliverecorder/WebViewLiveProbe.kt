package com.example.douyinautoliverecorder

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

data class WebProbePage(
    val rawText: String,
    val finalUrl: String,
    val blockedByVerification: Boolean,
    val ok: Boolean,
    val message: String? = null
)

/**
 * Loads a Douyin room page in a hidden, reused WebView so the page's own JavaScript runs the
 * `__ac_signature` anti-crawler challenge. The probe then reads RENDER_DATA / __INIT_PROPS__ and
 * any captured `webcast/room/web/enter` response, and hands the text to the existing regex parser.
 *
 * This is the only path that can clear Douyin's JS challenge — a plain HTTP request never can.
 * All WebView calls are marshalled onto the main thread; a mutex keeps probes strictly sequential
 * so a single WebView can be reused (also keeps request volume gentle).
 */
class WebViewLiveProbe(context: Context) {

    private val webContext = context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeMutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var scriptHandler: ScriptHandler? = null

    @Volatile
    private var destroyed = false

    /** Loads the Douyin homepage once so the shared cookie store gets `ttwid` / `__ac_*` populated. */
    suspend fun warmUp() {
        if (destroyed) {
            return
        }
        runCatching {
            probeMutex.withLock {
                withContext(Dispatchers.Main) {
                    withTimeoutOrNull(WARM_UP_TIMEOUT_MS) { awaitWarmUp() }
                }
            }
        }.onFailure { Log.w(TAG, "warmUp failed: ${it.message}") }
    }

    suspend fun fetch(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): WebProbePage {
        if (destroyed) {
            return WebProbePage("", url, blockedByVerification = false, ok = false, message = "probe destroyed")
        }
        return probeMutex.withLock {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(timeoutMs) { awaitPage(url, timeoutMs) }
                    ?: WebProbePage("", url, blockedByVerification = false, ok = false, message = "WebView probe timed out")
            }
        }
    }

    fun destroy() {
        destroyed = true
        mainHandler.post {
            val view = webView ?: return@post
            webView = null
            scriptHandler = null
            runCatching {
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
            }.onFailure { Log.w(TAG, "destroy failed: ${it.message}") }
        }
    }

    private suspend fun awaitWarmUp() = suspendCancellableCoroutine<Unit> { cont ->
        val view = ensureWebView()
        if (view == null) {
            if (cont.isActive) cont.resume(Unit)
            return@suspendCancellableCoroutine
        }
        view.onResume()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                mainHandler.postDelayed({ if (cont.isActive) cont.resume(Unit) }, WARM_UP_SETTLE_MS)
            }

            override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame && cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
        cont.invokeOnCancellation {
            mainHandler.post { runCatching { view.stopLoading() } }
        }
        runCatching { view.loadUrl(WARM_UP_URL) }
            .onFailure { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun awaitPage(url: String, timeoutMs: Long) =
        suspendCancellableCoroutine<WebProbePage> { cont ->
            val view = ensureWebView()
            if (view == null) {
                if (cont.isActive) {
                    cont.resume(WebProbePage("", url, blockedByVerification = false, ok = false, message = "WebView unavailable"))
                }
                return@suspendCancellableCoroutine
            }

            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var finished = false
            var lastPage: WebProbePage? = null
            var pollRunnable: Runnable? = null

            fun finish(page: WebProbePage) {
                if (finished) {
                    return
                }
                finished = true
                pollRunnable?.let { mainHandler.removeCallbacks(it) }
                runCatching {
                    view.loadUrl(BLANK_URL)
                    view.onPause()
                }
                if (cont.isActive) {
                    cont.resume(page)
                }
            }

            fun poll() {
                if (finished) {
                    return
                }
                runCatching {
                    view.evaluateJavascript(COLLECTOR_SCRIPT) { raw ->
                        if (finished) {
                            return@evaluateJavascript
                        }
                        val page = interpret(raw, url)
                        if (page != null) {
                            lastPage = page
                        }
                        when {
                            page == null -> Unit
                            page.blockedByVerification -> finish(page)
                            page.ok -> finish(page)
                            else -> Unit
                        }
                        if (!finished) {
                            if (SystemClock.uptimeMillis() >= deadline) {
                                finish(lastPage ?: WebProbePage("", url, blockedByVerification = false, ok = false, message = "no room data"))
                            } else {
                                pollRunnable?.let { mainHandler.postDelayed(it, POLL_INTERVAL_MS) }
                            }
                        }
                    }
                }.onFailure {
                    if (!finished) {
                        pollRunnable?.let { mainHandler.postDelayed(it, POLL_INTERVAL_MS) }
                    }
                }
            }

            pollRunnable = Runnable { poll() }

            view.onResume()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, finishedUrl: String) {
                    mainHandler.postDelayed(pollRunnable, FIRST_POLL_DELAY_MS)
                }

                override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame && !finished) {
                        finish(WebProbePage("", url, blockedByVerification = false, ok = false, message = "page error ${err.errorCode}"))
                    }
                }
            }

            cont.invokeOnCancellation {
                mainHandler.post {
                    finished = true
                    mainHandler.removeCallbacks(pollRunnable)
                    runCatching {
                        view.stopLoading()
                        view.loadUrl(BLANK_URL)
                        view.onPause()
                    }
                }
            }

            runCatching {
                view.loadUrl(url, mapOf("Accept-Language" to "zh-CN,zh;q=0.9"))
            }.onFailure {
                finish(WebProbePage("", url, blockedByVerification = false, ok = false, message = "loadUrl failed: ${it.message}"))
            }
        }

    private fun ensureWebView(): WebView? {
        if (destroyed) {
            return null
        }
        webView?.let { return it }
        return runCatching {
            WebViewCookieJar.ensureEnabled()
            val view = WebView(webContext)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                loadsImagesAutomatically = false
                blockNetworkImage = true
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            runCatching {
                CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            }
            val widthSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)
            view.layout(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                scriptHandler = runCatching {
                    WebViewCompat.addDocumentStartJavaScript(view, HOOK_SCRIPT, setOf("*"))
                }.getOrNull()
            }
            webView = view
            view
        }.onFailure { Log.w(TAG, "ensureWebView failed: ${it.message}") }.getOrNull()
    }

    private fun interpret(raw: String?, requestedUrl: String): WebProbePage? {
        if (raw.isNullOrBlank() || raw == "null") {
            return null
        }
        val inner = runCatching { JSONTokener(raw).nextValue() }.getOrNull() as? String ?: return null
        val obj = runCatching { JSONObject(inner) }.getOrNull() ?: return null

        val href = obj.optString("href").ifBlank { requestedUrl }
        val title = obj.optString("title")
        val renderData = obj.optString("renderData")
        val initProps = obj.optString("initProps")
        val body = obj.optString("body")
        val enter = obj.optJSONArray("enter")
        val enterText = buildString {
            if (enter != null) {
                for (index in 0 until enter.length()) {
                    append(enter.optString(index))
                    append('\n')
                }
            }
        }

        val combined = buildString {
            if (renderData.isNotBlank()) {
                append("<script id=\"RENDER_DATA\" type=\"application/json\">")
                append(renderData)
                append("</script>\n")
            }
            if (initProps.isNotBlank()) {
                append(initProps)
                append('\n')
            }
            append(enterText)
        }

        val verification = DouyinLiveResolver.looksLikeVerification(
            listOf(body, title, renderData).joinToString("\n"),
            href
        )
        val hasData = sequenceOf("live_status", "room_status", "flv_pull_url", "hls_pull_url")
            .any { combined.contains(it, ignoreCase = true) }

        return WebProbePage(
            rawText = combined,
            finalUrl = href,
            blockedByVerification = verification,
            ok = hasData && !verification
        )
    }

    companion object {
        private const val TAG = "WebViewLiveProbe"
        private const val WARM_UP_URL = "https://live.douyin.com/"
        private const val BLANK_URL = "about:blank"
        private const val DEFAULT_TIMEOUT_MS = 13_000L
        private const val WARM_UP_TIMEOUT_MS = 11_000L
        private const val WARM_UP_SETTLE_MS = 1_800L
        private const val FIRST_POLL_DELAY_MS = 500L
        private const val POLL_INTERVAL_MS = 700L
        private const val VIEWPORT_WIDTH = 720
        private const val VIEWPORT_HEIGHT = 1280

        // Installed at document-start so it hooks fetch/XHR before Douyin's page code runs, and
        // captures the room "enter" API response the page fetches for itself.
        private val HOOK_SCRIPT = """
            (function() {
              if (window.__dyProbeHooked) { return; }
              window.__dyProbeHooked = true;
              window.__dyProbeEnter = [];
              var RE = /webcast\/room\/(web\/enter|enter|info)|aweme\/v1\/web\/live/i;
              function keep(text) {
                try {
                  if (text && window.__dyProbeEnter.length < 8) {
                    window.__dyProbeEnter.push(String(text));
                  }
                } catch (e) {}
              }
              try {
                Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } });
                Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } });
              } catch (e) {}
              try {
                if (window.fetch) {
                  var nativeFetch = window.fetch;
                  window.fetch = function() {
                    var args = arguments;
                    return nativeFetch.apply(this, args).then(function(resp) {
                      try {
                        var url = String((resp && resp.url) || (args[0] && args[0].url) || args[0] || '');
                        if (RE.test(url)) {
                          resp.clone().text().then(keep).catch(function() {});
                        }
                      } catch (e) {}
                      return resp;
                    });
                  };
                }
              } catch (e) {}
              try {
                if (window.XMLHttpRequest) {
                  var open = XMLHttpRequest.prototype.open;
                  var send = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    this.__dyProbeUrl = url;
                    return open.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function() {
                    var xhr = this;
                    xhr.addEventListener('readystatechange', function() {
                      try {
                        if (xhr.readyState === 4 && RE.test(String(xhr.__dyProbeUrl || ''))) {
                          keep(xhr.responseText || '');
                        }
                      } catch (e) {}
                    });
                    return send.apply(this, arguments);
                  };
                }
              } catch (e) {}
            })();
        """.trimIndent()

        // Polled after page load; returns a JSON blob of everything worth parsing.
        private val COLLECTOR_SCRIPT = """
            (function() {
              function byId(id) {
                try {
                  var el = document.getElementById(id);
                  return el ? String(el.textContent || el.innerText || '') : '';
                } catch (e) { return ''; }
              }
              var out = { href: '', title: '', renderData: '', initProps: '', body: '', enter: [] };
              try { out.href = String(location.href || ''); } catch (e) {}
              try { out.title = String(document.title || ''); } catch (e) {}
              try { out.renderData = byId('RENDER_DATA').slice(0, 800000); } catch (e) {}
              try {
                if (window.__INIT_PROPS__) { out.initProps = JSON.stringify(window.__INIT_PROPS__); }
              } catch (e) {}
              try {
                if (!out.initProps && window.__pace_f) { out.initProps = JSON.stringify(window.__pace_f); }
              } catch (e) {}
              try {
                var text = document.body ? (document.body.innerText || '') : '';
                out.body = String(text).slice(0, 4000);
              } catch (e) {}
              try { out.enter = window.__dyProbeEnter || []; } catch (e) {}
              return JSON.stringify(out);
            })();
        """.trimIndent()
    }
}
