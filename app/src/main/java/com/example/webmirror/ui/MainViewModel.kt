package com.example.webmirror.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

    init {
        viewModelScope.launch {
            repo.stats.collect { stats ->
                _uiState.update { it.copy(stats = stats) }
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
                downloadDirDisplay = if (uri != null) displayName else defaultDir.absolutePath
            )
        }
    }

    fun clearTreeUri() {
        _uiState.update { it.copy(treeUri = null, downloadDirDisplay = defaultDir.absolutePath) }
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
        val config = buildConfig(state)
        logger.i("UI", "startDownload mode=$mode url=$url dir=${dir.absolutePath}")

        // 1) Best-effort notification service (may fail on some OEMs without killing download)
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

        // 2) Always start engine here so button always does something
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(toastMessage = "开始镜像…") }
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
