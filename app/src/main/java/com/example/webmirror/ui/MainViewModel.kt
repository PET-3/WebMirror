package com.example.webmirror.ui

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webmirror.downloader.DownloadProgress
import com.example.webmirror.downloader.DownloadStatus
import com.example.webmirror.downloader.WebsiteDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class UiState(
    val url: String = "",
    val maxDepth: Int = 2,
    val sameDomainOnly: Boolean = true,
    val progress: DownloadProgress = DownloadProgress(),
    val downloadDir: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val downloader = WebsiteDownloader()

    private val _uiState = MutableStateFlow(
        UiState(
            downloadDir = getDefaultDownloadDir().absolutePath
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloader.progress.collect { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
        }
    }

    fun updateUrl(url: String) {
        _uiState.update { it.copy(url = url) }
    }

    fun updateMaxDepth(depth: Int) {
        _uiState.update { it.copy(maxDepth = depth.coerceIn(0, 5)) }
    }

    fun updateSameDomainOnly(value: Boolean) {
        _uiState.update { it.copy(sameDomainOnly = value) }
    }

    fun startDownload() {
        val state = _uiState.value
        if (state.url.isBlank()) return
        if (state.progress.status == DownloadStatus.Downloading) return

        val dir = File(state.downloadDir)
        viewModelScope.launch {
            downloader.download(
                startUrl = state.url.trim(),
                outputDir = dir,
                maxDepth = state.maxDepth,
                sameDomainOnly = state.sameDomainOnly
            )
        }
    }

    fun cancelDownload() {
        downloader.cancel()
    }

    private fun getDefaultDownloadDir(): File {
        val app = getApplication<Application>()
        // Prefer external files dir (no special permission needed on modern Android)
        val external = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return if (external != null) {
            File(external, "WebMirror").also { it.mkdirs() }
        } else {
            File(app.filesDir, "WebMirror").also { it.mkdirs() }
        }
    }
}
