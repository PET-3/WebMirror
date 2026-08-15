package com.example.webmirror.downloader

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class DownloadProgress(
    val currentUrl: String = "",
    val downloadedCount: Int = 0,
    val totalDiscovered: Int = 0,
    val status: DownloadStatus = DownloadStatus.Idle,
    val errorMessage: String? = null,
    val recentFiles: List<String> = emptyList()
)

enum class DownloadStatus {
    Idle, Downloading, Completed, Error, Cancelled
}

class WebsiteDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    @Volatile
    private var cancelled = false

    private val visited = Collections.synchronizedSet(mutableSetOf<String>())
    private val downloadedCount = AtomicInteger(0)

    fun cancel() {
        cancelled = true
        _progress.value = _progress.value.copy(status = DownloadStatus.Cancelled)
    }

    suspend fun download(
        startUrl: String,
        outputDir: File,
        maxDepth: Int = 3,
        sameDomainOnly: Boolean = true
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        visited.clear()
        downloadedCount.set(0)

        val normalizedStart = normalizeUrl(startUrl) ?: run {
            _progress.value = DownloadProgress(
                status = DownloadStatus.Error,
                errorMessage = "无效的 URL"
            )
            return@withContext
        }

        val baseHost = try {
            URI(normalizedStart).host
        } catch (e: Exception) {
            null
        }

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        _progress.value = DownloadProgress(
            currentUrl = normalizedStart,
            status = DownloadStatus.Downloading
        )

        try {
            crawl(
                url = normalizedStart,
                baseHost = baseHost,
                outputDir = outputDir,
                depth = 0,
                maxDepth = maxDepth,
                sameDomainOnly = sameDomainOnly
            )

            if (!cancelled) {
                _progress.value = _progress.value.copy(
                    status = DownloadStatus.Completed,
                    currentUrl = ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _progress.value = _progress.value.copy(
                status = DownloadStatus.Error,
                errorMessage = e.message ?: "未知错误"
            )
        }
    }

    private fun crawl(
        url: String,
        baseHost: String?,
        outputDir: File,
        depth: Int,
        maxDepth: Int,
        sameDomainOnly: Boolean
    ) {
        if (cancelled || depth > maxDepth) return

        val normalized = normalizeUrl(url) ?: return
        if (!visited.add(normalized)) return

        if (sameDomainOnly && baseHost != null) {
            val host = try {
                URI(normalized).host
            } catch (e: Exception) {
                null
            }
            if (host != baseHost) return
        }

        _progress.value = _progress.value.copy(
            currentUrl = normalized,
            totalDiscovered = visited.size
        )

        try {
            val request = Request.Builder()
                .url(normalized)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed: $normalized code=${response.code}")
                    return
                }

                val body = response.body ?: return
                val contentType = body.contentType()?.toString() ?: ""
                val bytes = body.bytes()

                val localFile = urlToLocalFile(normalized, outputDir)
                localFile.parentFile?.mkdirs()
                localFile.writeBytes(bytes)

                val count = downloadedCount.incrementAndGet()
                val recent = (_progress.value.recentFiles + localFile.name).takeLast(8)
                _progress.value = _progress.value.copy(
                    downloadedCount = count,
                    recentFiles = recent
                )

                Log.d(TAG, "Saved: ${localFile.absolutePath}")

                // Only parse HTML for further links
                if (contentType.contains("text/html", ignoreCase = true) ||
                    localFile.extension.lowercase() in listOf("html", "htm", "")
                ) {
                    val html = String(bytes, Charsets.UTF_8)
                    val doc = Jsoup.parse(html, normalized)

                    val links = mutableSetOf<String>()

                    // <a href>
                    doc.select("a[href]").forEach { el ->
                        el.attr("abs:href").takeIf { it.isNotBlank() }?.let { links.add(it) }
                    }
                    // <link href> (css)
                    doc.select("link[href]").forEach { el ->
                        el.attr("abs:href").takeIf { it.isNotBlank() }?.let { links.add(it) }
                    }
                    // <script src>
                    doc.select("script[src]").forEach { el ->
                        el.attr("abs:src").takeIf { it.isNotBlank() }?.let { links.add(it) }
                    }
                    // <img src>
                    doc.select("img[src]").forEach { el ->
                        el.attr("abs:src").takeIf { it.isNotBlank() }?.let { links.add(it) }
                    }
                    // <source src>
                    doc.select("source[src]").forEach { el ->
                        el.attr("abs:src").takeIf { it.isNotBlank() }?.let { links.add(it) }
                    }
                    // CSS url() roughly handled by downloading linked css files which may contain more

                    links.forEach { link ->
                        if (cancelled) return
                        // Strip fragment
                        val clean = link.substringBefore('#')
                        if (clean.startsWith("http")) {
                            crawl(
                                url = clean,
                                baseHost = baseHost,
                                outputDir = outputDir,
                                depth = depth + 1,
                                maxDepth = maxDepth,
                                sameDomainOnly = sameDomainOnly
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error downloading $normalized: ${e.message}")
        }
    }

    private fun normalizeUrl(url: String): String? {
        return try {
            var u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            val uri = URI(u)
            // Remove fragment
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null)
                .toString()
                .removeSuffix("/")
                .ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun urlToLocalFile(url: String, outputDir: File): File {
        val uri = URI(url)
        var path = uri.path ?: "/"
        if (path.endsWith("/") || path.isEmpty()) {
            path += "index.html"
        }
        // Remove leading /
        if (path.startsWith("/")) path = path.substring(1)

        // Sanitize path segments
        val safePath = path.split("/").joinToString("/") { segment ->
            segment.replace(Regex("[\\\\:*?\"<>|]"), "_")
        }

        val hostDir = (uri.host ?: "unknown").replace(Regex("[\\\\:*?\"<>|]"), "_")
        return File(outputDir, "$hostDir/$safePath")
    }

    companion object {
        private const val TAG = "WebsiteDownloader"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 WebMirror/1.0"
    }
}
