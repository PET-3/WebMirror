package com.example.webmirror.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.webmirror.data.DefaultSaveFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPickSaveDirectory: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }

    fun showInfo(title: String, body: String) {
        infoTitle = title
        infoBody = body
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsSection("保存") {
                Text("默认下载方式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                FormatRadio(
                    label = "文件夹",
                    selected = state.defaultSaveFormat == DefaultSaveFormat.FOLDER,
                    onClick = { viewModel.setDefaultSaveFormat(DefaultSaveFormat.FOLDER) }
                )
                FormatRadio(
                    label = "ZIP（无压缩）",
                    selected = state.defaultSaveFormat == DefaultSaveFormat.ZIP,
                    onClick = { viewModel.setDefaultSaveFormat(DefaultSaveFormat.ZIP) }
                )

                Spacer(Modifier.height(8.dp))
                Text("保存位置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    state.saveLocationDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestStoragePermission) {
                        Text("申请存储权限")
                    }
                    OutlinedButton(onClick = onPickSaveDirectory) {
                        Text("更改位置")
                    }
                }
                if (state.treeUri != null) {
                    TextButton(onClick = { viewModel.resetSaveLocationToDefault() }) {
                        Text("恢复为 Download/WebMirror")
                    }
                }
            }

            SettingsSection("抓取默认值") {
                SettingSwitchRow(
                    title = "链接重写",
                    checked = state.rewriteLinks,
                    onCheckedChange = viewModel::updateRewriteLinks,
                    onInfo = {
                        showInfo(
                            "链接重写",
                            "下载完成后，把 HTML/CSS 中的站内链接改成相对路径，方便离线打开与跳转（类似 HTTrack）。"
                        )
                    }
                )
                SettingSwitchRow(
                    title = "仅同域名",
                    checked = state.sameDomainOnly,
                    onCheckedChange = viewModel::updateSameDomainOnly,
                    onInfo = {
                        showInfo(
                            "仅同域名",
                            "只下载与起始网址相同域名（及子域）的资源。关闭后会尝试下载 CDN 等外链，体积与时间会明显增加。"
                        )
                    }
                )
                SettingSwitchRow(
                    title = "遵守 robots",
                    checked = state.respectRobots,
                    onCheckedChange = viewModel::updateRespectRobots,
                    onInfo = {
                        showInfo(
                            "遵守 robots",
                            "读取目标站 robots.txt，跳过其中禁止抓取的路径。个人备份可按需关闭，请仍遵守网站条款与法律。"
                        )
                    }
                )
                Text("最大深度：${state.maxDepth}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.maxDepth.toFloat(),
                    onValueChange = { viewModel.updateMaxDepth(it.toInt()) },
                    valueRange = 0f..50f,
                    steps = 49
                )
                Text("并发数：${state.maxWorkers}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.maxWorkers.toFloat(),
                    onValueChange = { viewModel.updateMaxWorkers(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14
                )
            }

            SettingsSection("日志") {
                SettingSwitchRow(
                    title = "自动清理日志",
                    checked = state.autoCleanLogs,
                    onCheckedChange = viewModel::setAutoCleanLogs,
                    onInfo = {
                        showInfo(
                            "自动清理日志",
                            "启动时删除超过保留天数的旧日志文件，避免占用过多存储。"
                        )
                    }
                )
                Text("保留天数：${state.logRetentionDays}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.logRetentionDays.toFloat(),
                    onValueChange = { viewModel.setLogRetentionDays(it.toInt()) },
                    valueRange = 1f..30f,
                    steps = 28,
                    enabled = state.autoCleanLogs
                )
                OutlinedButton(onClick = { viewModel.exportLogs(includeAll = true) }) {
                    Text("导出日志")
                }
                state.lastExportPath?.let { path ->
                    Text(
                        path,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = { viewModel.cleanLogsNow() }) {
                    Text("立即清理过期日志")
                }
            }

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

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun FormatRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInfo: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (onInfo != null) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "说明",
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable(onClick = onInfo),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
