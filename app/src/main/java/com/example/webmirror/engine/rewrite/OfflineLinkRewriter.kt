package com.example.webmirror.engine.rewrite

import com.example.webmirror.engine.model.UrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Offline link rewriter (Phase 4).
 * - Rewrites href/src/poster/data/action etc. to relative local paths
 * - Rewrites ALL srcset candidates (does NOT drop srcset)
 * - Rewrites CSS url() / @import inside <style> and style=""
 */
object OfflineLinkRewriter {

    /**
     * @param urlToLocal map of normalized absolute URL → relative local path under mirror root
     */
    fun rewriteHtml(
        html: String,
        pageUrl: String,
        pageLocalPath: String,
        urlToLocal: Map<String, String>
    ): String {
        val doc = try {
            Jsoup.parse(html, pageUrl)
        } catch (_: Exception) {
            return html
        }

        fun mapAttr(el: Element, attr: String) {
            val raw = el.attr(attr)
            if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("javascript:") ||
                raw.startsWith("mailto:") || raw.startsWith("#")
            ) return
            val abs = el.attr("abs:$attr").ifBlank {
                resolve(pageUrl, raw) ?: return
            }
            val norm = normalizeKey(abs) ?: return
            val local = urlToLocal[norm] ?: return
            el.attr(attr, relative(pageLocalPath, local))
        }

        doc.select("[href]").forEach { mapAttr(it, "href") }
        doc.select("[src]").forEach { mapAttr(it, "src") }
        doc.select("[poster]").forEach { mapAttr(it, "poster") }
        doc.select("[data-src]").forEach { mapAttr(it, "data-src") }
        doc.select("[data]").forEach { mapAttr(it, "data") }
        doc.select("form[action]").forEach { mapAttr(it, "action") }

        // srcset: rewrite every candidate, keep descriptors
        doc.select("[srcset]").forEach { el ->
            el.attr("srcset", rewriteSrcset(el.attr("srcset"), pageUrl, pageLocalPath, urlToLocal))
        }
        doc.select("[data-srcset]").forEach { el ->
            el.attr("data-srcset", rewriteSrcset(el.attr("data-srcset"), pageUrl, pageLocalPath, urlToLocal))
        }

        doc.select("[style]").forEach { el ->
            el.attr(
                "style",
                rewriteCss(el.attr("style"), pageUrl, pageLocalPath, urlToLocal)
            )
        }
        doc.select("style").forEach { el ->
            val body = el.data().ifBlank { el.html() }
            el.html(rewriteCss(body, pageUrl, pageLocalPath, urlToLocal))
        }

        return doc.outerHtml()
    }

    fun rewriteCss(
        css: String,
        cssUrl: String,
        cssLocalPath: String,
        urlToLocal: Map<String, String>
    ): String {
        if (css.isBlank()) return css
        val urlRe = Regex("""url\s*\(\s*(['"]?)([^'")]+)\1\s*\)""", RegexOption.IGNORE_CASE)
        val importRe = Regex(
            """@import\s+(?:url\s*\(\s*)?(['"]?)([^'")\s;]+)\1\s*\)?""",
            RegexOption.IGNORE_CASE
        )

        fun replaceRef(raw: String): String {
            if (raw.startsWith("data:") || raw.startsWith("#")) return raw
            val abs = resolve(cssUrl, raw) ?: return raw
            val norm = normalizeKey(abs) ?: return raw
            val local = urlToLocal[norm] ?: return raw
            return relative(cssLocalPath, local)
        }

        var out = urlRe.replace(css) { m ->
            val quote = m.groupValues[1]
            val ref = m.groupValues[2]
            val neu = replaceRef(ref)
            "url($quote$neu$quote)"
        }
        out = importRe.replace(out) { m ->
            val quote = m.groupValues[1]
            val ref = m.groupValues[2]
            val neu = replaceRef(ref)
            m.value.replace(ref, neu)
        }
        return out
    }

    private fun rewriteSrcset(
        srcset: String,
        baseUrl: String,
        pageLocalPath: String,
        urlToLocal: Map<String, String>
    ): String {
        if (srcset.isBlank()) return srcset
        return srcset.split(',')
            .joinToString(", ") { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) return@joinToString trimmed
                val tokens = trimmed.split(Regex("\\s+"))
                if (tokens.isEmpty()) return@joinToString trimmed
                val urlPart = tokens[0]
                val descriptors = tokens.drop(1).joinToString(" ")
                val abs = resolve(baseUrl, urlPart) ?: return@joinToString trimmed
                val norm = normalizeKey(abs) ?: return@joinToString trimmed
                val local = urlToLocal[norm] ?: return@joinToString trimmed
                val rel = relative(pageLocalPath, local)
                if (descriptors.isBlank()) rel else "$rel $descriptors"
            }
    }

    private fun normalizeKey(url: String): String? {
        // Must match UrlNormalizer used when enqueue/save so map lookups succeed
        return UrlNormalizer.normalize(url)
    }

    private fun resolve(base: String, ref: String): String? {
        return try {
            URI(base).resolve(ref).toString().substringBefore('#')
        } catch (_: Exception) {
            null
        }
    }

    private fun relative(fromPath: String, toPath: String): String {
        // Prefer LocalPathMapper helper if available via same logic
        val fromParts = fromPath.split("/").dropLast(1)
        val toParts = toPath.split("/")
        var i = 0
        while (i < fromParts.size && i < toParts.size && fromParts[i] == toParts[i]) i++
        val up = "../".repeat(fromParts.size - i)
        val down = toParts.drop(i).joinToString("/")
        val rel = up + down
        return if (rel.isEmpty()) toParts.last() else rel
    }
}
