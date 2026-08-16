package com.example.webmirror.engine.extract

import java.net.URI

/**
 * CSS resource discovery: url(), @import, @font-face sources, image-set().
 */
object CssResourceExtractor {

    // url("...") / url('...') / url(...)
    private val URL_RE = Regex(
        """url\s*\(\s*(['"]?)([^'")]+)\1\s*\)""",
        RegexOption.IGNORE_CASE
    )

    // @import "x";  @import url(x);  @import 'x' screen;
    private val IMPORT_RE = Regex(
        """@import\s+(?:url\s*\(\s*)?(['"]?)([^'")\s;]+)\1\s*\)?""",
        RegexOption.IGNORE_CASE
    )

    // image-set( url(a) 1x, url(b) 2x ) or image-set("a" 1x, "b" 2x)
    private val IMAGE_SET_RE = Regex(
        """image-set\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(css: String, baseUrl: String): Set<String> {
        if (css.isBlank()) return emptySet()
        val urls = linkedSetOf<String>()

        fun collect(raw: String) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("data:") || t.startsWith("#")) return
            resolve(baseUrl, t)?.let { urls.add(it) }
        }

        URL_RE.findAll(css).forEach { collect(it.groupValues[2]) }
        IMPORT_RE.findAll(css).forEach { collect(it.groupValues[2]) }

        IMAGE_SET_RE.findAll(css).forEach { match ->
            val inner = match.groupValues[1]
            // Pull url(...) or quoted strings inside image-set
            URL_RE.findAll(inner).forEach { collect(it.groupValues[2]) }
            Regex("""(['"])([^'"]+)\1""").findAll(inner).forEach { collect(it.groupValues[2]) }
        }

        return urls
    }

    private fun resolve(base: String, ref: String): String? {
        return try {
            URI(base).resolve(ref.trim()).toString().substringBefore('#')
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }
}
