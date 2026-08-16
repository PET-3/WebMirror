package com.example.webmirror.engine.model

/**
 * Resolve MIME / kind from Content-Type header, then extension, then magic bytes.
 */
object MimeTypeResolver {

    enum class Kind {
        HTML, CSS, JS, IMAGE, FONT, AUDIO, VIDEO, JSON, XML, WASM, PDF, ARCHIVE, OTHER
    }

    fun kindFromContentType(contentType: String?): Kind {
        val ct = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
        return when {
            ct.contains("text/html") || ct.contains("application/xhtml") -> Kind.HTML
            ct.contains("text/css") -> Kind.CSS
            ct.contains("javascript") || ct.contains("ecmascript") -> Kind.JS
            ct.startsWith("image/") -> Kind.IMAGE
            ct.startsWith("font/") || ct.contains("font-") || ct.contains("woff") -> Kind.FONT
            ct.startsWith("audio/") -> Kind.AUDIO
            ct.startsWith("video/") -> Kind.VIDEO
            ct.contains("application/json") || ct == "text/json" -> Kind.JSON
            ct.contains("xml") -> Kind.XML
            ct.contains("wasm") -> Kind.WASM
            ct.contains("pdf") -> Kind.PDF
            ct.contains("zip") || ct.contains("gzip") || ct.contains("tar") -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }

    fun kindFromPath(path: String): Kind {
        val p = path.lowercase()
        return when {
            p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".xhtml") -> Kind.HTML
            p.endsWith(".css") -> Kind.CSS
            p.endsWith(".js") || p.endsWith(".mjs") || p.endsWith(".cjs") -> Kind.JS
            p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") ||
                    p.endsWith(".gif") || p.endsWith(".webp") || p.endsWith(".svg") ||
                    p.endsWith(".ico") || p.endsWith(".avif") || p.endsWith(".bmp") -> Kind.IMAGE
            p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") ||
                    p.endsWith(".otf") || p.endsWith(".eot") -> Kind.FONT
            p.endsWith(".mp3") || p.endsWith(".wav") || p.endsWith(".ogg") ||
                    p.endsWith(".m4a") || p.endsWith(".flac") -> Kind.AUDIO
            p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".mkv") ||
                    p.endsWith(".mov") -> Kind.VIDEO
            p.endsWith(".json") -> Kind.JSON
            p.endsWith(".xml") -> Kind.XML
            p.endsWith(".wasm") -> Kind.WASM
            p.endsWith(".pdf") -> Kind.PDF
            p.endsWith(".zip") || p.endsWith(".gz") || p.endsWith(".tar") -> Kind.ARCHIVE
            else -> Kind.OTHER
        }
    }

    fun resolve(contentType: String?, path: String, magic: ByteArray? = null): Kind {
        val fromCt = kindFromContentType(contentType)
        if (fromCt != Kind.OTHER) return fromCt
        val fromPath = kindFromPath(path)
        if (fromPath != Kind.OTHER) return fromPath
        if (magic != null && magic.size >= 4) {
            // PNG
            if (magic[0] == 0x89.toByte() && magic[1] == 0x50.toByte()) return Kind.IMAGE
            // JPEG
            if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) return Kind.IMAGE
            // GIF
            if (magic[0] == 'G'.code.toByte() && magic[1] == 'I'.code.toByte()) return Kind.IMAGE
            // PDF
            if (magic[0] == '%'.code.toByte() && magic[1] == 'P'.code.toByte()) return Kind.PDF
            // ZIP
            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) return Kind.ARCHIVE
        }
        return Kind.OTHER
    }

    fun extensionForKind(kind: Kind, fallbackPath: String): String {
        val existing = fallbackPath.substringAfterLast('.', "")
        if (existing.isNotBlank() && existing.length <= 5) return fallbackPath
        val ext = when (kind) {
            Kind.HTML -> "html"
            Kind.CSS -> "css"
            Kind.JS -> "js"
            Kind.IMAGE -> "bin"
            Kind.FONT -> "woff2"
            Kind.AUDIO -> "mp3"
            Kind.VIDEO -> "mp4"
            Kind.JSON -> "json"
            Kind.XML -> "xml"
            Kind.WASM -> "wasm"
            Kind.PDF -> "pdf"
            Kind.ARCHIVE -> "zip"
            Kind.OTHER -> "bin"
        }
        return if (fallbackPath.contains('.')) fallbackPath else "$fallbackPath.$ext"
    }
}
