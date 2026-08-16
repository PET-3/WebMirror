package com.example.webmirror.engine.extract

import java.net.URI

/**
 * Minimal sitemap.xml / sitemap_index.xml parser.
 * Extracts <loc> URLs without a full XML library dependency.
 */
object SitemapParser {

    private val LOC_RE = Regex(
        """<loc>\s*([^<]+?)\s*</loc>""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun extractLocs(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return LOC_RE.findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    /** Candidate sitemap URLs for a site root. */
    fun candidateSitemapUrls(seedUrl: String): List<String> {
        return try {
            val uri = URI(seedUrl)
            val origin = "${uri.scheme}://${uri.host}" +
                    if (uri.port != -1) ":${uri.port}" else ""
            listOf(
                "$origin/sitemap.xml",
                "$origin/sitemap_index.xml",
                "$origin/sitemap-index.xml",
                "$origin/wp-sitemap.xml"
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isSitemapPath(path: String): Boolean {
        val p = path.lowercase()
        return p.contains("sitemap") && (p.endsWith(".xml") || p.endsWith(".xml.gz"))
    }
}
