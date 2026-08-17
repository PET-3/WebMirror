package com.example.webmirror.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.example.webmirror.engine.EngineStatus
import com.example.webmirror.engine.model.RunMode
import com.example.webmirror.model.DownloadFormatFilter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StaticDownloadScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit = {},
    onOpenResources: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    embedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val isDownloading =
        state.stats.status == EngineStatus.Running || state.stats.status == EngineStatus.Paused
    val context = LocalContext.current
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!embedded) {
                TopAppBar(
                    title = {
                        Text("静态下载", fontWeight = FontWeight.SemiBold)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // URL
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.url,
                        onValueChange = viewModel::updateUrl,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("网站 URL") },
                        singleLine = true,
                        enabled = !isDownloading,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Text("只下载格式（不选=全部；仍会抓 HTML/CSS/JS 以便发现链接）", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DownloadFormatFilter.PRESETS.forEach { (ext, label) ->
                            val selected = ext in state.formatFilter.extensions
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleFormatExtension(ext) },
                                label = { Text(label) }
                            )
                        }
                        if (state.formatFilter.extensions.isNotEmpty()) {
                            FilterChip(
                                selected = false,
                                onClick = { viewModel.clearFormatFilter() },
                                label = { Text("清除") }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.startDownload(RunMode.FRESH)
                        },
                        enabled = !isDownloading && state.url.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("开始下载")
                    }

                    AnimatedVisibility(visible = isDownloading) {
                        FilledTonalButton(
                            onClick = viewModel::cancelDownload,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Cancel, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("取消")
                        }
                    }
                }
            }

            // Options (compact)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingSwitchRow(
                        title = "链接重写",
                        checked = state.rewriteLinks,
                        onCheckedChange = viewModel::updateRewriteLinks,
                        onInfo = {
                            infoTitle = "链接重写"
                            infoBody =
                                "下载完成后，把 HTML/CSS 中的站内链接改成相对路径，方便离线打开与跳转（类似 HTTrack）。"
                        }
                    )
                    SettingSwitchRow(
                        title = "仅同域名",
                        checked = state.sameDomainOnly,
                        onCheckedChange = viewModel::updateSameDomainOnly,
                        onInfo = {
                            infoTitle = "仅同域名"
                            infoBody =
                                "只下载与起始网址相同域名（及子域）的资源。关闭后会尝试下载 CDN 等外链，体积与时间会明显增加。"
                        }
                    )
                    SettingSwitchRow(
                        title = "遵守 robots",
                        checked = state.respectRobots,
                        onCheckedChange = viewModel::updateRespectRobots,
                        onInfo = {
                            infoTitle = "遵守 robots"
                            infoBody =
                                "读取目标站 robots.txt，跳过其中禁止抓取的路径。个人备份可按需关闭，请仍遵守网站条款与法律。"
                        }
                    )

                    Text("最大深度：${state.maxDepth}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = state.maxDepth.toFloat(),
                        onValueChange = { viewModel.updateMaxDepth(it.toInt()) },
                        valueRange = 0f..50f,
                        steps = 49,
                        enabled = !isDownloading
                    )
                    Text("并发数：${state.maxWorkers}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = state.maxWorkers.toFloat(),
                        onValueChange = { viewModel.updateMaxWorkers(it.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14,
                        enabled = !isDownloading
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.startDownload(RunMode.CONTINUE) },
                            enabled = !isDownloading && state.url.isNotBlank(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("继续") }
                        OutlinedButton(
                            onClick = { viewModel.startDownload(RunMode.UPDATE) },
                            enabled = !isDownloading && state.url.isNotBlank(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("更新") }
                        if (state.stats.status == EngineStatus.Running) {
                            OutlinedButton(
                                onClick = viewModel::pauseDownload,
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("暂停") }
                        }
                        if (state.stats.status == EngineStatus.Paused) {
                            OutlinedButton(
                                onClick = viewModel::resumeDownload,
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("恢复") }
                        }
                    }
                }
            }

            // Progress — only when not idle
            if (state.stats.status != EngineStatus.Idle) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (state.stats.status) {
                            EngineStatus.Completed ->
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            EngineStatus.Error ->
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            else -> MaterialTheme.colorScheme.surfaceContainerLow
                        }
                    )
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            when (state.stats.status) {
                                EngineStatus.Running ->
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                EngineStatus.Completed ->
                                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                EngineStatus.Error ->
                                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Outlined.Info, null)
                            }
                            Text(
                                text = when (state.stats.status) {
                                    EngineStatus.Running -> "正在下载…"
                                    EngineStatus.Paused -> "已暂停"
                                    EngineStatus.Completed -> "下载完成"
                                    EngineStatus.Error -> "出错"
                                    EngineStatus.Cancelled -> "已取消"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (state.stats.status == EngineStatus.Running ||
                            state.stats.status == EngineStatus.Paused
                        ) {
                            val finished =
                                state.stats.downloaded + state.stats.failed + state.stats.skipped
                            val total = state.stats.total.coerceAtLeast(1)
                            val pct = ((finished * 100f) / total).toInt().coerceIn(0, 100)
                            Text(
                                "$finished / ${state.stats.total}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            LinearProgressIndicator(
                                progress = { state.stats.progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "$pct% · 队列 ${state.stats.queued} · 失败 ${state.stats.failed} · 跳过 ${state.stats.skipped}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatSpeedSize(state.stats.bytesDownloaded, state.stats.speedBps),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.stats.currentUrl.isNotBlank()) {
                                Text(
                                    state.stats.currentUrl,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (state.stats.status == EngineStatus.Completed ||
                            state.stats.status == EngineStatus.Error ||
                            state.stats.status == EngineStatus.Cancelled
                        ) {
                            Text(
                                "共保存 ${state.stats.downloaded} 个文件 · 失败 ${state.stats.failed} · 跳过 ${state.stats.skipped}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            state.stats.errorMessage?.let {
                                Text(it, color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.stats.status == EngineStatus.Completed) {
                                Button(
                                    onClick = {
                                        viewModel.refreshStaging()
                                        onOpenResources()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("查看资源 / 导出")
                                }
                            }
                        }
                    }
                }
            }

            StagingBottomPanel(
                viewModel = viewModel,
                source = "static",
                title = "静态暂存",
                onOpenFull = onOpenResources
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    infoTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            title = { Text(title) },
            text = { Text(infoBody) },
            confirmButton = {
                TextButton(onClick = { infoTitle = null }) { Text("知道了") }
            }
        )
    }
}

private fun formatSpeedSize(bytes: Long, speedBps: Long): String {
    fun fmt(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.2f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }
    val speed = if (speedBps <= 0) "—" else fmt(speedBps) + "/s"
    return "已下载 ${fmt(bytes)} · 速度 $speed"
}
