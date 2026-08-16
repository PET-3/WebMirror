package com.example.webmirror.engine.extract

import com.example.webmirror.engine.model.MimeTypeResolver

/**
 * Dispatches content to the right extractor based on MIME / path.
 */
object ContentAnalyzer {

    fun discoverUrls(
        bytesOrText: String,
        contentType: String?,
        localPath: String,
        baseUrl: String
    ): Set<String> {
        val kind = MimeTypeResolver.resolve(contentType, localPath)
        val p = localPath.lowercase()
        val out = linkedSetOf<String>()

        when (kind) {
            MimeTypeResolver.Kind.HTML -> out.addAll(HtmlResourceExtractor.extract(bytesOrText, baseUrl))
            MimeTypeResolver.Kind.CSS -> out.addAll(CssResourceExtractor.extract(bytesOrText, baseUrl))
            MimeTypeResolver.Kind.JS -> out.addAll(JsResourceExtractor.extract(bytesOrText, baseUrl))
            MimeTypeResolver.Kind.JSON -> {
                // manifest.json or generic JSON with URLs
                if (p.contains("manifest")) {
                    out.addAll(ManifestExtractor.extract(bytesOrText, baseUrl))
                }
            }
            MimeTypeResolver.Kind.XML -> {
                if (SitemapParser.isSitemapPath(p) || bytesOrText.contains("<urlset", ignoreCase = true) ||
                    bytesOrText.contains("<sitemapindex", ignoreCase = true)
                ) {
                    out.addAll(SitemapParser.extractLocs(bytesOrText))
                }
            }
            else -> {
                when {
                    p.endsWith(".html") || p.endsWith(".htm") || !p.contains('.') ->
                        out.addAll(HtmlResourceExtractor.extract(bytesOrText, baseUrl))
                    p.endsWith(".css") ->
                        out.addAll(CssResourceExtractor.extract(bytesOrText, baseUrl))
                    p.endsWith(".js") || p.endsWith(".mjs") ->
                        out.addAll(JsResourceExtractor.extract(bytesOrText, baseUrl))
                    p.endsWith("manifest.json") ->
                        out.addAll(ManifestExtractor.extract(bytesOrText, baseUrl))
                    p.endsWith(".xml") && p.contains("sitemap") ->
                        out.addAll(SitemapParser.extractLocs(bytesOrText))
                }
            }
        }
        return out
    }
}
