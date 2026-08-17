package com.example.webmirror.export

import android.content.Context
import android.util.Log
import com.example.webmirror.model.FileTypeFilter
import com.example.webmirror.model.ResourceCategory
import java.io.BufferedOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline HTML image gallery export.
 * Output is a single ZIP containing:
 *   index.html + assets/ + style.css + assets/script.js
 * All local — no CDN. Visual direction inspired by clean gallery UX (not a copy).
 *
 * For CreateDocument we write a .zip that holds the gallery folder structure,
 * OR if user picks .html we still write a zip named *.html.zip for completeness.
 * Spec asks for index.html + assets/; packaging as STORED zip is the practical
 * single-file SAF deliverable on Android.
 */
class HtmlGalleryExporter : Exporter {

    override val format: ExportFormat = ExportFormat.HTML

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
        val total = images.size + 3 // images + html + css + js
        if (images.isEmpty()) {
            return ExportProgress(done = true, error = "没有可导出的图片")
        }

        var current = 0
        val used = mutableMapOf<String, Int>()
        val items = mutableListOf<GalleryItem>()

        try {
            context.contentResolver.openOutputStream(request.outputUri, "wt")?.use { rawOs ->
                // Package gallery as STORED zip for atomic SAF write
                ZipOutputStream(BufferedOutputStream(rawOs, 64 * 1024)).use { zos ->
                    fun putStored(name: String, bytes: ByteArray) {
                        val crc = java.util.zip.CRC32().also { it.update(bytes) }
                        val e = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = bytes.size.toLong()
                            this.crc = crc.value
                        }
                        zos.putNextEntry(e)
                        zos.write(bytes)
                        zos.closeEntry()
                    }

                    fun putFile(name: String, file: File) {
                        val size = file.length()
                        val crc = java.util.zip.CRC32()
                        file.inputStream().use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                crc.update(buf, 0, n)
                            }
                        }
                        val e = ZipEntry(name).apply {
                            method = ZipEntry.STORED
                            this.size = size
                            compressedSize = size
                            this.crc = crc.value
                        }
                        zos.putNextEntry(e)
                        file.inputStream().use { it.copyTo(zos, 64 * 1024) }
                        zos.closeEntry()
                    }

                    for (res in images) {
                        if (isCancelled()) {
                            return ExportProgress(current, total, cancelled = true, message = "已取消")
                        }
                        val rel = res.localPath ?: continue
                        val file = File(request.mirrorRoot, rel)
                        if (!file.isFile) {
                            current++
                            continue
                        }
                        val baseName = FileTypeFilter.displayName(rel)
                        val safe = uniqueName(sanitize(baseName), used)
                        val assetPath = "assets/$safe"
                        putFile(assetPath, file)
                        items.add(
                            GalleryItem(
                                fileName = safe,
                                assetPath = assetPath,
                                sizeBytes = file.length(),
                                sourceUrl = res.normalizedUrl
                            )
                        )
                        current++
                        onProgress(ExportProgress(current, total, "复制 $safe"))
                    }

                    val css = STYLE_CSS.toByteArray(StandardCharsets.UTF_8)
                    putStored("assets/style.css", css)
                    current++
                    onProgress(ExportProgress(current, total, "写入 style.css"))

                    val js = SCRIPT_JS.toByteArray(StandardCharsets.UTF_8)
                    putStored("assets/script.js", js)
                    current++
                    onProgress(ExportProgress(current, total, "写入 script.js"))

                    val html = buildIndexHtml(request.title, items).toByteArray(StandardCharsets.UTF_8)
                    putStored("index.html", html)
                    current++
                    onProgress(ExportProgress(current, total, "写入 index.html"))

