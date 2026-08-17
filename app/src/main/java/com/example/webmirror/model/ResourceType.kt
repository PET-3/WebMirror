package com.example.webmirror.model

/**
 * High-level resource categories for staging filters.
 * Extension-based so new formats (HEIC, JXL, …) can be added without UI rewrite.
 */
enum class ResourceCategory(val label: String) {
    ALL("全部"),
    IMAGE("图片"),
    HTML("HTML"),
    CSS("CSS"),
    JS("JS"),
    FONT("字体"),
    AUDIO("音频"),
    VIDEO("视频"),
    PDF("PDF"),
    TEXTURE("纹理/3D"),
    OTHER("其他")
}

/**
 * File extension filter (lowercase, no dot). Empty set = all extensions in category.
 */
data class FileTypeFilter(
    val category: ResourceCategory = ResourceCategory.ALL,
    /** Selected image extensions when category == IMAGE; empty = all images */
    val imageExtensions: Set<String> = emptySet()
) {
    fun matches(path: String, contentType: String? = null): Boolean {
        val ext = extensionOf(path)
        val cat = categoryOf(path, contentType)
        if (category != ResourceCategory.ALL && cat != category) return false
        if (category == ResourceCategory.IMAGE && imageExtensions.isNotEmpty()) {
            return ext in imageExtensions
        }
        return true
    }

    companion object {
        val IMAGE_EXTENSIONS = listOf(
            "jpg", "jpeg", "png", "webp", "gif", "svg", "avif", "bmp", "tiff", "tif", "ico", "heic", "jxl"
        )

        fun extensionOf(path: String): String {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return ""
            return name.substring(dot + 1).lowercase()
        }

        fun categoryOf(path: String, contentType: String? = null): ResourceCategory {
            val ext = extensionOf(path)
            val ct = contentType?.lowercase().orEmpty()
            return when {
                ext in IMAGE_EXTENSIONS || ct.startsWith("image/") -> ResourceCategory.IMAGE
                ext in listOf("html", "htm", "xhtml") || ct.contains("text/html") -> ResourceCategory.HTML
                ext == "css" || ct.contains("text/css") -> ResourceCategory.CSS
                ext in listOf("js", "mjs", "cjs") || ct.contains("javascript") -> ResourceCategory.JS
                ext in listOf("woff", "woff2", "ttf", "otf", "eot") || ct.startsWith("font/") -> ResourceCategory.FONT
                ext in listOf("mp3", "wav", "ogg", "m4a", "flac", "aac") || ct.startsWith("audio/") -> ResourceCategory.AUDIO
                ext in listOf("mp4", "webm", "mkv", "mov", "m4v") || ct.startsWith("video/") -> ResourceCategory.VIDEO
                ext == "pdf" || ct.contains("pdf") -> ResourceCategory.PDF
                ext in listOf("ktx", "ktx2", "basis", "dds", "glb", "gltf", "hdr", "exr", "wasm", "bin") ->
                    ResourceCategory.TEXTURE
                else -> ResourceCategory.OTHER
            }
        }

        fun displayName(path: String): String = path.substringAfterLast('/').ifBlank { path }
    }
}

enum class ResourceSort {
    TIME_DESC, TIME_ASC, NAME_ASC, NAME_DESC, SIZE_DESC, SIZE_ASC, TYPE
}
