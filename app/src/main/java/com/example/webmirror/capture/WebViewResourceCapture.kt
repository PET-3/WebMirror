package com.example.webmirror.capture

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.webmirror.engine.model.LocalPathMapper
import com.example.webmirror.engine.model.UrlNormalizer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView-based capture: when the page (or user interaction) triggers a network
 * request, we fetch once via OkHttp, save under [outputDir] using the same path
 * mapping as the static engine, and return the body to WebView.
 *
 * Not a full desktop headless browser — best-effort Network-like capture on Android.
 */
class WebViewResourceCapture(
    private val outputDir: File,
    private val sameHostOnly: Boolean = false,
    private val seedHost: String? = null,
    private val onCaptured: (url: String, bytes: Int, totalCount: Int) -> Unit = { _, _, _ -> },
    private val onPageEvent: (message: String) -> Unit = {}
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val savedKeys = ConcurrentHashMap.newKeySet<String>()
    private val captureCount = AtomicInteger(0)

    fun capturedCount(): Int = captureCount.get()

    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = settingsUserAgent(userAgentString)
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        webView.webViewClient = captureClient()
    }

    private fun settingsUserAgent(base: String?): String {
        // Keep a normal mobile UA so sites serve real assets
        return (base ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36") + " WebMirrorCapture/1.0"
    }

    private fun captureClient() = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageEvent("加载中：${url.orEmpty()}")
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onPageEvent("页面完成：${url.orEmpty()} · 已捕获 ${captureCount.get()} 个资源")
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // Stay inside WebView
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            if (request == null) return null
            val url = request.url?.toString() ?: return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return null
            }
            // Only intercept main GETs; let WebView handle non-GET (complex multipart etc.)
            if (request.method != null && !request.method.equals("GET", ignoreCase = true)) {
                return fetchAndMaybeSave(url, request) // still try capture POST? skip body complexity
            }
            return fetchAndSave(url, request)
        }
    }

    private fun fetchAndMaybeSave(url: String, request: WebResourceRequest): WebResourceResponse? {
        // For non-GET, don't intercept — WebView default. Optional: still fire a GET save if same URL later.
        return null
    }

    private fun fetchAndSave(url: String, request: WebResourceRequest): WebResourceResponse? {
        val normalized = UrlNormalizer.normalize(url) ?: url
        if (sameHostOnly && seedHost != null) {
            val host = try {
                java.net.URI(normalized).host?.lowercase()
            } catch (_: Exception) {
                null
            }
            if (host != null && host != seedHost && !host.endsWith(".$seedHost")) {
                // Let WebView load CDN itself but we skip saving off-host if policy set;
                // still intercept would break page — so pass through without save:
                return null
            }
        }

        return try {
            val builder = Request.Builder().url(url).get()
            // Forward a few headers from WebView request
            request.requestHeaders?.forEach { (k, v) ->
                val key = k.lowercase()
                if (key == "user-agent" || key == "accept" || key == "accept-language" ||
                    key == "referer" || key == "range"
                ) {
                    try {
                        builder.header(k, v)
                    } catch (_: Exception) {
                    }
                }
            }
            if (request.requestHeaders?.containsKey("User-Agent") != true) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }

            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code !in 200..299) {
                    return null
                }
                val bodyBytes = response.body?.bytes() ?: return null
                val contentType = response.header("Content-Type")
                val mime = contentType?.substringBefore(';')?.trim().orEmpty()
                    .ifBlank { guessMime(normalized) }

                saveBytes(normalized, bodyBytes)

                val encoding = if (mime.startsWith("text/") || mime.contains("json") ||
                    mime.contains("javascript") || mime.contains("xml")
                ) {
                    contentType?.substringAfter("charset=", "UTF-8")?.trim()?.takeIf { it.isNotBlank() }
                        ?: "UTF-8"
                } else {
                    null
                }

                WebResourceResponse(
                    mime.ifBlank { "application/octet-stream" },
                    encoding,
                    ByteArrayInputStream(bodyBytes)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "intercept failed $url: ${e.message}")
            null
        }
    }

    private fun saveBytes(normalizedUrl: String, bytes: ByteArray) {
        val key = normalizedUrl
        if (!savedKeys.add(key)) return
        val rel = LocalPathMapper.toRelativePath(normalizedUrl) ?: return
        try {
            val file = File(outputDir, rel)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            val n = captureCount.incrementAndGet()
            onCaptured(normalizedUrl, bytes.size, n)
        } catch (e: Exception) {
            savedKeys.remove(key)
            Log.w(TAG, "save failed $rel: ${e.message}")
        }
    }

    private fun guessMime(url: String): String {
        val p = url.lowercase().substringBefore('?')
        return when {
            p.endsWith(".html") || p.endsWith(".htm") -> "text/html"
            p.endsWith(".css") -> "text/css"
            p.endsWith(".js") || p.endsWith(".mjs") -> "application/javascript"
            p.endsWith(".json") -> "application/json"
            p.endsWith(".png") -> "image/png"
            p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
            p.endsWith(".webp") -> "image/webp"
            p.endsWith(".gif") -> "image/gif"
            p.endsWith(".svg") -> "image/svg+xml"
            p.endsWith(".woff2") -> "font/woff2"
            p.endsWith(".woff") -> "font/woff"
            p.endsWith(".ktx2") || p.endsWith(".ktx") -> "application/octet-stream"
            p.endsWith(".glb") -> "model/gltf-binary"
            p.endsWith(".wasm") -> "application/wasm"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "WebViewCapture"
    }
}
