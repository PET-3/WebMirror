package com.example.webmirror.downloader

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

/**
 * Enhanced website mirror downloader inspired by HTTrack behaviour:
 * - Recursive discovery of HTML/CSS/JS/images etc.
 * - CSS url() / @import extraction
 * - Link rewriting for offline browsing
 * - Same-domain filter + max depth
 * - Limited parallelism + basic retries
 * - Supports both classic File output and SAF DocumentFile tree
 */
class WebsiteDownloader(private val context: Context? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    @Volatile private var cancelled = false

    private val visited = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val urlToLocal = ConcurrentHashMap<String, String>() // norm URL → relative local path
    private val downloadedCount = AtomicInteger(0)

    private val maxParallel = 4
    private val maxRetries = 2

    fun cancel() {
        cancelled = true
        _progress.value = _progress.value.copy(status = DownloadStatus.Cancelled)
    }

    /**
     * @param outputDir classic java.io.File directory (app-specific or external)
     * @param treeUri optional SAF tree URI chosen by user; if non-null, preferred over outputDir
     */
    suspend fun download(
        startUrl: String,
        outputDir: File,
        treeUri: Uri? = null,
        maxDepth: Int = 3,
        sameDomainOnly: Boolean = true,
        rewriteLinks: Boolean = true
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        visited.clear()
        urlToLocal.clear()
        downloadedCount.set(0)

        val normalizedStart = UrlMapper.normalize(startUrl) ?: run {
            _progress.value = DownloadProgress(status = DownloadStatus.Error, errorMessage = "无效的 URL")
            return@withContext
        }

        val baseHost = try { URI(normalizedStart).host } catch (_: Exception) { null }

        val rootDoc: DocumentFile? = if (treeUri != null && context != null) {
            DocumentFile.fromTreeUri(context, treeUri)
        } else null

        if (rootDoc == null && !outputDir.exists()) {
            outputDir.mkdirs()
        }

        _progress.value = DownloadProgress(
            currentUrl = normalizedStart,
            status = DownloadStatus.Downloading
        )

        try {
            // Phase 1: crawl & download (collect url→local mapping)
            crawl(
                url = normalizedStart,
                baseHost = baseHost,
                outputDir = outputDir,
                rootDoc = rootDoc,
                depth = 0,
                maxDepth = maxDepth,
                sameDomainOnly = sameDomainOnly
            )

            // Phase 2: rewrite already-downloaded HTML/CSS so offline links work
            if (rewriteLinks && !cancelled) {
                rewriteAll(outputDir, rootDoc)
            }

            if (!cancelled) {
                _progress.value = _progress.value.copy(status = DownloadStatus.Completed, currentUrl = "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _progress.value = _progress.value.copy(
                status = DownloadStatus.Error,
                errorMessage = e.message ?: "未知错误"
            )
        }
    }

    private suspend fun crawl(
        url: String,
        baseHost: String?,
        outputDir: File,
        rootDoc: DocumentFile?,
        depth: Int,
        maxDepth: Int,
        sameDomainOnly: Boolean
    ) {
        if (cancelled || depth > maxDepth) return

        val normalized = UrlMapper.normalize(url) ?: return
        if (!visited.add(normalized)) return

        if (sameDomainOnly && baseHost != null) {
            val host = try { URI(normalized).host } catch (_: Exception) { null }
            if (host != baseHost) return
        }

        val localRel = UrlMapper.urlToRelativePath(normalized) ?: return
        urlToLocal[normalized] = localRel

        _progress.value = _progress.value.copy(
            currentUrl = normalized,
            totalDiscovered = visited.size
        )

        val bytes = fetchWithRetry(normalized) ?: return

        // Save original content first
        saveBytes(localRel, bytes, outputDir, rootDoc)

        val count = downloadedCount.incrementAndGet()
        val name = localRel.substringAfterLast('/')
        val recent = (_progress.value.recentFiles + name).takeLast(10)
        _progress.value = _progress.value.copy(downloadedCount = count, recentFiles = recent)

        val contentTypeGuess = guessContentType(localRel, bytes)

        // Discover more links
        val childUrls = mutableSetOf<String>()
        if (contentTypeGuess == ContentType.HTML) {
            val html = String(bytes, Charsets.UTF_8)
            childUrls.addAll(ResourceExtractor.extractFromHtml(html, normalized))
        } else if (contentTypeGuess == ContentType.CSS) {
            val css = String(bytes, Charsets.UTF_8)
            childUrls.addAll(ResourceExtractor.extractFromCss(css, normalized))
        }

        // Parallel limited crawl of children
        if (childUrls.isNotEmpty() && depth < maxDepth && !cancelled) {
            coroutineScope {
                childUrls.chunked(maxParallel).forEach { batch ->
                    if (cancelled) return@forEach
                    batch.map { link ->
                        async(Dispatchers.IO) {
                            crawl(
                                url = link,
                                baseHost = baseHost,
                                outputDir = outputDir,
                                rootDoc = rootDoc,
                                depth = depth + 1,
                                maxDepth = maxDepth,
                                sameDomainOnly = sameDomainOnly
                            )
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun fetchWithRetry(url: String): ByteArray? {
        var lastError: Exception? = null
        repeat(maxRetries + 1) { attempt ->
            if (cancelled) return null
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/*,*/*;q=0.8")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HTTP ${response.code} for $url")
                        return null
                    }
                    return response.body?.bytes()
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt ${attempt + 1} failed for $url: ${e.message}")
                Thread.sleep(300L * (attempt + 1))
            }
        }
        Log.w(TAG, "Giving up on $url: ${lastError?.message}")
        return null
    }

    private fun saveBytes(
        relativePath: String,
        bytes: ByteArray,
        outputDir: File,
        rootDoc: DocumentFile?
    ) {
        if (rootDoc != null && context != null) {
            saveToDocumentTree(rootDoc, relativePath, bytes)
        } else {
            val file = File(outputDir, relativePath)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private fun saveToDocumentTree(root: DocumentFile, relativePath: String, bytes: ByteArray) {
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        var current = root
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var next = current.findFile(dirName)
            if (next == null || !next.isDirectory) {
                next = current.createDirectory(dirName)
            }
            if (next == null) {
                Log.e(TAG, "Cannot create dir $dirName")
                return
            }
            current = next
        }
        val fileName = parts.last()
        var file = current.findFile(fileName)
        if (file == null) {
            val mime = guessMime(fileName)
            file = current.createFile(mime, fileName)
        }
        if (file == null) {
            Log.e(TAG, "Cannot create file $fileName")
            return
        }
        context?.contentResolver?.openOutputStream(file.uri)?.use { os: OutputStream ->
            os.write(bytes)
        }
    }

    private suspend fun rewriteAll(outputDir: File, rootDoc: DocumentFile?) {
        val snapshot = urlToLocal.toMap()
        for ((url, localPath) in snapshot) {
            if (cancelled) break
            val lower = localPath.lowercase()
            if (lower.endsWith(".html") || lower.endsWith(".htm") ||
                lower.endsWith(".css") || !localPath.contains('.')
            ) {
                val bytes = readBytes(localPath, outputDir, rootDoc) ?: continue
                val text = String(bytes, Charsets.UTF_8)
                val rewritten = when {
                    lower.endsWith(".css") -> LinkRewriter.rewriteCss(text, url, localPath, snapshot)
                    else -> LinkRewriter.rewriteHtml(text, url, localPath, snapshot)
                }
                if (rewritten != text) {
                    saveBytes(localPath, rewritten.toByteArray(Charsets.UTF_8), outputDir, rootDoc)
                }
            }
        }
    }

    private fun readBytes(relativePath: String, outputDir: File, rootDoc: DocumentFile?): ByteArray? {
        return try {
            if (rootDoc != null && context != null) {
                val file = findDocumentFile(rootDoc, relativePath) ?: return null
                context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
            } else {
                File(outputDir, relativePath).takeIf { it.exists() }?.readBytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Read failed $relativePath: ${e.message}")
            null
        }
    }

    private fun findDocumentFile(root: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        var current = root
        for (part in parts) {
            current = current.findFile(part) ?: return null
        }
        return current
    }

    private enum class ContentType { HTML, CSS, OTHER }

    private fun guessContentType(path: String, bytes: ByteArray): ContentType {
        val lower = path.lowercase()
        if (lower.endsWith(".css")) return ContentType.CSS
        if (lower.endsWith(".html") || lower.endsWith(".htm") || !path.contains('.')) {
            val head = String(bytes.take(512).toByteArray(), Charsets.UTF_8).lowercase()
            if (head.contains("<html") || head.contains("<!doctype") || head.contains("<head")) {
                return ContentType.HTML
            }
        }
        return ContentType.OTHER
    }

    private fun guessMime(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    companion object {
        private const val TAG = "WebsiteDownloader"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 WebMirror/1.1"
    }
}
