package com.example.webmirror.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webmirror.data.MirrorRepository
import com.example.webmirror.engine.EngineStats
import com.example.webmirror.engine.EngineStatus
import com.example.webmirror.engine.log.MirrorLogger
import com.example.webmirror.engine.model.CrawlLimits
import com.example.webmirror.engine.model.DomainMode
import com.example.webmirror.engine.model.DomainPolicy
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.RunMode
import com.example.webmirror.engine.service.MirrorForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val url: String = "",
    val maxDepth: Int = 5,
    val maxWorkers: Int = 4,
    val sameDomainOnly: Boolean = true,
    val rewriteLinks: Boolean = true,
    val respectRobots: Boolean = true,
    val stats: EngineStats = EngineStats(),
    /** Always the real on-disk path where the engine writes files. */
    val downloadDirDisplay: String = "",
    val treeUri: Uri? = null,
    val treeDisplayName: String? = null,
    val lastExportPath: String? = null,
    val toastMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MirrorRepository(application)
    private val logger = MirrorLogger.get(application)
    private val defaultDir = getDefaultDownloadDir()

    private val _uiState = MutableStateFlow(
        UiState(downloadDirDisplay = defaultDir.absolutePath)
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Avoid exporting the same completion multiple times. */
    private var lastHandledStatus: EngineStatus = EngineStatus.Idle

    init {
        viewModelScope.launch {
            repo.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
                // After successful mirror: export to user-chosen SAF folder if set
                if (stats.status == EngineStatus.Completed && lastHandledStatus != EngineStatus.Completed) {
                    lastHandledStatus = EngineStatus.Completed
                    val tree = _uiState.value.treeUri
                    if (tree != null) {
                        exportMirrorToSaf(tree)
                    } else {
                        _uiState.update {
                            it.copy(
                                toastMessage = "完成：文件在 ${defaultDir.absolutePath}"
                            )
                        }
                    }
                } else if (stats.status != EngineStatus.Completed) {
                    lastHandledStatus = stats.status
                }
            }
        }
    }

    fun updateUrl(url: String) = _uiState.update { it.copy(url = url) }
    fun updateMaxDepth(depth: Int) = _uiState.update { it.copy(maxDepth = depth.coerceAtLeast(0)) }
    fun updateMaxWorkers(n: Int) = _uiState.update { it.copy(maxWorkers = n.coerceIn(1, 16)) }
    fun updateSameDomainOnly(v: Boolean) = _uiState.update { it.copy(sameDomainOnly = v) }
    fun updateRewriteLinks(v: Boolean) = _uiState.update { it.copy(rewriteLinks = v) }
    fun updateRespectRobots(v: Boolean) = _uiState.update { it.copy(respectRobots = v) }

    fun setTreeUri(uri: Uri?, displayName: String) {
        _uiState.update {
            it.copy(
                treeUri = uri,
                treeDisplayName = if (uri != null) displayName else null,
                // Keep showing the real engine path; note user export target separately in UI
                downloadDirDisplay = defaultDir.absolutePath
            )
        }
    }

    fun clearTreeUri() {
        _uiState.update {
            it.copy(
                treeUri = null,
                treeDisplayName = null,
                downloadDirDisplay = defaultDir.absolutePath
            )
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    private fun buildConfig(state: UiState): MirrorConfig {
        return MirrorConfig(
            startUrl = state.url.trim(),
            maxWorkers = state.maxWorkers,
            domainPolicy = DomainPolicy(
                mode = if (state.sameDomainOnly) DomainMode.SAME_HOST else DomainMode.EVERYWHERE
            ),
            limits = CrawlLimits(maxDepth = state.maxDepth),
            rewriteLinks = state.rewriteLinks,
            respectRobots = state.respectRobots
        )
    }

    /**
     * Start mirror. Always runs engine in ViewModel scope (reliable),
     * and best-effort starts ForegroundService for notification.
     *
     * Files are always written to app-specific dir (reliable on Android 10+).
     * If user picked a SAF folder, contents are copied there after completion.
     */
    fun startDownload(mode: RunMode = RunMode.FRESH) {
        val state = _uiState.value
        val url = state.url.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(toastMessage = "请先输入网站 URL") }
            return
        }
        if (state.stats.status == EngineStatus.Running) {
            _uiState.update { it.copy(toastMessage = "已在下载中") }
            return
        }

        val dir = defaultDir
        dir.mkdirs()
        val config = buildConfig(state)
        lastHandledStatus = EngineStatus.Idle
        logger.i("UI", "startDownload mode=$mode url=$url dir=${dir.absolutePath} export=${state.treeUri}")

        try {
            MirrorForegroundService.start(
                context = getApplication(),
                url = url,
                dir = dir.absolutePath,
                depth = state.maxDepth,
                workers = state.maxWorkers,
                sameDomain = state.sameDomainOnly,
                mode = mode,
                respectRobots = state.respectRobots
            )
        } catch (e: Exception) {
            Log.w("MainViewModel", "FGS start failed, fallback to in-process engine", e)
            logger.w("UI", "FGS start failed: ${e.message}")
        }

        viewModelScope.launch {
            try {
                val tip = if (state.treeUri != null) {
                    "开始镜像…（完成后会导出到你选择的目录）"
                } else {
                    "开始镜像… 保存于 ${dir.absolutePath}"
                }
                _uiState.update {
                    it.copy(
                        toastMessage = tip,
                        downloadDirDisplay = dir.absolutePath
                    )
                }
                repo.setRespectRobots(state.respectRobots)
                when (mode) {
                    RunMode.FRESH -> repo.startMirror(config, dir)
                    RunMode.CONTINUE -> repo.continueMirror(config, dir)
                    RunMode.UPDATE -> repo.updateMirror(config, dir)
                }
            } catch (e: Exception) {
                logger.e("UI", "engine start failed", e)
                _uiState.update {
                    it.copy(toastMessage = "启动失败：${e.message ?: "未知错误"}")
                }
            }
        }
    }

    fun cancelDownload() {
        try {
            MirrorForegroundService.stop(getApplication())
        } catch (_: Exception) {
        }
        repo.cancel()
        _uiState.update { it.copy(toastMessage = "已取消") }
    }

    fun pauseDownload() {
        repo.pause()
        _uiState.update { it.copy(toastMessage = "已暂停") }
    }

    fun resumeDownload() {
        repo.unpause()
        _uiState.update { it.copy(toastMessage = "继续下载") }
    }

    /**
     * Copy entire mirror tree from app dir into user-selected SAF folder.
     */
    private fun exportMirrorToSaf(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = "正在导出到所选目录…") }
            val result = withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val root = DocumentFile.fromTreeUri(app, treeUri)
                        ?: return@withContext "无法打开所选目录"
                    val srcRoot = defaultDir
                    if (!srcRoot.exists()) return@withContext "源目录不存在"
                    var count = 0
                    count += copyDirToDocument(srcRoot, root, app)
                    "已导出 $count 个文件到所选目录（本地缓存仍在 ${srcRoot.absolutePath}）"
                } catch (e: Exception) {
                    logger.e("UI", "SAF export failed", e)
                    "导出失败：${e.message ?: "未知错误"}"
                }
            }
            _uiState.update { it.copy(toastMessage = result) }
        }
    }

    private fun copyDirToDocument(src: File, destDir: DocumentFile, app: Application): Int {
        var n = 0
        val children = src.listFiles() ?: return 0
        for (child in children) {
            if (child.isDirectory) {
                var sub = destDir.findFile(child.name)
                if (sub == null || !sub.isDirectory) {
                    sub = destDir.createDirectory(child.name)
                }
                if (sub != null) {
                    n += copyDirToDocument(child, sub, app)
                }
            } else if (child.isFile) {
                val mime = guessMime(child.name)
                var target = destDir.findFile(child.name)
                if (target == null) {
                    target = destDir.createFile(mime, child.name)
                }
                if (target != null) {
                    try {
                        app.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
                            child.inputStream().use { it.copyTo(os) }
                        }
                        n++
                    } catch (e: Exception) {
                        Log.w("MainViewModel", "copy failed ${child.name}: ${e.message}")
                    }
                }
            }
        }
        return n
    }

    private fun guessMime(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") || lower.endsWith(".mjs") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".xml") -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    fun exportLogs(includeAll: Boolean = true) {
        viewModelScope.launch {
            val outDir = File(defaultDir, "exported_logs").also { it.mkdirs() }
            val file = logger.exportTo(outDir, includeAllSessions = includeAll)
            if (file != null) {
                logger.i("UI", "logs exported: ${file.absolutePath}")
                _uiState.update {
                    it.copy(
                        lastExportPath = file.absolutePath,
                        toastMessage = "日志已导出：${file.name}"
                    )
                }
            } else {
                _uiState.update { it.copy(toastMessage = "日志导出失败") }
            }
        }
    }

    fun shareExportedLogs(): Intent? {
        val path = _uiState.value.lastExportPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return try {
            val uri = FileProvider.getUriForFile(
                getApplication(),
                getApplication<Application>().packageName + ".fileprovider",
                file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getDefaultDownloadDir(): File {
        val app = getApplication<Application>()
        val external = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return if (external != null) {
            File(external, "WebMirror").also { it.mkdirs() }
        } else {
            File(app.filesDir, "WebMirror").also { it.mkdirs() }
        }
    }
}
