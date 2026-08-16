package com.example.webmirror.downloader

import java.net.URI
import java.security.MessageDigest

/**
 * Maps remote URLs to stable local relative paths (HTTrack-style directory structure).
 * Also provides helpers for computing relative links between two local paths.
 */
object UrlMapper {

    fun normalize(url: String): String? {
        return try {
            var u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                u = "https://$u"
            }
            val uri = URI(u)
            // Drop fragment
            val clean = URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, uri.query, null)
            clean.toString().removeSuffix("/").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Convert absolute URL → relative path under mirror root, e.g.
     * https://example.com/docs/a.html → example.com/docs/a.html
     * https://example.com/ → example.com/index.html
     * https://example.com/page?id=1 → example.com/page_q_id_1.html (sanitized)
     */
    fun urlToRelativePath(url: String): String? {
        val norm = normalize(url) ?: return null
        val uri = try {
            URI(norm)
        } catch (_: Exception) {
            return null
        }
        val host = (uri.host ?: "unknown").replace(Regex("[\\\\:*?\"<>|]"), "_")
        var path = uri.path ?: "/"
        if (path.isEmpty() || path.endsWith("/")) {
            path += "index.html"
        }
        // Sanitize path segments
        val segments = path.split("/").filter { it.isNotEmpty() }.map { seg ->
            seg.replace(Regex("[\\\\:*?\"<>|]"), "_")
        }
        var filePath = segments.joinToString("/")

        // Handle query string: append sanitized query to filename to avoid collisions
        val query = uri.query
        if (!query.isNullOrBlank()) {
            val qSafe = query
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                .take(80)
            val dot = filePath.lastIndexOf('.')
            filePath = if (dot > 0) {
                filePath.substring(0, dot) + "_q_" + qSafe + filePath.substring(dot)
            } else {
                filePath + "_q_" + qSafe
            }
        }

        // Ensure HTML-like pages have extension
        if (!filePath.contains('.') && looksLikeHtmlPath(filePath)) {
            filePath += ".html"
        }

        return "$host/$filePath"
    }

    private fun looksLikeHtmlPath(path: String): Boolean {
        val lower = path.lowercase()
        return !lower.endsWith(".css") && !lower.endsWith(".js") &&
                !lower.endsWith(".png") && !lower.endsWith(".jpg") &&
                !lower.endsWith(".jpeg") && !lower.endsWith(".gif") &&
                !lower.endsWith(".svg") && !lower.endsWith(".webp") &&
                !lower.endsWith(".woff") && !lower.endsWith(".woff2") &&
                !lower.endsWith(".ttf") && !lower.endsWith(".ico") &&
                !lower.endsWith(".json") && !lower.endsWith(".xml") &&
                !lower.endsWith(".pdf") && !lower.endsWith(".mp4") &&
                !lower.endsWith(".webm")
    }

    /**
     * Compute a relative href from one local path to another (both under mirror root).
     * Example: from "example.com/a/b.html" to "example.com/c/d.png" → "../../c/d.png"
     */
    fun relativeLink(fromPath: String, toPath: String): String {
        val fromParts = fromPath.split("/").dropLast(1) // directory of current file
        val toParts = toPath.split("/")
        var i = 0
        while (i < fromParts.size && i < toParts.size && fromParts[i] == toParts[i]) {
            i++
        }
        val up = "../".repeat(fromParts.size - i)
        val down = toParts.drop(i).joinToString("/")
        val rel = up + down
        return if (rel.isEmpty()) toParts.last() else rel
    }

    fun shortHash(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(8)
    }
}
