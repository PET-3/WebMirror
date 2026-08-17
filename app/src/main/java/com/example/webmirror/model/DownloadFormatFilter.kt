package com.example.webmirror.model

/**
 * Pre-download format selection. Empty set = all formats.
 * HTML/CSS/JS are still fetched for link discovery when [keepDiscoveryDocs] is true.
 */
data class DownloadFormatFilter(
    val extensions: Set<String> = emptySet(),
    val keepDiscoveryDocs: Boolean = true
) {
    fun isUnrestricted(): Boolean = extensions.isEmpty()

    fun allowsPath(path: String, contentType: String?): Boolean {
        if (isUnrestricted()) return true
        val p = path.lowercase().substringBefore('?')
        val ext = p.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext in extensions) return true
        if (keepDiscoveryDocs) {
            if (ext in DISCOVERY_EXTS) return true
            val ct = contentType?.lowercase().orEmpty()
            if ("text/html" in ct || "text/css" in ct || "javascript" in ct) return true
        }
        return false
    }

    companion object {
        val DISCOVERY_EXTS = setOf("html", "htm", "css", "js", "mjs", "cjs", "json", "xml")

        val PRESETS = listOf(
            "html" to "HTML",
            "css" to "CSS",
            "js" to "JS",
            "png" to "PNG",
            "jpg" to "JPG",
            "jpeg" to "JPEG",
            "webp" to "WEBP",
            "gif" to "GIF",
            "svg" to "SVG",
            "woff2" to "字体",
            "mp4" to "视频",
            "mp3" to "音频",
            "json" to "JSON",
            "ktx2" to "KTX2",
            "wasm" to "WASM"
        )
    }
}
