package com.example.webmirror.engine.http

/**
 * Policy for retries, timeouts, and which status codes are retryable.
 */
data class HttpRequestPolicy(
    val connectTimeoutSec: Long = 15,
    val readTimeoutSec: Long = 60,
    val maxRetries: Int = 3,
    /** Base delay between retries (multiplied by attempt). */
    val retryBaseDelayMs: Long = 400,
    val respectRetryAfter: Boolean = true,
    /** Max seconds to wait on Retry-After (cap). */
    val maxRetryAfterSec: Long = 60,
    val userAgent: String = HttpFetcher.DEFAULT_UA,
    val acceptHeader: String =
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    val acceptLanguage: String = "zh-CN,zh;q=0.9,en;q=0.8",
    val customHeaders: Map<String, String> = emptyMap()
) {
    fun isRetryableHttpCode(code: Int): Boolean = when (code) {
        408, 429, 500, 502, 503, 504 -> true
        else -> false
    }

    fun isFatalClientError(code: Int): Boolean = code in 400..499 && !isRetryableHttpCode(code)

    fun delayForAttempt(attempt: Int, retryAfterSec: Long? = null): Long {
        if (respectRetryAfter && retryAfterSec != null && retryAfterSec > 0) {
            return (retryAfterSec.coerceAtMost(maxRetryAfterSec)) * 1000L
        }
        return retryBaseDelayMs * (attempt + 1)
    }
}