                    zos.finish()
                }
            } ?: return ExportProgress(done = true, error = "无法写入目标文件")
        } catch (e: Exception) {
            Log.e(TAG, "HTML export failed", e)
            try {
                context.contentResolver.delete(request.outputUri, null, null)
            } catch (_: Exception) {
            }
            return ExportProgress(current, total, done = true, error = e.message ?: "HTML 导出失败")
        }

        return ExportProgress(
            current = current,
            total = total,
            message = "完成（ZIP 内含 index.html + assets，解压后离线打开）",
            done = true,
            outputUri = request.outputUri
        )
    }

    private data class GalleryItem(
        val fileName: String,
        val assetPath: String,
        val sizeBytes: Long,
        val sourceUrl: String
    )

    private fun buildIndexHtml(title: String, items: List<GalleryItem>): String {
        val cards = items.mapIndexed { idx, it ->
            val sizeLabel = formatSize(it.sizeBytes)
            val escName = escapeHtml(it.fileName)
            val escUrl = escapeHtml(it.sourceUrl)
            """
            <article class="card" data-index="$idx">
              <button class="thumb" type="button" data-src="${escapeHtml(it.assetPath)}" data-name="$escName" aria-label="查看 $escName">
                <img src="${escapeHtml(it.assetPath)}" alt="$escName" loading="lazy" />
              </button>
              <div class="meta">
                <div class="name" title="$escName">$escName</div>
                <div class="sub">$sizeLabel</div>
              </div>
            </article>
            """.trimIndent()
        }.joinToString("\n")

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>${escapeHtml(title)}</title>
<link rel="stylesheet" href="assets/style.css"/>
</head>
<body>
<header class="top">
  <h1>${escapeHtml(title)}</h1>
  <p class="sub">${items.size} 张图片 · 离线画廊</p>
</header>
<main class="grid">
$cards
</main>
<div id="lightbox" class="lightbox" hidden>
  <button type="button" class="lb-close" aria-label="关闭">×</button>
  <button type="button" class="lb-prev" aria-label="上一张">‹</button>
  <img id="lb-img" alt=""/>
  <button type="button" class="lb-next" aria-label="下一张">›</button>
  <div id="lb-caption" class="lb-caption"></div>
</div>
<script src="assets/script.js"></script>
</body>
</html>
        """.trimIndent()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "image" }

    private fun uniqueName(base: String, used: MutableMap<String, Int>): String {
        val n = used[base] ?: 0
        used[base] = n + 1
        if (n == 0) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) + "_$n" + base.substring(dot) else "${base}_$n"
    }

    companion object {
        private const val TAG = "HtmlGalleryExporter"

        private val STYLE_CSS: String = """
:root {
  --bg: #F6F4EE;
  --card: #E8E4D9;
  --text: #38352E;
  --muted: #5C5850;
  --accent: #5E7A94;
  --shadow: 0 1px 2px rgba(0,0,0,.06), 0 4px 16px rgba(94,122,148,.12);
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #15140F;
    --card: #26241D;
    --text: #E5E1D6;
    --muted: #C9C3B4;
    --accent: #A9C3D8;
    --shadow: 0 1px 2px rgba(0,0,0,.4), 0 4px 20px rgba(0,0,0,.35);
  }
}
html, body, div, article, button, img, header, main {
  box-sizing: border-box;
}
body {
  margin: 0;
  font-family: system-ui, -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif;
  background: var(--bg);
  color: var(--text);
  padding: 20px 16px 64px;
}
.top { max-width: 1100px; margin: 0 auto 20px; }
.top h1 { margin: 0; font-size: 1.5rem; font-weight: 700; }
.top .sub { margin: 4px 0 0; color: var(--muted); font-size: .9rem; }
.grid {
  max-width: 1100px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 14px;
}
.card {
  background: var(--card);
  border-radius: 18px;
  overflow: hidden;
  box-shadow: var(--shadow);
}
.thumb {
  display: block;
  width: 100%;
  border: 0;
  padding: 0;
  cursor: pointer;
  background: #000;
  aspect-ratio: 1;
}
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
  background: #111;
}
.meta { padding: 10px 12px; }
.meta .name {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta .sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
.lightbox {
  position: fixed; inset: 0;
  background: rgba(0,0,0,.88);
  display: flex; align-items: center; justify-content: center;
  z-index: 100;
  padding: 48px 56px 72px;
}
.lightbox[hidden] { display: none !important; }
.lightbox img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  border-radius: 8px;
}
.lb-close, .lb-prev, .lb-next {
  position: absolute;
  border: 0;
  background: rgba(255,255,255,.12);
  color: #fff;
  font-size: 28px;
  width: 44px; height: 44px;
  border-radius: 50%;
  cursor: pointer;
}
.lb-close { top: 12px; right: 12px; }
.lb-prev { left: 12px; top: 50%; transform: translateY(-50%); }
.lb-next { right: 12px; top: 50%; transform: translateY(-50%); }
.lb-caption {
  position: absolute; bottom: 16px; left: 0; right: 0;
  text-align: center; color: #eee; font-size: 14px;
}
@media (max-width: 600px) {
  .grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .lightbox { padding: 56px 12px 64px; }
}
""".trimIndent()

        private val SCRIPT_JS: String = """
(function () {
  var lb = document.getElementById("lightbox");
  var img = document.getElementById("lb-img");
  var cap = document.getElementById("lb-caption");
  var thumbs = Array.prototype.slice.call(document.querySelectorAll(".thumb"));
  var index = 0;
  var items = thumbs.map(function (t) {
    return { src: t.getAttribute("data-src"), name: t.getAttribute("data-name") || "" };
  });

  function openAt(i) {
    if (!items.length) return;
    index = (i + items.length) % items.length;
    img.src = items[index].src;
    img.alt = items[index].name;
    cap.textContent = items[index].name + " (" + (index + 1) + "/" + items.length + ")";
    lb.hidden = false;
  }
  function closeLb() { lb.hidden = true; img.src = ""; }
  function prev() { openAt(index - 1); }
  function next() { openAt(index + 1); }

  thumbs.forEach(function (t, i) {
    t.addEventListener("click", function () { openAt(i); });
  });
  document.querySelector(".lb-close").addEventListener("click", closeLb);
  document.querySelector(".lb-prev").addEventListener("click", prev);
  document.querySelector(".lb-next").addEventListener("click", next);
  lb.addEventListener("click", function (e) { if (e.target === lb) closeLb(); });
  document.addEventListener("keydown", function (e) {
    if (lb.hidden) return;
    if (e.key === "Escape") closeLb();
    if (e.key === "ArrowLeft") prev();
    if (e.key === "ArrowRight") next();
  });
})();
""".trimIndent()
    }
}
