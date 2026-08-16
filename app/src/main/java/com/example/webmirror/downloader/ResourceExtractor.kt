package com.example.webmirror.downloader

import com.example.webmirror.engine.extract.CssResourceExtractor
import com.example.webmirror.engine.extract.HtmlResourceExtractor

/**
 * Backward-compatible facade. New code should use engine.extract.* directly.
 */
object ResourceExtractor {
    fun extractFromHtml(html: String, baseUrl: String): Set<String> =
        HtmlResourceExtractor.extract(html, baseUrl)

    fun extractFromCss(css: String, baseUrl: String): Set<String> =
        CssResourceExtractor.extract(css, baseUrl)
}
