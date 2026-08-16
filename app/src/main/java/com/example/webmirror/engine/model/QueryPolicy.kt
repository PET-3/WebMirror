package com.example.webmirror.engine.model

/**
 * How to treat URL query strings for identity / path mapping.
 */
enum class QueryMode {
    /** Keep full query (default; safest for content identity). */
    KEEP_ALL,
    /** Drop only known tracking parameters. */
    STRIP_TRACKING,
    /** Drop entire query (aggressive; may collide pages). */
    DROP_ALL
}

data class QueryPolicy(
    val mode: QueryMode = QueryMode.KEEP_ALL,
    /** Extra query keys to strip when mode == STRIP_TRACKING. */
    val extraStripKeys: Set<String> = emptySet()
) {
    private val defaultTrackingKeys = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_reader", "utm_name", "utm_social", "utm_social-type",
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid",
        "mc_cid", "mc_eid", "_ga", "_gl", "yclid",
        "ref", "ref_src", "ref_url"
    )

    /**
     * Returns normalized URL string with query adjusted per policy.
     * Input should already be scheme/host normalized; we only touch query.
     */
    fun applyToNormalizedUrl(normalizedUrl: String): String {
        val qIndex = normalizedUrl.indexOf('?')
        if (qIndex < 0) return normalizedUrl
        val base = normalizedUrl.substring(0, qIndex)
        val query = normalizedUrl.substring(qIndex + 1)
        return when (mode) {
            QueryMode.DROP_ALL -> base
            QueryMode.KEEP_ALL -> normalizedUrl
            QueryMode.STRIP_TRACKING -> {
                val strip = defaultTrackingKeys + extraStripKeys.map { it.lowercase() }
                val kept = query.split('&')
                    .mapNotNull { pair ->
                        val eq = pair.indexOf('=')
                        val key = if (eq >= 0) pair.substring(0, eq) else pair
                        if (key.lowercase() in strip) null else pair
                    }
                    .filter { it.isNotBlank() }
                if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
            }
        }
    }
}
