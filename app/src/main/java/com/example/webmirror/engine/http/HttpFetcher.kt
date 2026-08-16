package com.example.webmirror.engine.http

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class FetchResult(
    val success: Boolean,
    val httpCode: Int? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val finalUrl: String? = null,
    val originalUrl: String? = null,
    val redirectChain: RedirectChain? = null,
    val sha256: String? = null,
    val bytesWritten: Long = 0,
    val errorMessage: String? = null,
    val notModified: Boolean = false,
    val retryAfterSec: Long? = null,
    val retryable: Boolean = false
)

/**
 * Streaming HTTP fetcher with redirect-chain capture, Retry-After, conditional GET.
 * Never loads the full body into memory.
 */
class HttpFetcher(
    private val policy: HttpRequestPolicy = HttpRequestPolicy(),
    private val cookieJar: okhttp3.CookieJar? = null,
    private val proxyHost: String? = null,
    private val proxyPort: Int = 0
) {
    private val followClient: OkHttpClient = buildClient(follow = true)
    private val noFollowClient: OkHttpClient = buildClient(follow = false)

    private fun buildClient(follow: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(policy.connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(policy.readTimeoutSec, TimeUnit.SECONDS)
            .followRedirects(follow)
            .followSslRedirects(follow)
        if (cookieJar != null) b.cookieJar(cookieJar)
        if (!proxyHost.isNullOrBlank() && proxyPort > 0) {
            b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(proxyHost, proxyPort)))
        }
        return b.build()
    }

    fun fetchToStream(
        url: String,
        output: OutputStream,
        etag: String? = null,
        lastModified: String? = null,
        recordRedirects: Boolean = true
    ): FetchResult {
        return try {
            if (recordRedirects) {
                fetchWithRedirectRecording(url, output, etag, lastModified)
            } else {
                val request = buildRequest(url, etag, lastModified)
                followClient.newCall(request).execute().use { response ->
                    handleBodyResponse(
                        response, output, url,
                        response.request.url.toString(), null
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed $url: ${e.message}")
            FetchResult(
                success = false,
                originalUrl = url,
                errorMessage = e.message,
                retryable = true
            )
        }
    }

    fun fetchToFile(
        url: String,
        file: File,
        etag: String? = null,
        lastModified: String? = null,
        recordRedirects: Boolean = true
    ): FetchResult {
        file.parentFile?.mkdirs()
        return file.outputStream().use { os ->
            fetchToStream(url, os, etag, lastModified, recordRedirects)
        }
    }

    private fun fetchWithRedirectRecording(
        startUrl: String,
        output: OutputStream,
        etag: String?,
        lastModified: String?
    ): FetchResult {
        val hops = mutableListOf<RedirectHop>()
        var current = startUrl
        var conditionalEtag = etag
        var conditionalLm = lastModified

        repeat(MAX_REDIRECTS) {
            val request = buildRequest(current, conditionalEtag, conditionalLm)
            noFollowClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code in REDIRECT_CODES) {
                    val location = response.header("Location")
                        ?: return FetchResult(
                            success = false,
                            httpCode = code,
                            originalUrl = startUrl,
                            finalUrl = current,
                            errorMessage = "Redirect without Location",
                            retryable = false
                        )
                    val next = resolveRedirect(current, location)
                    hops.add(RedirectHop(fromUrl = current, toUrl = next, statusCode = code))
                    current = next
                    conditionalEtag = null
                    conditionalLm = null
                    return@use
                }

                val chain = if (hops.isEmpty()) null else RedirectChain(
                    originalUrl = startUrl,
                    finalUrl = current,
                    hops = hops.toList()
                )
                return handleBodyResponse(response, output, startUrl, current, chain)
            }
        }

        return FetchResult(
            success = false,
            originalUrl = startUrl,
            finalUrl = current,
            redirectChain = RedirectChain(startUrl, current, hops),
            errorMessage = "Too many redirects",
            retryable = false
        )
    }

    private fun handleBodyResponse(
        response: Response,
        output: OutputStream,
        originalUrl: String,
        finalUrl: String,
        chain: RedirectChain?
    ): FetchResult {
        val code = response.code
        val retryAfter = parseRetryAfter(response.header("Retry-After"))

        if (code == 304) {
            return FetchResult(
                success = true,
                httpCode = 304,
                notModified = true,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                originalUrl = originalUrl,
                finalUrl = finalUrl,
                redirectChain = chain
            )
        }

        if (!response.isSuccessful) {
            return FetchResult(
                success = false,
                httpCode = code,
                originalUrl = originalUrl,
                finalUrl = finalUrl,
                redirectChain = chain,
                errorMessage = "HTTP $code",
                retryAfterSec = retryAfter,
                retryable = policy.isRetryableHttpCode(code)
            )
        }

        val body = response.body ?: return FetchResult(
            success = false,
            httpCode = code,
            originalUrl = originalUrl,
            finalUrl = finalUrl,
            errorMessage = "Empty body",
            retryable = false
        )

        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        body.source().use { source ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = source.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                written += read
            }
        }
        output.flush()

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return FetchResult(
            success = true,
            httpCode = code,
            contentType = body.contentType()?.toString() ?: response.header("Content-Type"),
            contentLength = written,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            originalUrl = originalUrl,
            finalUrl = finalUrl,
            redirectChain = chain,
            sha256 = sha,
            bytesWritten = written
        )
    }

    private fun buildRequest(url: String, etag: String?, lastModified: String?): Request {
        val b = Request.Builder()
            .url(url)
            .header("User-Agent", policy.userAgent)
            .header("Accept", policy.acceptHeader)
            .header("Accept-Language", policy.acceptLanguage)
        for ((k, v) in policy.customHeaders) {
            if (k.isNotBlank() && v.isNotBlank()) b.header(k, v)
        }
        if (!etag.isNullOrBlank()) b.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) b.header("If-Modified-Since", lastModified)
        return b.build()
    }

    private fun resolveRedirect(currentUrl: String, location: String): String {
        return try {
            java.net.URI(currentUrl).resolve(location).toString()
        } catch (_: Exception) {
            location
        }
    }

    private fun parseRetryAfter(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        return header.trim().toLongOrNull()?.coerceAtLeast(0)
    }

    companion object {
        private const val TAG = "HttpFetcher"
        private const val BUFFER = 16 * 1024
        private const val MAX_REDIRECTS = 10
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 WebMirror/1.2"
    }
}
