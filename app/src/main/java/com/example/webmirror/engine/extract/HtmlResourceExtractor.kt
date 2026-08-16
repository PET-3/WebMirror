package com.example.webmirror.engine.extract

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Comprehensive HTML resource discovery (Phase 4).
 * Covers anchors, assets, media, srcset (all candidates), meta/og, forms, etc.
 */
object HtmlResourceExtractor {

    fun extract(html: String, baseUrl: String): Set<String> {
        val urls = linkedSetOf<String>()
        val doc = try {
            Jsoup.parse(html, baseUrl)
        } catch (_: Exception) {
            return emptySet()
        }

        fun addAbs(el: Element, attr: String) {
            val abs = el.attr("abs:$attr")
            addHttp(abs, urls)
        }

        // Links & stylesheets
        doc.select("a[href]").forEach { addAbs(it, "href") }
        doc.select("link[href]").forEach { addAbs(it, "href") }
        doc.select("area[href]").forEach { addAbs(it, "href") }
        doc.select("base[href]").forEach { addAbs(it, "href") }

        // Scripts
        doc.select("script[src]").forEach { addAbs(it, "src") }

        // Images & media
        doc.select("img[src]").forEach { addAbs(it, "src") }
        doc.select("img[data-src]").forEach { addAbs(it, "data-src") }
        doc.select("source[src]").forEach { addAbs(it, "src") }
        doc.select("video[src], audio[src]").forEach { addAbs(it, "src") }
        doc.select("video[poster]").forEach { addAbs(it, "poster") }
        doc.select("track[src]").forEach { addAbs(it, "src") }
        doc.select("embed[src]").forEach { addAbs(it, "src") }
        doc.select("object[data]").forEach { addAbs(it, "data") }
        doc.select("iframe[src], frame[src]").forEach { addAbs(it, "src") }

        // Forms (may point to same-site pages)
        doc.select("form[action]").forEach { addAbs(it, "action") }

        // srcset / data-srcset — keep ALL candidates
        doc.select("[srcset], [data-srcset]").forEach { el ->
            val raw = el.attr("srcset").ifBlank { el.attr("data-srcset") }
            parseSrcset(raw).forEach { candidate ->
                resolve(baseUrl, candidate)?.let { urls.add(it) }
            }
        }

        // Meta / Open Graph / Twitter cards
        doc.select("meta[property=og:image], meta[name=og:image], meta[name=twitter:image]").forEach {
            val content = it.attr("content")
            resolve(baseUrl, content)?.let { u -> urls.add(u) }
        }
        doc.select("meta[property=og:url], meta[name=og:url]").forEach {
            resolve(baseUrl, it.attr("content"))?.let { u -> urls.add(u) }
        }

        // Inline style + <style>
        doc.select("[style]").forEach {
            urls.addAll(CssResourceExtractor.extract(it.attr("style"), baseUrl))
        }
        doc.select("style").forEach {
            urls.addAll(CssResourceExtractor.extract(it.data().ifBlank { it.html() }, baseUrl))
        }

        return urls
    }

    /**
     * Parse srcset into URL candidates only (ignore descriptors like 1x, 480w).
     */
    fun parseSrcset(srcset: String): List<String> {
        if (srcset.isBlank()) return emptyList()
        return srcset.split(',')
            .map { it.trim() }
            .mapNotNull { part ->
                if (part.isEmpty()) return@mapNotNull null
                // URL may contain spaces only as separator before descriptor
                val tokens = part.split(Regex("\\s+"))
                tokens.firstOrNull()?.takeIf { it.isNotBlank() }
            }
    }

    private fun addHttp(url: String, out: MutableSet<String>) {
        val clean = url.substringBefore('#').trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            out.add(clean)
        }
    }

    private fun resolve(base: String, ref: String): String? {
        if (ref.isBlank() || ref.startsWith("data:") || ref.startsWith("javascript:") ||
            ref.startsWith("mailto:") || ref.startsWith("#")
        ) return null
        return try {
            URI(base).resolve(ref.trim()).toString().substringBefore('#')
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }
}
