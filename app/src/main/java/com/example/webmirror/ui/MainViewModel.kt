package com.example.webmirror.ui

import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webmirror.data.MirrorRepository
import com.example.webmirror.engine.EngineStats
import com.example.webmirror.engine.EngineStatus
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
    val treeUri: Uri? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = MirrorRepository(application)
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
        if (state.url.isBlank()) return
        if (state.stats.status == EngineStatus.Running) return

        val dir = defaultDir // SAF tree write path handled later; engine uses File root for now
        val app = getApplication<Application>()
        MirrorForegroundService.start(
            context = app,
            url = state.url.trim(),
            dir = dir.absolutePath,
            depth = state.maxDepth,
            workers = state.maxWorkers,
            sameDomain = state.sameDomainOnly,
            mode = mode,
            respectRobots = state.respectRobots
        )
    }

    fun cancelDownload() {
        MirrorForegroundService.stop(getApplication())
        repo.cancel()
    }

    fun pauseDownload() = repo.pause()
    fun resumeDownload() = repo.unpause()

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
