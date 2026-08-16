package com.example.webmirror.engine.extract

import java.net.URI

/**
 * Static JS resource discovery only — does NOT execute JavaScript.
 * Finds import/export paths, fetch/axios URLs, and obvious asset string literals.
 */
object JsResourceExtractor {

    private val importRe = Regex(
        """(?:import|export)\s+(?:[^'"\n]+from\s+)?['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val dynamicImportRe = Regex(
        """import\s*\(\s*['"]([^'"]+)['"]\s*\)""",
        RegexOption.IGNORE_CASE
    )
    private val fetchRe = Regex(
        """(?:fetch|axios\.(?:get|post|put|delete|head|patch)|XMLHttpRequest)\s*\(\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    // Quoted paths that look like static assets
    private val assetLiteralRe = Regex(
        """['"]((?:https?:)?/?/?[^'"]+\.(?:js|mjs|cjs|css|png|jpe?g|gif|webp|svg|woff2?|ttf|otf|wasm|json|map))['"]""",
        RegexOption.IGNORE_CASE
    )

    fun extract(js: String, baseUrl: String): Set<String> {
        if (js.isBlank()) return emptySet()
        val out = linkedSetOf<String>()

        fun add(raw: String) {
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("data:")) return
            // Skip pure API-looking paths without asset extension when from fetch — still allow
            resolve(baseUrl, t)?.let { out.add(it) }
        }

        importRe.findAll(js).forEach { add(it.groupValues[1]) }
        dynamicImportRe.findAll(js).forEach { add(it.groupValues[1]) }
        fetchRe.findAll(js).forEach { add(it.groupValues[1]) }
        assetLiteralRe.findAll(js).forEach { add(it.groupValues[1]) }

        return out
    }

    private fun resolve(base: String, ref: String): String? {
        return try {
            // protocol-relative
            val fixed = when {
                ref.startsWith("//") -> {
                    val scheme = URI(base).scheme ?: "https"
                    "$scheme:$ref"
                }
                else -> ref
            }
            URI(base).resolve(fixed).toString().substringBefore('#')
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }
}
