package com.example.webmirror.export

import android.content.Context
import android.net.Uri
import com.example.webmirror.data.ResourceEntity
import java.io.File

enum class ExportFormat(val label: String, val mimeType: String, val defaultExtension: String) {
    ZIP_STORED("ZIP（无压缩）", "application/zip", "zip"),
    PDF("PDF", "application/pdf", "pdf"),
    HTML("HTML 画廊", "text/html", "html")
}

enum class ExportScope {
    /** Currently checked items */
    SELECTED,
    /** Current filter result (all matching rows) */
    FILTERED,
    /** Entire mirror project */
    ENTIRE_MIRROR
}

data class ExportRequest(
    val format: ExportFormat,
    val scope: ExportScope,
    val resources: List<ResourceEntity>,
    val mirrorRoot: File,
    /** SAF destination URI (file create document) */
    val outputUri: Uri,
    val title: String = "WebMirror Export"
)

data class ExportProgress(
    val current: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val done: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
    val outputUri: Uri? = null
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}

interface Exporter {
    val format: ExportFormat
    /**
     * Stream-based export. Must not load entire dataset into one giant byte[].
     * Call [onProgress] periodically. Return final [ExportProgress] with done=true.
     */
    suspend fun export(
        context: Context,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean
    ): ExportProgress
}

/**
 * Facade for all export formats. Easy to plug Gallery / Markdown / EPUB later.
 */
class ExportManager(private val exporters: List<Exporter>) {

    fun availableFormats(): List<ExportFormat> = exporters.map { it.format }

    fun exporterFor(format: ExportFormat): Exporter? =
        exporters.firstOrNull { it.format == format }

    companion object {
        fun createDefault(): ExportManager = ExportManager(
            listOf(ZipStoredExporter(), PdfImageExporter(), HtmlGalleryExporter())
        )
    }
}
