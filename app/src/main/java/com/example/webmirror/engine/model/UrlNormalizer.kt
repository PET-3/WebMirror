package com.example.webmirror.engine.model

import java.net.URI

/**
 * Canonical URL normalization for identity & dedup.
 * Handles scheme/host case, default ports, fragments, path cleanup.
 */
object UrlNormalizer {

    fun normalize(raw: String): String? {
        return try {
            var input = raw.trim()
            if (input.isEmpty()) return null
            if (!input.startsWith("http://") && !input.startsWith("https://")) {
                input = "https://$input"
            }
            val uri = URI(input)

            val scheme = (uri.scheme ?: "https").lowercase()
            val host = (uri.host ?: return null).lowercase()
            val port = when {
                uri.port == -1 -> -1
                scheme == "http" && uri.port == 80 -> -1
                scheme == "https" && uri.port == 443 -> -1
                else -> uri.port
            }

            // Resolve ./ ../ and collapse //
            val path = normalizePath(uri.path ?: "/")
            // Drop fragment entirely
            val query = uri.query

            val authority = if (port != -1) "$host:$port" else host
            val q = if (query.isNullOrBlank()) "" else "?$query"
            "$scheme://$authority$path$q"
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizePath(path: String): String {
        if (path.isEmpty()) return "/"
        val parts = path.split("/")
        val stack = ArrayDeque<String>()
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> { /* skip */ }
                p == ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        val joined = stack.joinToString("/")
        return if (path.endsWith("/") && joined.isNotEmpty()) {
            "/$joined/"
        } else if (joined.isEmpty()) {
            "/"
        } else {
            "/$joined"
        }
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }
}
