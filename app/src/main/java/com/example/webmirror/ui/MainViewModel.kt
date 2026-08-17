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
import com.example.webmirror.data.AppPreferences
import com.example.webmirror.data.DefaultSaveFormat
import com.example.webmirror.data.MirrorRepository
import com.example.webmirror.data.ResourceEntity
import com.example.webmirror.engine.EngineStats
import com.example.webmirror.engine.EngineStatus
import com.example.webmirror.engine.log.MirrorLogger
import com.example.webmirror.engine.model.CrawlLimits
import com.example.webmirror.engine.model.DomainMode
import com.example.webmirror.engine.model.DomainPolicy
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.RunMode
import com.example.webmirror.engine.service.MirrorForegroundService
import com.example.webmirror.export.ExportFormat
import com.example.webmirror.export.ExportManager
import com.example.webmirror.export.ExportProgress
import com.example.webmirror.export.ExportRequest
import com.example.webmirror.export.ExportScope
import com.example.webmirror.model.FileTypeFilter
import com.example.webmirror.model.ResourceCategory
import com.example.webmirror.model.ResourceSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.webmirror.util.StoragePaths
import java.io.File

data class UiState(
    val url: String = "",
    val maxDepth: Int = 5,
    val maxWorkers: Int = 4,
    val sameDomainOnly: Boolean = true,
    val rewriteLinks: Boolean = true,
    val respectRobots: Boolean = true,
    val stats: EngineStats = EngineStats(),
    val downloadDirDisplay: String = "",
    val treeUri: Uri? = null,
    val treeDisplayName: String? = null,
    val lastExportPath: String? = null,
    val toastMessage: String? = null,
    // Staging / resources
    val stagingResources: List<ResourceEntity> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val filter: FileTypeFilter = FileTypeFilter(),
    val resourceQuery: String = "",
    val resourceSort: ResourceSort = ResourceSort.TIME_DESC,
    val exportProgress: ExportProgress? = null,
    // Settings
    val defaultSaveFormat: DefaultSaveFormat = DefaultSaveFormat.FOLDER,
    val saveLocationDisplay: String = "",
    val autoCleanLogs: Boolean = true,
    val logRetentionDays: Int = 7
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MirrorRepository(application)
    private val logger = MirrorLogger.get(application)
    private val prefs = AppPreferences(application)
    private var defaultDir: File = StoragePaths.getDownloadDir(application)
    private val exportManager = ExportManager.createDefault()

    private val _uiState = MutableStateFlow(
        UiState(
            downloadDirDisplay = defaultDir.absolutePath,
            maxDepth = prefs.maxDepth,
            maxWorkers = prefs.maxWorkers,
            sameDomainOnly = prefs.sameDomainOnly,
            rewriteLinks = prefs.rewriteLinks,
            respectRobots = prefs.respectRobots,
            defaultSaveFormat = prefs.defaultSaveFormat,
            autoCleanLogs = prefs.autoCleanLogs,
            logRetentionDays = prefs.logRetentionDays,
            saveLocationDisplay = resolveSaveLocationLabel(),
            treeUri = prefs.resolvedTreeUri()
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var lastHandledStatus: EngineStatus = EngineStatus.Idle
    private var exportJob: Job? = null
    @Volatile private var exportCancelled = false
    private var pendingExportFormat: ExportFormat = ExportFormat.ZIP_STORED
    private var pendingExportScope: ExportScope = ExportScope.SELECTED

    init {
        refreshDownloadDir()
        runLogAutoCleanIfEnabled()
        viewModelScope.launch {
            repo.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
                if (stats.status == EngineStatus.Completed && lastHandledStatus != EngineStatus.Completed) {
                    lastHandledStatus = EngineStatus.Completed
                    refreshStaging()
                    val tree = _uiState.value.treeUri
                    if (tree != null) {
                        exportMirrorToSaf(tree)
                    } else {
                        _uiState.update {
                            it.copy(toastMessage = "完成：可打开「资源暂存」整理并导出")
                        }
                    }
                } else if (stats.status != EngineStatus.Completed) {
                    lastHandledStatus = stats.status
                }
            }
        }
    }

    fun updateUrl(url: String) = _uiState.update { it.copy(url = url) }
    fun updateMaxDepth(depth: Int) {
        val d = depth.coerceAtLeast(0)
        prefs.maxDepth = d
        _uiState.update { it.copy(maxDepth = d) }
    }
    fun updateMaxWorkers(n: Int) {
        val w = n.coerceIn(1, 16)
        prefs.maxWorkers = w
        _uiState.update { it.copy(maxWorkers = w) }
    }
    fun updateSameDomainOnly(v: Boolean) {
        prefs.sameDomainOnly = v
        _uiState.update { it.copy(sameDomainOnly = v) }
    }
    fun updateRewriteLinks(v: Boolean) {
        prefs.rewriteLinks = v
        _uiState.update { it.copy(rewriteLinks = v) }
    }
    fun updateRespectRobots(v: Boolean) {
        prefs.respectRobots = v
        _uiState.update { it.copy(respectRobots = v) }
    }

    fun setTreeUri(uri: Uri?, displayName: String) {
        prefs.saveTreeUri = uri?.toString()
        _uiState.update {
            it.copy(
                treeUri = uri,
                treeDisplayName = if (uri != null) displayName else null,
                downloadDirDisplay = defaultDir.absolutePath,
                saveLocationDisplay = if (uri != null) displayName else prefs.defaultPublicDir().absolutePath
            )
        }
    }

    fun clearTreeUri() {
        _uiState.update {
            it.copy(treeUri = null, treeDisplayName = null, downloadDirDisplay = defaultDir.absolutePath)
        }
    }

    fun clearToast() = _uiState.update { it.copy(toastMessage = null) }

    fun showToast(msg: String) = _uiState.update { it.copy(toastMessage = msg) }

    fun mirrorRoot(): File = defaultDir

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
        logger.i("UI", "startDownload mode=$mode url=$url dir=${dir.absolutePath}")

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
            Log.w("MainViewModel", "FGS start failed", e)
        }

        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(
                        toastMessage = "开始镜像…",
                        downloadDirDisplay = dir.absolutePath,
                        selectedIds = emptySet()
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
                _uiState.update { it.copy(toastMessage = "启动失败：${e.message ?: "未知错误"}") }
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

    // ——— Staging / resources ———

    fun refreshStaging() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.allDownloadedResources() }
            _uiState.update { it.copy(stagingResources = list) }
        }
    }

    fun updateResourceQuery(q: String) = _uiState.update { it.copy(resourceQuery = q) }

    fun setCategory(cat: ResourceCategory) {
        _uiState.update {
            it.copy(
                filter = it.filter.copy(
                    category = cat,
                    imageExtensions = if (cat != ResourceCategory.IMAGE) emptySet() else it.filter.imageExtensions
                )
            )
        }
    }

    fun toggleImageExt(ext: String) {
        _uiState.update {
            val cur = it.filter.imageExtensions
            val next = if (ext in cur) cur - ext else cur + ext
            it.copy(filter = it.filter.copy(imageExtensions = next))
        }
    }

    fun setSort(sort: ResourceSort) = _uiState.update { it.copy(resourceSort = sort) }

    fun toggleSelection(id: Long) {
        _uiState.update {
            val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = next)
        }
    }

    fun filteredResources(): List<ResourceEntity> {
        val s = _uiState.value
        var list = s.stagingResources.filter { res ->
            val path = res.localPath ?: return@filter false
            s.filter.matches(path, res.contentType)
        }
        val q = s.resourceQuery.trim().lowercase()
        if (q.isNotEmpty()) {
            list = list.filter {
                val path = it.localPath.orEmpty().lowercase()
                path.contains(q) || it.normalizedUrl.lowercase().contains(q) ||
                    FileTypeFilter.extensionOf(path).contains(q.trimStart('.'))
            }
        }
        return when (s.resourceSort) {
            ResourceSort.TIME_DESC -> list.sortedByDescending { it.updatedAt }
            ResourceSort.TIME_ASC -> list.sortedBy { it.updatedAt }
            ResourceSort.NAME_ASC -> list.sortedBy { it.localPath.orEmpty() }
            ResourceSort.NAME_DESC -> list.sortedByDescending { it.localPath.orEmpty() }
            ResourceSort.SIZE_DESC -> list.sortedByDescending { it.contentLength ?: 0L }
            ResourceSort.SIZE_ASC -> list.sortedBy { it.contentLength ?: 0L }
            ResourceSort.TYPE -> list.sortedBy { FileTypeFilter.extensionOf(it.localPath.orEmpty()) }
        }
    }

    fun isAllFilteredSelected(): Boolean {
        val ids = filteredResources().map { it.id }.toSet()
        return ids.isNotEmpty() && ids.all { it in _uiState.value.selectedIds }
    }

    fun toggleSelectAllFiltered() {
        val ids = filteredResources().map { it.id }.toSet()
        _uiState.update {
            if (ids.isNotEmpty() && ids.all { id -> id in it.selectedIds }) {
                it.copy(selectedIds = it.selectedIds - ids)
            } else {
                it.copy(selectedIds = it.selectedIds + ids)
            }
        }
    }

    fun removeSelected(deleteFiles: Boolean) {
        val ids = _uiState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (deleteFiles) {
                    repo.removeResources(ids, defaultDir, deleteFiles = true)
                } else {
                    // Only clear selection — do not touch DB or mirror files
                }
            }
            if (deleteFiles) {
                _uiState.update {
                    it.copy(
                        selectedIds = emptySet(),
                        stagingResources = it.stagingResources.filter { r -> r.id !in ids.toSet() },
                        toastMessage = "已删除 ${ids.size} 个镜像文件"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        selectedIds = emptySet(),
                        toastMessage = "已移出选择（镜像文件保留）"
                    )
                }
            }
        }
    }

    // ——— Export ———

    fun prepareExport(format: ExportFormat, scope: ExportScope) {
        pendingExportFormat = format
        pendingExportScope = scope
    }

    fun suggestExportFileName(format: ExportFormat): String {
        val host = try {
            val u = _uiState.value.url.trim().ifBlank { "mirror" }
            java.net.URI(if (u.startsWith("http")) u else "https://$u").host ?: "mirror"
        } catch (_: Exception) {
            "mirror"
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date())
        // HTML is packaged as zip of gallery
        val ext = when (format) {
            ExportFormat.HTML -> "zip"
            else -> format.defaultExtension
        }
        return "webmirror-$host-$stamp.$ext"
    }

    fun startExportToUri(uri: Uri) {
        val format = pendingExportFormat
        val scope = pendingExportScope
        val state = _uiState.value
        val resources = when (scope) {
            ExportScope.SELECTED -> state.stagingResources.filter { it.id in state.selectedIds }
            ExportScope.FILTERED -> filteredResources()
            ExportScope.ENTIRE_MIRROR -> state.stagingResources
        }
        if (resources.isEmpty()) {
            _uiState.update { it.copy(toastMessage = "没有可导出的资源") }
            return
        }
        val exporter = exportManager.exporterFor(format)
        if (exporter == null) {
            _uiState.update { it.copy(toastMessage = "不支持的导出格式") }
            return
        }

        exportCancelled = false
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(exportProgress = ExportProgress(0, resources.size, "准备导出…"))
            }
            val result = withContext(Dispatchers.IO) {
                exporter.export(
                    context = getApplication(),
                    request = ExportRequest(
                        format = format,
                        scope = scope,
                        resources = resources,
                        mirrorRoot = defaultDir,
                        outputUri = uri,
                        title = "WebMirror · $format"
                    ),
                    onProgress = { p ->
                        _uiState.update { it.copy(exportProgress = p) }
                    },
                    isCancelled = { exportCancelled }
                )
            }
            _uiState.update {
                it.copy(
                    exportProgress = result,
                    toastMessage = when {
                        result.cancelled -> "导出已取消"
                        result.error != null -> "导出失败：${result.error}"
                        else -> "导出完成"
                    },
                    lastExportPath = uri.toString()
                )
            }
        }
    }

    fun cancelExport() {
        exportCancelled = true
        exportJob?.cancel()
        _uiState.update {
            it.copy(
                exportProgress = it.exportProgress?.copy(cancelled = true, done = true, message = "已取消"),
                toastMessage = "已取消导出"
            )
        }
    }

    private fun exportMirrorToSaf(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(toastMessage = "正在导出到所选目录…") }
            val result = withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val root = DocumentFile.fromTreeUri(app, treeUri)
                        ?: return@withContext "无法打开所选目录"
                    var count = 0
                    count += copyDirToDocument(defaultDir, root, app)
                    "已导出 $count 个文件到所选目录"
                } catch (e: Exception) {
                    "导出失败：${e.message}"
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
                if (sub == null || !sub.isDirectory) sub = destDir.createDirectory(child.name)
                if (sub != null) n += copyDirToDocument(child, sub, app)
            } else if (child.isFile) {
                val mime = guessMime(child.name)
                var target = destDir.findFile(child.name)
                if (target == null) target = destDir.createFile(mime, child.name)
                if (target != null) {
                    try {
                        app.contentResolver.openOutputStream(target.uri, "wt")?.use { os ->
                            child.inputStream().use { it.copyTo(os) }
                        }
                        n++
                    } catch (_: Exception) {
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
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    fun exportLogs(includeAll: Boolean = true) {
        viewModelScope.launch {
            val outDir = File(defaultDir, "exported_logs").also { it.mkdirs() }
            val file = logger.exportTo(outDir, includeAllSessions = includeAll)
            if (file != null) {
                _uiState.update {
                    it.copy(lastExportPath = file.absolutePath, toastMessage = "日志已导出：${file.name}")
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


    /**
     * WebView capture: register a file so it appears in staging (Room).
     */
    fun recordCapturedResource(url: String, byteSize: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val normalized = com.example.webmirror.engine.model.UrlNormalizer.normalize(url) ?: url
                val dao = repo.resourceDao()
                if (dao.findByNormalizedUrl(normalized) != null) return@launch
                val rel = com.example.webmirror.engine.model.LocalPathMapper.toRelativePath(normalized)
                dao.insert(
                    com.example.webmirror.data.ResourceEntity(
                        url = url,
                        normalizedUrl = normalized,
                        localPath = rel,
                        depth = 0,
                        status = com.example.webmirror.data.ResourceStatus.DOWNLOADED.name,
                        contentLength = byteSize.toLong(),
                        contentType = null
                    )
                )
            } catch (e: Exception) {
                android.util.Log.w("MainViewModel", "recordCaptured: ${e.message}")
            }
        }
    }

    fun onBrowserCaptureFinished(count: Int) {
        refreshStaging()
        _uiState.update {
            it.copy(toastMessage = "浏览器捕获结束：约 $count 个资源，可打开资源暂存")
        }
    }


    fun refreshDownloadDir() {
        defaultDir = StoragePaths.getDownloadDir(getApplication())
        defaultDir.mkdirs()
        _uiState.update {
            it.copy(
                downloadDirDisplay = defaultDir.absolutePath,
                saveLocationDisplay = defaultDir.absolutePath
            )
        }
    }

    fun setCustomDownloadDir(path: String) {
        StoragePaths.setDownloadDir(getApplication(), path)
        refreshDownloadDir()
        showToast("下载目录已更新")
    }

    fun resetDownloadDirToDefault() {
        StoragePaths.setDownloadDir(getApplication(), null)
        // Prefer public Download/WebMirror
        val pub = StoragePaths.defaultPublicDownloadDir()
        try {
            pub.mkdirs()
            StoragePaths.setDownloadDir(getApplication(), pub.absolutePath)
        } catch (_: Exception) {
        }
        refreshDownloadDir()
        showToast("已恢复默认 Download/WebMirror")
    }

    fun isLogAutoCleanEnabled(): Boolean =
        StoragePaths.isLogAutoCleanEnabled(getApplication())

    fun setLogAutoCleanEnabled(v: Boolean) {
        StoragePaths.setLogAutoCleanEnabled(getApplication(), v)
    }

    fun logKeepDays(): Int = StoragePaths.logKeepDays(getApplication())

    fun setLogKeepDays(days: Int) {
        StoragePaths.setLogKeepDays(getApplication(), days)
    }

    fun logDirSizeBytes(): Long = logger.logDirSizeBytes()

    fun clearAllLogs() {
        logger.clearAll()
        showToast("日志已清空")
    }

    fun runLogAutoCleanIfEnabled() {
        if (!StoragePaths.isLogAutoCleanEnabled(getApplication())) return
        val n = logger.autoClean(StoragePaths.logKeepDays(getApplication()))
        if (n > 0) logger.i("Log", "auto-clean deleted $n files")
    }


    private fun resolveSaveLocationLabel(): String {
        return StoragePaths.getDownloadDir(getApplication()).absolutePath
    }

    fun setDefaultSaveFormat(format: DefaultSaveFormat) {
        prefs.defaultSaveFormat = format
        _uiState.update { it.copy(defaultSaveFormat = format) }
    }

    fun setAutoCleanLogs(v: Boolean) {
        prefs.autoCleanLogs = v
        StoragePaths.setLogAutoCleanEnabled(getApplication(), v)
        _uiState.update { it.copy(autoCleanLogs = v) }
    }

    fun setLogRetentionDays(days: Int) {
        prefs.logRetentionDays = days
        StoragePaths.setLogKeepDays(getApplication(), days)
        _uiState.update { it.copy(logRetentionDays = prefs.logRetentionDays) }
    }

    fun resetSaveLocationToDefault() {
        prefs.saveTreeUri = null
        StoragePaths.setDownloadDir(getApplication(), null)
        refreshDownloadDir()
        _uiState.update {
            it.copy(
                treeUri = null,
                treeDisplayName = null,
                saveLocationDisplay = resolveSaveLocationLabel()
            )
        }
    }

    fun cleanLogsNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = logger.autoClean(prefs.logRetentionDays)
            _uiState.update { it.copy(toastMessage = "已清理 $n 个过期日志") }
        }
    }

    fun runStartupMaintenance() {
        if (!prefs.autoCleanLogs) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { logger.autoClean(prefs.logRetentionDays) }
        }
    }

    private fun getDefaultDownloadDir(): File {
        // Working cache always under app-specific dir (reliable).
        // User-facing "save location" is public Download/WebMirror or SAF (settings).
        val app = getApplication<Application>()
        val external = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return if (external != null) {
            File(external, "WebMirror").also { it.mkdirs() }
        } else {
            File(app.filesDir, "WebMirror").also { it.mkdirs() }
        }
    }

    /** User-visible save root for finished exports / folder copies. */
    fun userSaveDir(): File = prefs.defaultPublicDir()

}
