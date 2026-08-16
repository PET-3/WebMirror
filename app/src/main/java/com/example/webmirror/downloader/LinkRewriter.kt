package com.example.webmirror.downloader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Rewrites links inside HTML (and basic CSS) so the offline mirror is browsable,
 * similar to HTTrack's default "Relative URI" behaviour for internal links.
 */
object LinkRewriter {

    /**
     * @param html original HTML
     * @param pageUrl absolute URL of this page (for resolving relative links)
     * @param pageLocalPath local relative path of this page (under mirror root)
     * @param urlToLocal map of absolute URL → local relative path (only for successfully downloaded resources)
     * @param externalAsOriginal if true, leave external / not-downloaded links as original absolute URLs
     */
    fun rewriteHtml(
        html: String,
        pageUrl: String,
        pageLocalPath: String,
        urlToLocal: Map<String, String>,
        externalAsOriginal: Boolean = true
    ): String {
        val doc: Document = Jsoup.parse(html, pageUrl)

        fun rewriteAttr(el: Element, attr: String) {
            val raw = el.attr(attr)
            if (raw.isBlank() || raw.startsWith("data:") || raw.startsWith("javascript:") ||
                raw.startsWith("mailto:") || raw.startsWith("#")
            ) return

            val abs = try {
                // Jsoup abs: already resolved, but we re-resolve for safety
                el.attr("abs:$attr").ifBlank {
                    URI(pageUrl).resolve(raw).toString()
                }
            } catch (_: Exception) {
                return
            }

            val norm = UrlMapper.normalize(abs) ?: return
            val local = urlToLocal[norm]
            if (local != null) {
                el.attr(attr, UrlMapper.relativeLink(pageLocalPath, local))
            } else if (!externalAsOriginal) {
                // optional: could replace with a placeholder page
            }
            // else leave original
        }

        // Common attributes
        doc.select("[href]").forEach { rewriteAttr(it, "href") }
        doc.select("[src]").forEach { rewriteAttr(it, "src") }
        doc.select("[poster]").forEach { rewriteAttr(it, "poster") }
        doc.select("[data-src]").forEach { rewriteAttr(it, "data-src") }
        doc.select("source[srcset], img[srcset]").forEach { el ->
            // simplistic: take first URL in srcset
            val srcset = el.attr("srcset")
            if (srcset.isNotBlank()) {
                val first = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                if (!first.isNullOrBlank()) {
                    el.attr("src", first)
                    rewriteAttr(el, "src")
                    el.removeAttr("srcset")
                }
            }
        }

        // Inline style url(...)
        doc.select("[style]").forEach { el ->
            val style = el.attr("style")
            if (style.contains("url(", ignoreCase = true)) {
                el.attr("style", rewriteCssUrls(style, pageUrl, pageLocalPath, urlToLocal, externalAsOriginal))
            }
        }

        // <style> blocks
        doc.select("style").forEach { el ->
            el.html(rewriteCssUrls(el.html(), pageUrl, pageLocalPath, urlToLocal, externalAsOriginal))
        }

        return doc.outerHtml()
    }

    fun rewriteCss(
        css: String,
        cssUrl: String,
        cssLocalPath: String,
        urlToLocal: Map<String, String>,
        externalAsOriginal: Boolean = true
    ): String {
        return rewriteCssUrls(css, cssUrl, cssLocalPath, urlToLocal, externalAsOriginal)
    }

    private fun rewriteCssUrls(
        content: String,
        baseUrl: String,
        localPath: String,
        urlToLocal: Map<String, String>,
        externalAsOriginal: Boolean
    ): String {
        // Match url(...) and @import "..."
        val urlRegex = Regex("""url\s*\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
        val importRegex = Regex("""@import\s+(?:url\s*\(\s*)?['"]?([^'")]+)['"]?\s*\)?""", RegexOption.IGNORE_CASE)

        fun replaceUrl(match: MatchResult): String {
            val raw = match.groupValues[1].trim()
            if (raw.startsWith("data:") || raw.startsWith("#")) return match.value
            val abs = try {
                URI(baseUrl).resolve(raw).toString()
            } catch (_: Exception) {
                return match.value
            }
            val norm = UrlMapper.normalize(abs) ?: return match.value
            val local = urlToLocal[norm]
            return if (local != null) {
                val rel = UrlMapper.relativeLink(localPath, local)
                match.value.replace(raw, rel)
            } else {
                match.value
            }
        }

        var result = urlRegex.replace(content, ::replaceUrl)
        result = importRegex.replace(result) { match ->
            val raw = match.groupValues[1].trim()
            if (raw.startsWith("data:")) return@replace match.value
            val abs = try {
                URI(baseUrl).resolve(raw).toString()
            } catch (_: Exception) {
                return@replace match.value
            }
            val norm = UrlMapper.normalize(abs) ?: return@replace match.value
            val local = urlToLocal[norm]
            if (local != null) {
                val rel = UrlMapper.relativeLink(localPath, local)
                match.value.replace(raw, rel)
            } else {
                match.value
            }
        }
        return result
    }
}
