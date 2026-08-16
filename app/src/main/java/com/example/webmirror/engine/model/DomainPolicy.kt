package com.example.webmirror.engine.model

/**
 * Controls which hosts may be crawled (HTTrack-like scope).
 */
enum class DomainMode {
    /** Only the exact host of the seed (www.example.com ≠ example.com). */
    SAME_HOST,
    /** Registrable-style: seed host and its parent domain match loosely via suffix. */
    SAME_DOMAIN,
    /** Seed host + any subdomain (*.example.com). */
    ALLOW_SUBDOMAINS,
    /** Explicit allow-list. */
    SELECTED_DOMAINS,
    /** No host restriction. */
    EVERYWHERE
}

data class DomainPolicy(
    val mode: DomainMode = DomainMode.SAME_HOST,
    /** Used when mode == SELECTED_DOMAINS (lowercase hosts). */
    val allowedHosts: Set<String> = emptySet(),
    /** Extra CDN / asset hosts always allowed (e.g. cdn.example.net). */
    val extraAllowedHosts: Set<String> = emptySet()
) {
    /**
     * @param seedHost host of the start URL (lowercase)
     * @param candidateHost host of the URL under test (lowercase)
     */
    fun allows(seedHost: String?, candidateHost: String?): Boolean {
        if (candidateHost.isNullOrBlank()) return false
        val host = candidateHost.lowercase()
        val seed = seedHost?.lowercase()

        if (extraAllowedHosts.any { it.equals(host, true) || host.endsWith(".$it") }) {
            return true
        }

        return when (mode) {
            DomainMode.EVERYWHERE -> true
            DomainMode.SAME_HOST -> seed != null && host == seed
            DomainMode.ALLOW_SUBDOMAINS -> {
                if (seed == null) return false
                host == seed || host.endsWith(".$seed")
            }
            DomainMode.SAME_DOMAIN -> {
                if (seed == null) return false
                val seedRoot = registrableHint(seed)
                val hostRoot = registrableHint(host)
                host == seed || host.endsWith(".$seed") || hostRoot == seedRoot
            }
            DomainMode.SELECTED_DOMAINS -> {
                allowedHosts.any { allowed ->
                    host == allowed || host.endsWith(".$allowed")
                }
            }
        }
    }

    /**
     * Very lightweight eTLD+1 hint (not a full Public Suffix List).
     * Good enough for common cases: example.com, example.co.uk still imperfect.
     */
    private fun registrableHint(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        // Keep last two labels as a simple default
        return parts.takeLast(2).joinToString(".")
    }
}
