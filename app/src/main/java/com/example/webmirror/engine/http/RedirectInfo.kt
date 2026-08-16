package com.example.webmirror.engine.http

/**
 * One hop in a redirect chain.
 */
data class RedirectHop(
    val fromUrl: String,
    val toUrl: String,
    val statusCode: Int
)

/**
 * Full redirect outcome for a request.
 */
data class RedirectChain(
    val originalUrl: String,
    val finalUrl: String,
    val hops: List<RedirectHop>
) {
    val redirected: Boolean get() = hops.isNotEmpty() && originalUrl != finalUrl
}
