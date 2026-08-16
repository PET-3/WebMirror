package com.example.webmirror.engine.model

import java.net.URI
import java.security.MessageDigest

/**
 * Stable remote URL → local relative path mapping.
 * Guarantees different resources do not collide on the same file when possible.
 */
object LocalPathMapper {

    fun toRelativePath(normalizedUrl: String): String? {
        return try {
            val uri = URI(normalizedUrl)
            val host = (uri.host ?: "unknown").replace(Regex("[\\\\:*?\"<>|]"), "_")
            var path = uri.path ?: "/"
            if (path.isEmpty() || path.endsWith("/")) {
                path += "index.html"
            }
            val segments = path.split("/").filter { it.isNotEmpty() }.map {
                it.replace(Regex("[\\\\:*?\"<>|]"), "_")
            }
            var filePath = segments.joinToString("/")

            val query = uri.query
            if (!query.isNullOrBlank()) {
                val qSafe = query.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
                val hash = shortHash(query)
                val dot = filePath.lastIndexOf('.')
                filePath = if (dot > 0) {
                    filePath.substring(0, dot) + "_q_" + qSafe.take(40) + "_" + hash + filePath.substring(dot)
                } else {
                    filePath + "_q_" + qSafe.take(40) + "_" + hash
                }
            }

            if (!filePath.contains('.') && looksLikePage(filePath)) {
                filePath += ".html"
            }

            "$host/$filePath"
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikePage(path: String): Boolean {
        val lower = path.lowercase()
        val assetExt = listOf(
            ".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp",
            ".woff", ".woff2", ".ttf", ".otf", ".ico", ".json", ".xml", ".pdf",
            ".mp4", ".webm", ".mp3", ".wav", ".wasm", ".zip"
        )
        return assetExt.none { lower.endsWith(it) }
    }

    private fun shortHash(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
    }
}
