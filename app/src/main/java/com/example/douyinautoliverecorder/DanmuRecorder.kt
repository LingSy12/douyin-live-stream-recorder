package com.example.douyinautoliverecorder

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

data class DanmuStartResult(
    val started: Boolean,
    val outputPath: String? = null,
    val error: String? = null
)

data class DanmuStopResult(
    val recording: PreparedRecording? = null,
    val outputPath: String? = null,
    val itemCount: Int = 0
)

class DanmuRecorder(
    context: Context
) {
    private val appContext = context.applicationContext
    private val webContext = context
    private val mainHandler = Handler(Looper.getMainLooper())

    private class ActiveCapture(
        val recording: PreparedRecording,
        val outputPath: String,
        val startedAtMs: Long
    ) {
        val lock = Any()
        val recentLines = LinkedHashMap<String, Long>()

        @Volatile
        var webView: WebView? = null

        @Volatile
        var stopping: Boolean = false

        @Volatile
        var itemCount: Int = 0

        @Volatile
        var scriptHandler: ScriptHandler? = null

        @Volatile
        var documentStartEnabled: Boolean = false
    }

    private val active = ConcurrentHashMap<String, ActiveCapture>()

    fun isRecording(roomId: String): Boolean = active.containsKey(roomId)

    fun start(
        roomId: String,
        roomUrl: String,
        roomLabel: String,
        settings: AppSettings,
        baseFileName: String?,
        startedAtMs: Long
    ): DanmuStartResult {
        if (active.containsKey(roomId)) {
            return DanmuStartResult(started = true, outputPath = active[roomId]?.outputPath)
        }

        val safeBaseName = baseFileName
            ?.takeIf { it.isNotBlank() }
            ?: roomLabel.ifBlank { "danmu" }

        val recording = StorageHelper.prepareCompanionRecording(
            context = appContext,
            settings = settings,
            baseFileName = safeBaseName,
            extension = "jsonl",
            mimeType = "application/x-ndjson"
        ) ?: return DanmuStartResult(
            started = false,
            error = "Failed to prepare danmu storage"
        )

        val capture = ActiveCapture(
            recording = recording,
            outputPath = recording.displayPath,
            startedAtMs = startedAtMs
        )
        active[roomId] = capture
        Log.d(TAG, "start room=$roomId url=$roomUrl out=${recording.displayPath}")
        launchWebView(roomId, roomUrl, capture)
        return DanmuStartResult(started = true, outputPath = recording.displayPath)
    }

    fun stop(roomId: String, publish: Boolean = true): DanmuStopResult {
        val capture = active.remove(roomId) ?: return DanmuStopResult()
        capture.stopping = true
        mainHandler.post {
            destroyWebView(capture)
        }

        Log.d(TAG, "stop room=$roomId items=${capture.itemCount} bytes=${capture.recording.tempFile.length()} publish=$publish")

        if (!capture.recording.tempFile.exists() || capture.recording.tempFile.length() < 8L || capture.itemCount == 0) {
            Log.d(TAG, "stop discard room=${roomId} exists=${capture.recording.tempFile.exists()} bytes=${capture.recording.tempFile.length()} items=${capture.itemCount}")
            StorageHelper.discardRecording(capture.recording)
            return DanmuStopResult(itemCount = capture.itemCount)
        }

        if (!publish) {
            return DanmuStopResult(
                recording = capture.recording,
                outputPath = capture.outputPath,
                itemCount = capture.itemCount
            )
        }

        val publishedPath = StorageHelper.publishRecording(appContext, capture.recording)
        return DanmuStopResult(
            recording = null,
            outputPath = publishedPath,
            itemCount = capture.itemCount
        )
    }

    fun stopAll(publish: Boolean = true): List<DanmuStopResult> {
        return active.keys.toList().map { stop(it, publish) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun launchWebView(roomId: String, roomUrl: String, capture: ActiveCapture) {
        mainHandler.post {
            if (active[roomId] !== capture || capture.stopping) {
                return@post
            }

            val webView = WebView(webContext)
            capture.webView = webView
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = false
                useWideViewPort = true
                loadWithOverviewMode = false
                userAgentString = "$DEFAULT_USER_AGENT OTGKeeper/1.0"
            }
            webView.resumeTimers()
            webView.onResume()
            prepareHiddenViewport(webView)
            webView.addJavascriptInterface(JsBridge(roomId, capture), JS_BRIDGE_NAME)
            installDocumentStartHooks(roomId, capture, webView)
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.d(TAG, "console room=$roomId ${consoleMessage.message()}")
                    return false
                }
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    Log.d(TAG, "page finished room=$roomId url=$url")
                    scheduleHookInjection(roomId, capture, view)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        Log.w(TAG, "danmu page error room=$roomId code=${error.errorCode} desc=${error.description}")
                    }
                }
            }
            webView.loadUrl(
                roomUrl,
                mapOf(
                    "Referer" to "https://live.douyin.com/",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Upgrade-Insecure-Requests" to "1"
                )
            )
        }
    }

    private fun prepareHiddenViewport(webView: WebView) {
        val width = View.MeasureSpec.makeMeasureSpec(WEB_VIEW_WIDTH, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(WEB_VIEW_HEIGHT, View.MeasureSpec.EXACTLY)
        webView.measure(width, height)
        webView.layout(0, 0, WEB_VIEW_WIDTH, WEB_VIEW_HEIGHT)
    }

    private fun scheduleHookInjection(roomId: String, capture: ActiveCapture, webView: WebView) {
        injectHooks(roomId, capture, webView)
        mainHandler.postDelayed({ injectHooks(roomId, capture, webView) }, 1500L)
        mainHandler.postDelayed({ injectHooks(roomId, capture, webView) }, 5000L)
    }

    private fun installDocumentStartHooks(roomId: String, capture: ActiveCapture, webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, WEB_MESSAGE_OBJECT, setOf("*")) { _, message, _, _, _ ->
                handleBridgeMessage(roomId, capture, message.data)
            }
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.d(TAG, "document-start not supported room=$roomId")
            capture.documentStartEnabled = false
            return
        }

        capture.scriptHandler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            INJECT_SCRIPT,
            setOf("*")
        )
        capture.documentStartEnabled = true
        Log.d(TAG, "document-start enabled room=$roomId")
    }

    private fun destroyWebView(capture: ActiveCapture) {
        val webView = capture.webView ?: return
        capture.webView = null
        capture.scriptHandler = null
        runCatching {
            webView.pauseTimers()
            webView.onPause()
            webView.stopLoading()
            webView.removeJavascriptInterface(JS_BRIDGE_NAME)
            webView.clearHistory()
            webView.clearCache(true)
            webView.destroy()
        }.onFailure {
            Log.w(TAG, "destroyWebView failed: ${it.message}")
        }
    }

    private fun handleBridgeMessage(roomId: String, capture: ActiveCapture, payload: String?) {
        if (payload.isNullOrBlank()) {
            return
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("type")) {
            "batch" -> appendBatch(roomId, capture, json.optJSONArray("items")?.toString())
            "log" -> {
                val message = json.optString("message")
                if (message.isNotBlank()) {
                    Log.d(TAG, "js room=$roomId $message")
                }
            }
        }
    }

    private fun appendBatch(roomId: String, capture: ActiveCapture, payload: String?) {
        if (payload.isNullOrBlank() || capture.stopping) {
            return
        }
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return
        if (active[roomId] !== capture) {
            return
        }

        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()

        synchronized(capture.lock) {
            cleanupRecent(capture, now)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = normalizeDanmuText(item.optString("text")) ?: continue
                if (capture.recentLines.containsKey(text)) {
                    continue
                }
                capture.recentLines[text] = now
                val offsetMs = (item.optLong("ts", now) - capture.startedAtMs).coerceAtLeast(0L)
                val source = item.optString("source")
                val field = item.optString("field")
                val line = JSONObject()
                    .put("offsetMs", offsetMs)
                    .put("text", text)
                if (source.isNotBlank()) {
                    line.put("source", source)
                }
                if (field.isNotBlank()) {
                    line.put("field", field)
                }
                lines += line.toString()
                capture.itemCount += 1
            }
        }

        if (lines.isEmpty()) {
            return
        }

        runCatching {
            capture.recording.tempFile.appendText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            if (capture.itemCount <= 5 || capture.itemCount % 20 == 0) {
                Log.d(TAG, "captured room=$roomId total=${capture.itemCount} last=${lines.lastOrNull()}")
            }
        }.onFailure {
            Log.w(TAG, "append danmu failed room=$roomId error=${it.message}")
        }
    }

    private fun cleanupRecent(capture: ActiveCapture, now: Long) {
        val iterator = capture.recentLines.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > RECENT_WINDOW_MS) {
                iterator.remove()
            }
        }
    }

    private fun normalizeDanmuText(value: String?): String? {
        val text = value.orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.length < 2 || text.length > 120) {
            return null
        }

        if (IGNORED_EXACT.any { it.equals(text, ignoreCase = true) }) {
            return null
        }

        if (IGNORED_PREFIX.any { text.startsWith(it) }) {
            return null
        }

        if (CODE_PATTERNS.any { marker -> text.contains(marker, ignoreCase = true) }) {
            return null
        }

        if (text.contains("http://", ignoreCase = true) || text.contains("https://", ignoreCase = true)) {
            return null
        }

        if (text.any { it in setOf('{', '}', '<', '>', ';') }) {
            return null
        }

        if ('=' in text) {
            return null
        }

        val asciiLetters = text.count { it.code in 65..90 || it.code in 97..122 }
        val cjkChars = text.count { it.code in 0x4E00..0x9FFF }
        if (asciiLetters > 24 && cjkChars == 0) {
            return null
        }

        if (text.count { it == ' ' } > 12) {
            return null
        }

        if (text.all(Char::isDigit) && text.length >= 5) {
            return null
        }

        return text
    }

    private fun injectHooks(roomId: String, capture: ActiveCapture, webView: WebView) {
        if (capture.stopping || active[roomId] !== capture) {
            return
        }
        webView.evaluateJavascript(INJECT_SCRIPT, null)
    }

    private inner class JsBridge(
        private val roomId: String,
        private val capture: ActiveCapture
    ) {
        @JavascriptInterface
        fun onBatch(payload: String?) {
            appendBatch(roomId, capture, payload)
        }

        @JavascriptInterface
        fun onLog(message: String?) {
            if (!message.isNullOrBlank()) {
                Log.d(TAG, "js room=$roomId $message")
            }
        }
    }

    companion object {
        private const val TAG = "DanmuRecorder"
        private const val JS_BRIDGE_NAME = "OTGDanmuBridge"
        private const val WEB_MESSAGE_OBJECT = "OTGDanmuPort"
        private const val RECENT_WINDOW_MS = 10 * 60 * 1000L
        private const val WEB_VIEW_WIDTH = 1920
        private const val WEB_VIEW_HEIGHT = 1080
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val IGNORED_EXACT = setOf(
            "\u53d1\u9001",
            "\u767b\u5f55",
            "\u8bc4\u8bba",
            "\u804a\u5929",
            "\u5f39\u5e55",
            "\u901a\u77e5",
            "\u79c1\u4fe1",
            "\u66f4\u591a",
            "\u4e3e\u62a5",
            "\u5206\u4eab",
            "\u5173\u6ce8",
            "\u793c\u7269",
            "\u8bbe\u7f6e",
            "\u4e0b\u8f7d",
            "\u6253\u5f00\u6296\u97f3",
            "\u8bf4\u70b9\u4ec0\u4e48...",
            "\u5168\u90e8",
            "\u4eba\u6c14\u699c",
            "\u5728\u7ebf\u89c2\u4f17",
            "\u9ad8\u7b49\u7ea7\u7528\u6237",
            "\u5145\u503c",
            "App"
        )

        private val IGNORED_PREFIX = listOf(
            "\u6296\u97f3",
            "\u6253\u5f00",
            "\u76f4\u64ad\u4e2d",
            "\u53d1\u9001\u8bc4\u8bba",
            "\u70b9\u51fb",
            "\u4e0b\u8f7d"
        )

        private val CODE_PATTERNS = listOf(
            "window.",
            "function",
            "slardar",
            "adapter_conf",
            "baseline",
            "javascript",
            "document.",
            "const ",
            "var ",
            "let ",
            "idontknownwhatisthis",
            "src=",
            "href=",
            "fetch(",
            "xmlhttprequest",
            "websocket"
        )

        private val INJECT_SCRIPT = """
            (function() {
              var reactSuffix = String.fromCharCode(36);
              if (window.__otgDanmuInstalled) {
                try {
                  if (window.__otgDanmuDiscover) {
                    window.__otgDanmuDiscover();
                  }
                } catch (e) {}
                try {
                  if (window.__otgDanmuForceScan) {
                    window.__otgDanmuForceScan(document.body || document.documentElement || document);
                  }
                } catch (e) {}
                return 'installed';
              }
              window.__otgDanmuInstalled = true;
              window.__otgDanmuSeen = window.__otgDanmuSeen || {};
              window.__otgDanmuAttempts = window.__otgDanmuAttempts || 0;
              var MESSAGE_EVENT_NAMES = [
                'RoomMessage',
                'WebcastRoomMessage',
                'WebcastChatMessage',
                'WebcastEmojiChatMessage',
                'WebcastExhibitionChatMessage',
                'WebcastScreenChatMessage',
                'WebcastPrivilegeScreenChatMessage',
                'WebcastMemberMessage',
                'WebcastSocialMessage',
                'WebcastNoticeMessage',
                'WebcastCommonMessage',
                'WebcastControlMessage'
              ];
              var GLOBAL_MESSAGE_KEYS = [
                '__MESSAGE_INSTANCE__',
                '__IM_MESSAGE_INSTANCE__',
                '__WEBCAST_MESSAGE_INSTANCE__',
                '__TTLIVE_MESSAGE_INSTANCE__',
                'messageInstance'
              ];
              var CANDIDATE_MESSAGE_KEYS = [
                'message',
                'messageStore',
                'msgStore',
                'msgCenter',
                'eventBus',
                'emitter',
                'instance',
                '__MESSAGE_INSTANCE__',
                '__IM_MESSAGE_INSTANCE__',
                '__WEBCAST_MESSAGE_INSTANCE__'
              ];
              var DISCOVERY_KEYS = {
                singletonStore: 1,
                message: 1,
                messageStore: 1,
                msgStore: 1,
                msgCenter: 1,
                eventBus: 1,
                emitter: 1,
                im: 1,
                imStore: 1,
                imSdk: 1,
                webcast: 1,
                webcastStore: 1,
                controller: 1,
                manager: 1,
                instance: 1,
                __MESSAGE_INSTANCE__: 1,
                __IM_MESSAGE_INSTANCE__: 1,
                __WEBCAST_MESSAGE_INSTANCE__: 1,
                props: 1,
                state: 1,
                memoizedProps: 1,
                memoizedState: 1,
                baseState: 1,
                stateNode: 1,
                child: 1,
                sibling: 1,
                return: 1,
                alternate: 1,
                current: 1,
                pendingProps: 1,
                dependencies: 1,
                context: 1,
                value: 1,
                store: 1,
                stores: 1
              };

              function postMessageToNative(obj) {
                var payload = JSON.stringify(obj);
                try {
                  if (window.OTGDanmuPort && typeof window.OTGDanmuPort.postMessage === 'function') {
                    window.OTGDanmuPort.postMessage(payload);
                    return true;
                  }
                } catch (e) {}
                try {
                  if (window.OTGDanmuBridge) {
                    if (obj.type === 'batch' && window.OTGDanmuBridge.onBatch) {
                      window.OTGDanmuBridge.onBatch(JSON.stringify(obj.items || []));
                      return true;
                    }
                    if (obj.type === 'log' && window.OTGDanmuBridge.onLog) {
                      window.OTGDanmuBridge.onLog(String(obj.message || ''));
                      return true;
                    }
                  }
                } catch (e) {}
                return false;
              }

              function log(msg) {
                postMessageToNative({ type: 'log', message: String(msg) });
              }

              function safeSeenHas(store, value) {
                if (!store || !value || (typeof value !== 'object' && typeof value !== 'function')) {
                  return false;
                }
                try {
                  if (typeof WeakSet !== 'undefined' && store instanceof WeakSet) {
                    return store.has(value);
                  }
                } catch (e) {}
                return !!store.indexOf && store.indexOf(value) >= 0;
              }

              function safeSeenAdd(store, value) {
                if (!store || !value || (typeof value !== 'object' && typeof value !== 'function')) {
                  return;
                }
                try {
                  if (typeof WeakSet !== 'undefined' && store instanceof WeakSet) {
                    store.add(value);
                    return;
                  }
                } catch (e) {}
                if (store.indexOf && store.indexOf(value) < 0) {
                  store.push(value);
                }
              }

              function normalize(text) {
                return String(text || '').replace(/\s+/g, ' ').trim();
              }

              function containsCjk(text) {
                for (var i = 0; i < text.length; i++) {
                  var code = text.charCodeAt(i);
                  if (code >= 0x4E00 && code <= 0x9FFF) {
                    return true;
                  }
                }
                return false;
              }

              function shouldSkip(text) {
                if (!text || text.length < 2 || text.length > 120) {
                  return true;
                }
                var lower = text.toLowerCase();
                var exact = [
                  '\u53d1\u9001',
                  '\u767b\u5f55',
                  '\u8bc4\u8bba',
                  '\u804a\u5929',
                  '\u5f39\u5e55',
                  '\u901a\u77e5',
                  '\u79c1\u4fe1',
                  '\u66f4\u591a',
                  '\u4e3e\u62a5',
                  '\u5206\u4eab',
                  '\u5173\u6ce8',
                  '\u793c\u7269',
                  '\u8bbe\u7f6e',
                  '\u4e0b\u8f7d',
                  '\u6253\u5f00\u6296\u97f3',
                  '\u8bf4\u70b9\u4ec0\u4e48...',
                  'app'
                ];
                for (var i = 0; i < exact.length; i++) {
                  if (lower === exact[i].toLowerCase()) {
                    return true;
                  }
                }
                var prefixes = [
                  '\u6296\u97f3',
                  '\u6253\u5f00',
                  '\u76f4\u64ad\u4e2d',
                  '\u53d1\u9001\u8bc4\u8bba',
                  '\u70b9\u51fb',
                  '\u4e0b\u8f7d'
                ];
                for (var j = 0; j < prefixes.length; j++) {
                  if (text.indexOf(prefixes[j]) === 0) {
                    return true;
                  }
                }
                var codeHints = [
                  'window.', 'function', 'slardar', 'adapter_conf', 'baseline', 'javascript',
                  'document.', 'const ', 'var ', 'let ', 'idontknownwhatisthis', 'src=', 'href=',
                  'fetch(', 'xmlhttprequest', 'websocket'
                ];
                for (var k = 0; k < codeHints.length; k++) {
                  if (lower.indexOf(codeHints[k]) >= 0) {
                    return true;
                  }
                }
                if (text.indexOf('http://') >= 0 || text.indexOf('https://') >= 0) {
                  return true;
                }
                if (/[{}<>;]/.test(text) || text.indexOf('=') >= 0) {
                  return true;
                }
                var spaces = (text.match(/ /g) || []).length;
                if (spaces > 12) {
                  return true;
                }
                var asciiLetters = (text.match(/[A-Za-z]/g) || []).length;
                if (asciiLetters > 24 && !containsCjk(text)) {
                  return true;
                }
                return false;
              }

              function isMessageInstance(candidate) {
                if (!candidate || (typeof candidate !== 'object' && typeof candidate !== 'function')) {
                  return false;
                }
                if (candidate === window || candidate === document || candidate.window === candidate) {
                  return false;
                }
                var subscribeLike = typeof candidate.subscribe === 'function';
                var emitterLike = typeof candidate.on === 'function' && typeof candidate.off === 'function';
                var messageOpsLike = typeof candidate.unsubscribe === 'function' ||
                  typeof candidate.getDecoder === 'function' ||
                  typeof candidate.getPolling === 'function' ||
                  typeof candidate.setWebsocketKey === 'function' ||
                  typeof candidate.queuePush === 'function' ||
                  typeof candidate.syncOn === 'function' ||
                  typeof candidate.mock === 'function';
                var messageStateLike = !!candidate.cachedHandler ||
                  !!candidate.headers ||
                  !!candidate.decoder ||
                  !!candidate.polling ||
                  !!candidate.insertData;
                return (subscribeLike || emitterLike) && (messageOpsLike || messageStateLike);
              }

              function summarizeCandidate(candidate) {
                if (!candidate) {
                  return String(candidate);
                }
                if (candidate === window) {
                  return 'window';
                }
                var keys = [];
                var fnKeys = [];
                try {
                  keys = Object.keys(candidate).slice(0, 12);
                } catch (e) {}
                try {
                  var ownKeys = Object.getOwnPropertyNames(candidate);
                  for (var ownIndex = 0; ownIndex < ownKeys.length && fnKeys.length < 12; ownIndex++) {
                    var ownKey = ownKeys[ownIndex];
                    try {
                      if (typeof candidate[ownKey] === 'function') {
                        fnKeys.push(ownKey);
                      }
                    } catch (e) {}
                  }
                } catch (e) {}
                var parts = [];
                if (keys.length) {
                  parts.push('keys=' + keys.join(','));
                }
                if (fnKeys.length) {
                  parts.push('fns=' + fnKeys.join(','));
                }
                return parts.join('|') || Object.prototype.toString.call(candidate);
              }

              function shouldWatchEventName(name) {
                if (!name || typeof name !== 'string') {
                  return false;
                }
                return MESSAGE_EVENT_NAMES.indexOf(name) >= 0 || /(message|chat|comment|room|danm|bullet|notice)/i.test(name);
              }

              function emitItems(items) {
                if (items && items.length) {
                  postMessageToNative({ type: 'batch', items: items });
                }
              }

              function emitText(rawText, ts, source, field) {
                var text = normalize(rawText);
                if (shouldSkip(text)) {
                  return null;
                }
                var now = ts || Date.now();
                if (window.__otgDanmuSeen[text] && now - window.__otgDanmuSeen[text] < 600000) {
                  return null;
                }
                window.__otgDanmuSeen[text] = now;
                var item = { text: text, ts: now, source: source || '' };
                if (field) {
                  item.field = field;
                }
                return item;
              }

              function isVisible(node) {
                if (!node || node.nodeType !== 1) {
                  return false;
                }
                var tag = (node.tagName || '').toUpperCase();
                if (['SCRIPT', 'STYLE', 'NOSCRIPT', 'HEAD', 'META', 'LINK', 'SVG', 'PATH', 'CANVAS'].indexOf(tag) >= 0) {
                  return false;
                }
                var style = window.getComputedStyle ? window.getComputedStyle(node) : null;
                if (style && (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity || '1') === 0)) {
                  return false;
                }

                return true;
              }

              function looksLikeMessageNode(node) {
                if (!node || node.nodeType !== 1) {
                  return false;
                }
                var mark = [
                  node.className || '',
                  node.id || '',
                  node.getAttribute && node.getAttribute('data-e2e') || '',
                  node.getAttribute && node.getAttribute('role') || '',
                  node.getAttribute && node.getAttribute('aria-live') || ''
                ].join(' ').toLowerCase();
                return /comment|chat|message|msg|danm|bullet|notice|im/.test(mark);
              }

              function collectNode(root) {
                if (!root) {
                  return;
                }
                var candidates = [];
                if (root.nodeType === 1 && isVisible(root) && looksLikeMessageNode(root) && (root.childElementCount || 0) <= 4) {
                  candidates.push(root);
                }
                if (root.querySelectorAll) {
                  var query = [
                    '[data-e2e*="comment"]',
                    '[data-e2e*="chat"]',
                    '[data-e2e*="message"]',
                    '[data-e2e*="danm"]',
                    '[aria-live] > *',
                    '[class*="comment"]',
                    '[class*="chat"]',
                    '[class*="danm"]',
                    '[class*="bullet"]',
                    '[class*="notice"]'
                  ].join(',');
                  try {
                    var found = root.querySelectorAll(query);
                    for (var i = 0; i < found.length && i < 120; i++) {
                      candidates.push(found[i]);
                    }
                  } catch (e) {}
                }
                var unique = [];
                var items = [];
                candidates.forEach(function(node) {
                  if (!node || unique.indexOf(node) >= 0 || !isVisible(node)) {
                    return;
                  }
                  unique.push(node);
                  var text = normalize(node.innerText || node.textContent || '');
                  if (!text || text.length > 120) {
                    return;
                  }
                  text.split(/\n+/).forEach(function(part) {
                    var item = emitText(part, Date.now(), 'dom', 'dom');
                    if (item) {
                      items.push(item);
                    }
                  });
                });
                emitItems(items);
              }

              function scanMessageObject(payload, source) {
                if (!payload) {
                  return;
                }
                var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : [];
                var queue = [{ value: payload, path: 'payload', depth: 0 }];
                var items = [];
                var visited = 0;

                function maybeCollect(path, rawValue) {
                  if (typeof rawValue !== 'string') {
                    return;
                  }
                  var lowerPath = String(path || '').toLowerCase();
                  if (!/(^|\.|\[\])(content|text|comment|comments|msg|display|display_text|displaytext|detail|body|desc|chat|danm)(\.|$|\[\])/.test(lowerPath)) {
                    return;
                  }
                  if (/(prompt|tips|toast|notice|priority|score|count|level|rank|heat|type|method|action|duration|time|timestamp|create_time|enter|leave)/.test(lowerPath)) {
                    return;
                  }
                  if (/(avatar|icon|image|url|uri|nick|name|title|id|sec|uid|room|user|label|schema|color|css|class)/.test(lowerPath)) {
                    return;
                  }
                  var item = emitText(rawValue, Date.now(), source, lowerPath);
                  if (item) {
                    items.push(item);
                  }
                }

                while (queue.length && visited < 300) {
                  var current = queue.shift();
                  visited += 1;
                  var value = current.value;
                  if (value == null) {
                    continue;
                  }
                  var valueType = typeof value;
                  if (valueType === 'string') {
                    maybeCollect(current.path, value);
                    continue;
                  }
                  if (valueType !== 'object' && valueType !== 'function') {
                    continue;
                  }
                  if (safeSeenHas(seen, value)) {
                    continue;
                  }
                  safeSeenAdd(seen, value);
                  if (current.depth >= 5) {
                    continue;
                  }
                  if (Array.isArray(value)) {
                    for (var arrayIndex = 0; arrayIndex < value.length && arrayIndex < 40; arrayIndex++) {
                      queue.push({
                        value: value[arrayIndex],
                        path: current.path + '[]',
                        depth: current.depth + 1
                      });
                    }
                    continue;
                  }
                  var keys = [];
                  try {
                    keys = Object.keys(value);
                  } catch (e) {}
                  for (var keyIndex = 0; keyIndex < keys.length && keyIndex < 50; keyIndex++) {
                    var key = keys[keyIndex];
                    var child;
                    try {
                      child = value[key];
                    } catch (e) {
                      continue;
                    }
                    if (child == null) {
                      continue;
                    }
                    var nextPath = current.path ? current.path + '.' + key : key;
                    if (typeof child === 'string') {
                      maybeCollect(nextPath, child);
                    } else if (typeof child === 'object' || typeof child === 'function') {
                      queue.push({ value: child, path: nextPath, depth: current.depth + 1 });
                    }
                  }
                }

                if (items.length) {
                  log('message-items:' + source + ':' + items.length);
                  emitItems(items);
                }
              }

              function scanPayload(payload, source) {
                if (!payload) {
                  return;
                }
                var text = '';
                try {
                  text = typeof payload === 'string' ? payload : String(payload);
                } catch (e) {
                  return;
                }
                if (!text || text.length > 500000) {
                  return;
                }
                var lowered = text.toLowerCase();
                if (lowered.indexOf('<html') >= 0 || lowered.indexOf('<!doctype') >= 0) {
                  return;
                }
                if (!/(webcast\/im|im\/fetch|roommessage|comment|chat|message|danm)/.test(lowered)) {
                  return;
                }
                if (/webcast\/im|im\/fetch|roommessage/.test(lowered)) {
                  log('matched:' + source);
                }
                var items = [];
                var pattern = /"(?:content|text|comment|message|msg|display_text|displayText|body)"\s*:\s*"([^"\\]{2,120}(?:\\.[^"\\]*)*)"/g;
                var match;
                while ((match = pattern.exec(text)) !== null) {
                  var raw = match[1]
                    .replace(/\\u003C/g, '<')
                    .replace(/\\u003E/g, '>')
                    .replace(/\\u0026/g, '&')
                    .replace(/\\\//g, '/')
                    .replace(/\\n/g, ' ')
                    .replace(/\\r/g, ' ')
                    .replace(/\\"/g, '"');
                  var item = emitText(raw, Date.now(), source, 'payload');
                  if (item) {
                    items.push(item);
                  }
                }
                emitItems(items);
              }

              function scanBinaryPayload(text, source) {
                if (!text || text.length < 2) {
                  return;
                }
                var matches = text.match(/[\u4E00-\u9FFF0-9A-Za-z@#._,!?:~\-\[\]()'\"]{2,40}/g) || [];
                var items = [];
                for (var matchIndex = 0; matchIndex < matches.length; matchIndex++) {
                  var item = emitText(matches[matchIndex], Date.now(), source, 'binary');
                  if (item) {
                    items.push(item);
                  }
                }
                if (items.length) {
                  log('binary-matched:' + source + ':' + items.length);
                  emitItems(items);
                }
              }

              function handleIncomingEvent(event, source) {
                try {
                  window.__otgDanmuEventCount = (window.__otgDanmuEventCount || 0) + 1;
                  if (window.__otgDanmuEventCount <= 8) {
                    log('message-event:' + source + ':' + summarizeCandidate(event));
                  }
                  if (Array.isArray(event)) {
                    for (var eventIndex = 0; eventIndex < event.length; eventIndex++) {
                      scanMessageObject(event[eventIndex], source);
                    }
                    return;
                  }
                  scanMessageObject(event, source);
                } catch (handlerError) {
                  log('message-handler-error:' + (handlerError && handlerError.message ? handlerError.message : handlerError));
                }
              }

              function subscribeMessageMethod(message, methodName, eventName, source) {
                if (!message || typeof message[methodName] !== 'function') {
                  return false;
                }
                try {
                  var unsubscribe = message[methodName](eventName, function(event) {
                    handleIncomingEvent(event, methodName + ':' + source + ':' + eventName);
                  });
                  if (!window.__otgDanmuUnsubscribeList) {
                    window.__otgDanmuUnsubscribeList = [];
                  }
                  if (typeof unsubscribe === 'function') {
                    window.__otgDanmuUnsubscribeList.push(unsubscribe);
                  }
                  return true;
                } catch (subscribeError) {
                  log('message-' + methodName + '-fail:' + eventName + ':' + (subscribeError && subscribeError.message ? subscribeError.message : subscribeError));
                }
                return false;
              }

              function wrapMessageMethod(message, methodName) {
                if (!message || typeof message[methodName] !== 'function') {
                  return;
                }
                var guardName = '__otgDanmuWrapped_' + methodName;
                if (message[guardName]) {
                  return;
                }
                try {
                  message[guardName] = true;
                } catch (e) {}
                var nativeMethod = message[methodName];
                message[methodName] = function() {
                  try {
                    var eventName = typeof arguments[0] === 'string' ? arguments[0] : '';
                    var payload = arguments.length > 1 ? arguments[1] : arguments[0];
                    if ((eventName && shouldWatchEventName(eventName)) || (!eventName && payload)) {
                      if (eventName) {
                        log('message-' + methodName + ':' + eventName);
                      }
                      handleIncomingEvent(payload, methodName + ':' + (eventName || 'payload'));
                    }
                  } catch (methodError) {}
                  return nativeMethod.apply(this, arguments);
                };
              }

              function hookMessageInstance(message, source) {
                if (!message || message.__otgDanmuHooked) {
                  return false;
                }
                try {
                  message.__otgDanmuHooked = true;
                } catch (e) {}
                window.__otgDanmuMessage = message;
                log('message-hooked:' + source);
                try {
                  if (window.__otgDanmuDomFallbackTimer) {
                    clearTimeout(window.__otgDanmuDomFallbackTimer);
                    window.__otgDanmuDomFallbackTimer = 0;
                  }
                  if (window.__otgDanmuDomInterval) {
                    clearInterval(window.__otgDanmuDomInterval);
                    window.__otgDanmuDomInterval = 0;
                  }
                  if (window.__otgDanmuObserver && typeof window.__otgDanmuObserver.disconnect === 'function') {
                    window.__otgDanmuObserver.disconnect();
                  }
                } catch (e) {}

                var subscribeCount = 0;
                for (var subscribeIndex = 0; subscribeIndex < MESSAGE_EVENT_NAMES.length; subscribeIndex++) {
                  var eventName = MESSAGE_EVENT_NAMES[subscribeIndex];
                  if (subscribeMessageMethod(message, 'subscribe', eventName, source)) {
                    subscribeCount += 1;
                  }
                }
                if (subscribeCount === 0) {
                  for (var onIndex = 0; onIndex < MESSAGE_EVENT_NAMES.length; onIndex++) {
                    if (subscribeMessageMethod(message, 'on', MESSAGE_EVENT_NAMES[onIndex], source)) {
                      subscribeCount += 1;
                    }
                  }
                }
                if (subscribeCount === 0) {
                  for (var addEventIndex = 0; addEventIndex < MESSAGE_EVENT_NAMES.length; addEventIndex++) {
                    if (subscribeMessageMethod(message, 'addEventListener', MESSAGE_EVENT_NAMES[addEventIndex], source)) {
                      subscribeCount += 1;
                    }
                  }
                }
                if (subscribeCount > 0) {
                  log('message-subscribe:ok:' + subscribeCount);
                } else {
                  log('message-subscribe:none');
                }

                wrapMessageMethod(message, 'emit');
                wrapMessageMethod(message, 'dispatch');
                wrapMessageMethod(message, 'trigger');
                wrapMessageMethod(message, 'publish');
                wrapMessageMethod(message, 'fire');

                return true;
              }

              function inspectCandidate(candidate, source) {
                if (!candidate) {
                  return false;
                }
                try {
                  if (isMessageInstance(candidate)) {
                    return hookMessageInstance(candidate, source);
                  }
                } catch (e) {}
                try {
                  if (candidate.singletonStore && isMessageInstance(candidate.singletonStore.message)) {
                    return hookMessageInstance(candidate.singletonStore.message, source + '.singletonStore.message');
                  }
                } catch (e) {}
                try {
                  if (candidate.message && isMessageInstance(candidate.message)) {
                    return hookMessageInstance(candidate.message, source + '.message');
                  }
                } catch (e) {}
                for (var nestedIndex = 0; nestedIndex < CANDIDATE_MESSAGE_KEYS.length; nestedIndex++) {
                  var nestedKey = CANDIDATE_MESSAGE_KEYS[nestedIndex];
                  try {
                    if (candidate[nestedKey] && isMessageInstance(candidate[nestedKey])) {
                      return hookMessageInstance(candidate[nestedKey], source + '.' + nestedKey);
                    }
                  } catch (e) {}
                }
                return false;
              }

              function enqueueDiscoveryChildren(queue, candidate, source, depth) {
                if (!candidate || depth > 6) {
                  return;
                }
                if (Array.isArray(candidate)) {
                  for (var arrayIndex = 0; arrayIndex < candidate.length && arrayIndex < 20; arrayIndex++) {
                    queue.push({
                      value: candidate[arrayIndex],
                      source: source + '[' + arrayIndex + ']',
                      depth: depth
                    });
                  }
                  return;
                }
                var keys = [];
                try {
                  keys = Object.keys(candidate);
                } catch (e) {}
                for (var keyIndex = 0; keyIndex < keys.length && keyIndex < 40; keyIndex++) {
                  var key = keys[keyIndex];
                  if (!DISCOVERY_KEYS[key]) {
                    continue;
                  }
                  try {
                    queue.push({
                      value: candidate[key],
                      source: source + '.' + key,
                      depth: depth
                    });
                  } catch (e) {}
                }
              }

              function watchGlobalMessageKey(name) {
                window.__otgDanmuGlobalWatchers = window.__otgDanmuGlobalWatchers || {};
                if (window.__otgDanmuGlobalWatchers[name]) {
                  return;
                }
                window.__otgDanmuGlobalWatchers[name] = true;
                try {
                  var currentValue = window[name];
                  Object.defineProperty(window, name, {
                    configurable: true,
                    enumerable: true,
                    get: function() {
                      return currentValue;
                    },
                    set: function(value) {
                      currentValue = value;
                      try {
                        log('message-global-set:' + name + ':' + summarizeCandidate(value));
                      } catch (e) {}
                      try {
                        inspectCandidate(value, 'window.' + name);
                      } catch (e) {}
                    }
                  });
                  if (currentValue) {
                    inspectCandidate(currentValue, 'window.' + name);
                  }
                } catch (e) {}
              }

              function discoverMessageInstance() {
                if (window.__otgDanmuMessage && window.__otgDanmuMessage.__otgDanmuHooked) {
                  return true;
                }
                window.__otgDanmuAttempts = (window.__otgDanmuAttempts || 0) + 1;
                var queue = [{ value: window, source: 'window', depth: 0 }];
                for (var globalIndex = 0; globalIndex < GLOBAL_MESSAGE_KEYS.length; globalIndex++) {
                  var globalKey = GLOBAL_MESSAGE_KEYS[globalIndex];
                  try {
                    if (window[globalKey] && inspectCandidate(window[globalKey], 'window.' + globalKey)) {
                      return true;
                    }
                  } catch (e) {}
                }
                try {
                  var windowProps = Object.getOwnPropertyNames(window);
                  var matchedWindowProps = [];
                  for (var windowPropIndex = 0; windowPropIndex < windowProps.length && windowPropIndex < 400; windowPropIndex++) {
                    var windowProp = windowProps[windowPropIndex];
                    if (!/(message|webcast|socket|chat|room)/i.test(windowProp)) {
                      continue;
                    }
                    if (matchedWindowProps.length < 12) {
                      matchedWindowProps.push(windowProp);
                    }
                    try {
                      if (inspectCandidate(window[windowProp], 'window.' + windowProp)) {
                        return true;
                      }
                    } catch (e) {}
                  }
                  if (matchedWindowProps.length && (window.__otgDanmuAttempts <= 2 || window.__otgDanmuAttempts % 10 == 0)) {
                    log('message-window-props:' + matchedWindowProps.join(','));
                  }
                } catch (e) {}
                var rootElement = document.getElementById('root') || document.body || document.documentElement;
                if (rootElement) {
                  var rootProps = [];
                  try {
                    rootProps = Object.getOwnPropertyNames(rootElement);
                  } catch (e) {}
                  for (var propIndex = 0; propIndex < rootProps.length; propIndex++) {
                    var propName = rootProps[propIndex];
                    if (propName.indexOf('__reactFiber' + reactSuffix) == 0 || propName.indexOf('__reactContainer' + reactSuffix) == 0) {
                      try {
                        queue.push({ value: rootElement[propName], source: propName, depth: 0 });
                      } catch (e) {}
                    }
                  }
                }
                if (window.__REACT_DEVTOOLS_GLOBAL_HOOK__) {
                  queue.push({ value: window.__REACT_DEVTOOLS_GLOBAL_HOOK__, source: '__REACT_DEVTOOLS_GLOBAL_HOOK__', depth: 0 });
                }
                if (window.__pace_f) {
                  queue.push({ value: window.__pace_f, source: '__pace_f', depth: 0 });
                }
                if (window.EXPOSE_DATA) {
                  queue.push({ value: window.EXPOSE_DATA, source: 'EXPOSE_DATA', depth: 0 });
                }

                var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : [];
                var steps = 0;
                while (queue.length && steps < 450) {
                  var current = queue.shift();
                  steps += 1;
                  var value = current.value;
                  if (!value || (typeof value !== 'object' && typeof value !== 'function')) {
                    continue;
                  }
                  if (safeSeenHas(seen, value)) {
                    continue;
                  }
                  safeSeenAdd(seen, value);
                  if (inspectCandidate(value, current.source)) {
                    return true;
                  }
                  enqueueDiscoveryChildren(queue, value, current.source, current.depth + 1);
                }

                if (window.__otgDanmuAttempts <= 4 || window.__otgDanmuAttempts % 5 == 0) {
                  log('message-discovery:miss:' + window.__otgDanmuAttempts);
                }
                return false;
              }

              function hookFetch() {
                if (!window.fetch || window.__otgDanmuFetchHooked) {
                  return;
                }
                window.__otgDanmuFetchHooked = true;
                var nativeFetch = window.fetch;
                window.fetch = function() {
                  var args = arguments;
                  return nativeFetch.apply(this, args).then(function(response) {
                    try {
                      var url = String(response.url || (args[0] && args[0].url) || args[0] || '');
                      if (/(webcast\/im|im\/fetch|roommessage|comment|chat|message|danm)/i.test(url)) {
                        log('fetch:' + url);
                        response.clone().text().then(function(text) {
                          scanPayload(text, 'fetch:' + url);
                        }).catch(function() {});
                      }
                    } catch (e) {}
                    return response;
                  });
                };
              }

              function hookXhr() {
                if (!window.XMLHttpRequest || window.__otgDanmuXhrHooked) {
                  return;
                }
                window.__otgDanmuXhrHooked = true;
                var open = XMLHttpRequest.prototype.open;
                var send = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function(method, url) {
                  this.__otgUrl = url;
                  return open.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  this.addEventListener('readystatechange', function() {
                    try {
                      if (this.readyState === 4 && /(webcast\/im|im\/fetch|roommessage|comment|chat|message|danm)/i.test(String(this.__otgUrl || ''))) {
                        log('xhr:' + this.__otgUrl);
                        scanPayload(this.responseText || '', 'xhr:' + this.__otgUrl);
                      }
                    } catch (e) {}
                  });
                  return send.apply(this, arguments);
                };
              }

              function hookWebSocket() {
                if (!window.WebSocket || window.__otgDanmuWsHooked) {
                  return;
                }
                window.__otgDanmuWsHooked = true;
                window.__otgDanmuWsCount = 0;
                var NativeWebSocket = window.WebSocket;

                function describeSocketTarget(value) {
                  try {
                    if (typeof value === 'string') {
                      return value;
                    }
                    if (value && typeof value.url === 'string') {
                      return value.url;
                    }
                    if (value && typeof value === 'object') {
                      var keys = Object.keys(value).slice(0, 6);
                      return 'object:' + keys.join(',');
                    }
                    return String(value);
                  } catch (e) {
                    return 'unknown';
                  }
                }

                function handleSocketPayload(data, url, source) {
                  try {
                    window.__otgDanmuWsCount = (window.__otgDanmuWsCount || 0) + 1;
                    var seq = window.__otgDanmuWsCount;
                    var payload = data && typeof data === 'object' && 'data' in data ? data.data : data;
                    if (typeof payload === 'string') {
                      if (seq <= 5) log('ws-msg:string:' + seq + ':' + source + ':' + (payload.length || 0));
                      scanPayload(payload, 'ws:' + source + ':' + url);
                      scanBinaryPayload(payload, 'ws:' + source + ':' + url);
                    } else if (payload && typeof payload.text === 'function') {
                      if (seq <= 5) log('ws-msg:blob:' + seq + ':' + source);
                      payload.text().then(function(text) {
                        scanPayload(text, 'wsblob:' + source + ':' + url);
                        scanBinaryPayload(text, 'wsblob:' + source + ':' + url);
                      }).catch(function() {});
                    } else if (payload && typeof ArrayBuffer !== 'undefined' && payload instanceof ArrayBuffer && typeof TextDecoder !== 'undefined') {
                      if (seq <= 5) log('ws-msg:array:' + seq + ':' + source + ':' + payload.byteLength);
                      try {
                        var decoder = new TextDecoder('utf-8');
                        var decoded = decoder.decode(payload);
                        scanPayload(decoded, 'wsarray:' + source + ':' + url);
                        scanBinaryPayload(decoded, 'wsarray:' + source + ':' + url);
                      } catch (e) {}
                    } else if (payload && typeof ArrayBuffer !== 'undefined' && typeof ArrayBuffer.isView === 'function' && ArrayBuffer.isView(payload) && typeof TextDecoder !== 'undefined') {
                      if (seq <= 5) log('ws-msg:view:' + seq + ':' + source + ':' + payload.byteLength);
                      try {
                        var viewDecoder = new TextDecoder('utf-8');
                        var viewDecoded = viewDecoder.decode(payload);
                        scanPayload(viewDecoded, 'wsview:' + source + ':' + url);
                        scanBinaryPayload(viewDecoded, 'wsview:' + source + ':' + url);
                      } catch (e) {}
                    } else if (seq <= 5) {
                      log('ws-msg:other:' + seq + ':' + source + ':' + Object.prototype.toString.call(payload || data));
                    }
                  } catch (e) {}
                }

                function wrapSocket(ws, url) {
                  if (!ws || ws.__otgDanmuSocketWrapped) {
                    return ws;
                  }
                  try {
                    ws.__otgDanmuSocketWrapped = true;
                  } catch (e) {}
                  var target = describeSocketTarget(url);
                  log('ws-open:' + target);

                  try {
                    if (typeof ws.addEventListener === 'function') {
                      ws.addEventListener('message', function(event) {
                        handleSocketPayload(event, target, 'listener');
                      });
                    }
                  } catch (e) {}

                  try {
                    if (typeof ws.dispatchEvent === 'function' && !ws.__otgDanmuDispatchWrapped) {
                      ws.__otgDanmuDispatchWrapped = true;
                      var nativeDispatchEvent = ws.dispatchEvent;
                      ws.dispatchEvent = function(event) {
                        try {
                          if (event && event.type === 'message') {
                            handleSocketPayload(event, target, 'dispatch');
                          }
                        } catch (dispatchError) {}
                        return nativeDispatchEvent.apply(this, arguments);
                      };
                    }
                  } catch (e) {}

                  try {
                    if (typeof ws.on === 'function' && !ws.__otgDanmuOnWrapped) {
                      ws.__otgDanmuOnWrapped = true;
                      var nativeOn = ws.on;
                      ws.on = function(name, listener) {
                        if (name === 'message' && typeof listener === 'function') {
                          var wrappedListener = function(event) {
                            handleSocketPayload(event, target, 'on');
                            return listener.apply(this, arguments);
                          };
                          return nativeOn.call(this, name, wrappedListener);
                        }
                        return nativeOn.apply(this, arguments);
                      };
                    }
                  } catch (e) {}

                  try {
                    var onMessageDescriptor = Object.getOwnPropertyDescriptor(ws, 'onmessage') ||
                      Object.getOwnPropertyDescriptor(Object.getPrototypeOf(ws) || {}, 'onmessage');
                    if (onMessageDescriptor && onMessageDescriptor.configurable && !ws.__otgDanmuOnMessageWrapped) {
                      ws.__otgDanmuOnMessageWrapped = true;
                      var nativeGetter = onMessageDescriptor.get;
                      var nativeSetter = onMessageDescriptor.set;
                      var storedHandler = nativeGetter ? nativeGetter.call(ws) : ws.onmessage;
                      Object.defineProperty(ws, 'onmessage', {
                        configurable: true,
                        enumerable: true,
                        get: function() {
                          return nativeGetter ? nativeGetter.call(this) : storedHandler;
                        },
                        set: function(handler) {
                          var wrappedHandler = handler;
                          if (typeof handler === 'function') {
                            wrappedHandler = function(event) {
                              handleSocketPayload(event, target, 'onmessage');
                              return handler.apply(this, arguments);
                            };
                          }
                          storedHandler = wrappedHandler;
                          if (nativeSetter) {
                            nativeSetter.call(this, wrappedHandler);
                          }
                        }
                      });
                      if (typeof storedHandler === 'function') {
                        ws.onmessage = storedHandler;
                      }
                    }
                  } catch (e) {}
                  return ws;
                }
                var WrappedWebSocket = function(url, protocols) {
                  var ws = protocols ? new NativeWebSocket(url, protocols) : new NativeWebSocket(url);
                  return wrapSocket(ws, url);
                };
                WrappedWebSocket.prototype = NativeWebSocket.prototype;
                window.WebSocket = WrappedWebSocket;
              }

              function forceVisible() {
                try {
                  Object.defineProperty(document, 'hidden', { configurable: true, get: function() { return false; } });
                  Object.defineProperty(document, 'visibilityState', { configurable: true, get: function() { return 'visible'; } });
                  Object.defineProperty(document, 'webkitHidden', { configurable: true, get: function() { return false; } });
                } catch (e) {}
              }

              function muteMediaElement(media) {
                if (!media) {
                  return;
                }
                try { media.muted = true; } catch (e) {}
                try { media.defaultMuted = true; } catch (e) {}
                try { media.volume = 0; } catch (e) {}
                try { media.setAttribute('muted', 'muted'); } catch (e) {}
                try { media.playsInline = true; } catch (e) {}
              }

              function muteMediaElements(root) {
                var scope = root && root.querySelectorAll ? root : document;
                if (!scope || !scope.querySelectorAll) {
                  return;
                }
                try {
                  var medias = scope.querySelectorAll('video,audio');
                  for (var mediaIndex = 0; mediaIndex < medias.length; mediaIndex++) {
                    muteMediaElement(medias[mediaIndex]);
                  }
                } catch (e) {}
              }

              function hookMediaPlayback() {
                if (!window.HTMLMediaElement || window.__otgDanmuMediaHooked) {
                  return;
                }
                window.__otgDanmuMediaHooked = true;
                var nativePlay = window.HTMLMediaElement.prototype.play;
                if (typeof nativePlay === 'function') {
                  window.HTMLMediaElement.prototype.play = function() {
                    muteMediaElement(this);
                    return nativePlay.apply(this, arguments);
                  };
                }
              }

              function startDomHooks() {
                if (window.__otgDanmuDomStarted) {
                  return;
                }
                var root = document.documentElement || document.body;
                if (!root) {
                  setTimeout(startDomHooks, 50);
                  return;
                }
                window.__otgDanmuDomStarted = true;
                var observer = new MutationObserver(function(mutations) {
                  mutations.forEach(function(mutation) {
                    Array.prototype.forEach.call(mutation.addedNodes || [], function(node) {
                      muteMediaElements(node);
                      collectNode(node);
                    });
                  });
                });
                observer.observe(root, { childList: true, subtree: true });
                window.__otgDanmuObserver = observer;
                window.__otgDanmuForceScan = function(target) {
                  collectNode(target || document.body || document.documentElement || document);
                };
                window.__otgDanmuDomInterval = setInterval(function() {
                  muteMediaElements(document);
                  collectNode(document.body || document.documentElement || document);
                }, 1200);
                setTimeout(function() {
                  muteMediaElements(document);
                  collectNode(document.body || document.documentElement || document);
                }, 3000);
              }

              function startDomFallback() {
                if (window.__otgDanmuDomFallbackScheduled) {
                  return;
                }
                window.__otgDanmuDomFallbackScheduled = true;
                window.__otgDanmuDomFallbackTimer = setTimeout(function() {
                  if (window.__otgDanmuMessage && window.__otgDanmuMessage.__otgDanmuHooked) {
                    return;
                  }
                  log('dom-fallback-started');
                  startDomHooks();
                }, 6000);
              }

              function startInitialDomSnapshot() {
                if (window.__otgDanmuInitialDomSnapshotStarted) {
                  return;
                }
                window.__otgDanmuInitialDomSnapshotStarted = true;
                function runSnapshot(tag) {
                  try {
                    muteMediaElements(document);
                    collectNode(document.body || document.documentElement || document);
                    log('dom-snapshot:' + tag);
                  } catch (e) {}
                }
                setTimeout(function() {
                  runSnapshot('1');
                }, 2500);
                setTimeout(function() {
                  runSnapshot('2');
                }, 5000);
              }

              function startMessageDiscovery() {
                if (window.__otgDanmuDiscoveryStarted) {
                  return;
                }
                window.__otgDanmuDiscoveryStarted = true;
                window.__otgDanmuDiscover = discoverMessageInstance;
                var tick = function() {
                  try {
                    if (discoverMessageInstance() && window.__otgDanmuDiscoveryTimer) {
                      clearInterval(window.__otgDanmuDiscoveryTimer);
                      window.__otgDanmuDiscoveryTimer = 0;
                    }
                  } catch (e) {}
                };
                tick();
                window.__otgDanmuDiscoveryTimer = setInterval(tick, 1500);
              }

              hookFetch();
              hookXhr();
              hookWebSocket();
              hookMediaPlayback();
              for (var watchIndex = 0; watchIndex < GLOBAL_MESSAGE_KEYS.length; watchIndex++) {
                watchGlobalMessageKey(GLOBAL_MESSAGE_KEYS[watchIndex]);
              }
              forceVisible();
              muteMediaElements(document);
              log('viewport:' + window.innerWidth + 'x' + window.innerHeight);
              startMessageDiscovery();
              startInitialDomSnapshot();
              startDomFallback();
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', function() {
                  muteMediaElements(document);
                }, { once: true });
              }
              log('hooks-installed');
              return 'ok';
            })();
        """.trimIndent()
    }
}





