package com.example.webmirror.export

import android.content.Context
import android.util.Log
import com.example.webmirror.model.FileTypeFilter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ZIP export using method STORED (no compression).
 * Streamed: never loads whole archive into memory.
 * Paths are sanitized against Zip Slip.
 */
class ZipStoredExporter : Exporter {

    override val format: ExportFormat = ExportFormat.ZIP_STORED

    override suspend fun export(
        context: Context,
        request: ExportRequest,
        onProgress: (ExportProgress) -> Unit,
        isCancelled: () -> Boolean
    ): ExportProgress {
        val total = request.resources.size
        if (total == 0) {
            return ExportProgress(done = true, error = "没有可导出的资源")
        }

        val resolver = context.contentResolver
        val tmpUri = request.outputUri
        var current = 0
        val usedNames = mutableMapOf<String, Int>()

        try {
            resolver.openOutputStream(tmpUri, "wt")?.use { rawOs ->
                ZipOutputStream(BufferedOutputStream(rawOs, 64 * 1024)).use { zos ->
                    // STORED requires setMethod + size + crc before write
                    for (res in request.resources) {
                        if (isCancelled()) {
                            return ExportProgress(current, total, cancelled = true, message = "已取消")
                        }
                        val rel = res.localPath ?: continue
                        val file = File(request.mirrorRoot, rel)
                        if (!file.isFile) {
                            current++
                            onProgress(ExportProgress(current, total, "跳过缺失: ${file.name}"))
                            continue
                        }

                        val entryName = uniqueEntryName(sanitizeZipPath(rel), usedNames)
                        val size = file.length()
                        val crc = crc32Of(file)

                        val entry = ZipEntry(entryName).apply {
                            method = ZipEntry.STORED
                            this.size = size
                            this.compressedSize = size
                            this.crc = crc
                            time = file.lastModified()
                        }
                        zos.putNextEntry(entry)
                        BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
                            input.copyTo(zos, 64 * 1024)
                        }
                        zos.closeEntry()

                        current++
                        onProgress(
                            ExportProgress(
                                current = current,
                                total = total,
                                message = "打包 $entryName"
                            )
                        )
                    }
                    zos.finish()
                }
            } ?: return ExportProgress(done = true, error = "无法写入目标文件")
        } catch (e: Exception) {
            Log.e(TAG, "ZIP export failed", e)
            // Best-effort delete broken output
            try {
                resolver.delete(tmpUri, null, null)
            } catch (_: Exception) {
            }
            return ExportProgress(current, total, done = true, error = e.message ?: "ZIP 导出失败")
        }

        return ExportProgress(
            current = current,
            total = total,
            message = "完成",
            done = true,
            outputUri = tmpUri
        )
    }

    private fun crc32Of(file: File): Long {
        val crc = CRC32()
        BufferedInputStream(file.inputStream(), 64 * 1024).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }

    /** Prevent Zip Slip and illegal path chars. */
    private fun sanitizeZipPath(rel: String): String {
        var p = rel.replace('\\', '/')
        while (p.startsWith("/")) p = p.drop(1)
        p = p.split('/').joinToString("/") { seg ->
            seg.replace(Regex("[\\\\:*?\"<>|]"), "_")
                .let { if (it == ".." || it == ".") "_" else it }
        }
        if (p.isBlank()) p = "file_${System.currentTimeMillis()}"
        return p
    }

    private fun uniqueEntryName(base: String, used: MutableMap<String, Int>): String {
        val n = used[base] ?: 0
        used[base] = n + 1
        if (n == 0) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0) {
            base.substring(0, dot) + "_$n" + base.substring(dot)
        } else {
            "${base}_$n"
        }
    }

    companion object {
        private const val TAG = "ZipStoredExporter"
    }
}
