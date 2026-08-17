package com.example.webmirror.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.util.Log
import com.example.webmirror.model.FileTypeFilter
import com.example.webmirror.model.ResourceCategory
import java.io.BufferedOutputStream
import java.io.File

/**
 * First-phase PDF export: one image per page, fit to A4 with small margins.
 * Does not modify original files. SVG/AVIF best-effort via BitmapFactory (may fail).
 */
class PdfImageExporter : Exporter {

    override val format: ExportFormat = ExportFormat.PDF

    override suspend fun export(
        context: Context,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean
    ): ExportProgress {
        val images = request.resources.filter {
            val path = it.localPath ?: return@filter false
            FileTypeFilter.categoryOf(path, it.contentType) == ResourceCategory.IMAGE
        }
        val total = images.size
        if (total == 0) {
            return ExportProgress(done = true, error = "没有可导出的图片")
        }

        // A4 at ~72 dpi points: 595 x 842
        val pageW = 595
        val pageH = 842
        val margin = 24f

        val doc = PdfDocument()
        var current = 0
        try {
            for (res in images) {
                if (isCancelled()) {
                    doc.close()
                    try {
                        context.contentResolver.delete(request.outputUri, null, null)
                    } catch (_: Exception) {
                    }
                    return ExportProgress(current, total, cancelled = true, message = "已取消")
                }
                val rel = res.localPath ?: continue
                val file = File(request.mirrorRoot, rel)
                if (!file.isFile) {
                    current++
                    continue
                }

                val bitmap = decodeSampled(file, pageW * 2, pageH * 2)
                if (bitmap == null) {
                    current++
                    onProgress(ExportProgress(current, total, "无法解码: ${file.name}"))
                    continue
                }

                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, current + 1).create()
                    val page = doc.startPage(pageInfo)
                    val canvas = page.canvas

                    val maxW = pageW - margin * 2
                    val maxH = pageH - margin * 2
                    val scale = minOf(maxW / bitmap.width, maxH / bitmap.height, 1f)
                    val dw = bitmap.width * scale
                    val dh = bitmap.height * scale
                    val left = (pageW - dw) / 2f
                    val top = (pageH - dh) / 2f

                    canvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF(left, top, left + dw, top + dh),
                        null
                    )
                    doc.finishPage(page)
                } finally {
                    bitmap.recycle()
                }

                current++
                onProgress(ExportProgress(current, total, "写入 ${file.name}"))
            }

            context.contentResolver.openOutputStream(request.outputUri, "wt")?.use { os ->
                BufferedOutputStream(os).use { bos ->
                    doc.writeTo(bos)
                }
            } ?: run {
                doc.close()
                return ExportProgress(done = true, error = "无法写入 PDF")
            }
            doc.close()
        } catch (e: Exception) {
            Log.e(TAG, "PDF export failed", e)
            try {
                doc.close()
            } catch (_: Exception) {
            }
            try {
                context.contentResolver.delete(request.outputUri, null, null)
            } catch (_: Exception) {
            }
            return ExportProgress(current, total, done = true, error = e.message ?: "PDF 导出失败")
        }

        return ExportProgress(
            current = current,
            total = total,
            message = "完成",
            done = true,
            outputUri = request.outputUri
        )
    }

    private fun decodeSampled(file: File, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (e: Exception) {
            Log.w(TAG, "decode failed ${file.name}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "PdfImageExporter"
    }
}
