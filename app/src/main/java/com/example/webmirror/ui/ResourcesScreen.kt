package com.example.webmirror.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.webmirror.data.ResourceEntity
import com.example.webmirror.export.ExportFormat
import com.example.webmirror.export.ExportProgress
import com.example.webmirror.export.ExportScope
import com.example.webmirror.model.FileTypeFilter
import com.example.webmirror.model.ResourceCategory
import com.example.webmirror.model.ResourceSort

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ResourcesScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRequestExportDocument: (mime: String, suggestedName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val filtered = viewModel.filteredResources()
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePhysical by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.ZIP_STORED) }
    var pendingScope by remember { mutableStateOf(ExportScope.SELECTED) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("资源暂存", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSelectAllFiltered() }) {
                        Icon(
                            if (viewModel.isAllFilteredSelected()) Icons.Default.CheckBox
                            else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = "全选"
                        )
                    }
                    IconButton(
                        onClick = {
                            if (state.selectedIds.isNotEmpty()) {
                                deletePhysical = false
                                showDeleteDialog = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "移出选择")
                    }
                    IconButton(onClick = { showExportDialog = true }) {
                        Icon(Icons.Default.Share, contentDescription = "导出")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = state.resourceQuery,
                onValueChange = viewModel::updateResourceQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件名 / 扩展名 / URL") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            // Category chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ResourceCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = state.filter.category == cat,
                        onClick = { viewModel.setCategory(cat) },
                        label = { Text(cat.label) }
                    )
                }
            }

            if (state.filter.category == ResourceCategory.IMAGE) {
                Spacer(Modifier.height(6.dp))
                Text("图片格式（可多选，空=全部）", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FileTypeFilter.IMAGE_EXTENSIONS.forEach { ext ->
                        val selected = ext in state.filter.imageExtensions
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.toggleImageExt(ext) },
                            label = { Text(ext.uppercase()) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "显示 ${filtered.size} · 已选 ${state.selectedIds.size}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    TextButton(onClick = { viewModel.setSort(ResourceSort.TIME_DESC) }) { Text("时间") }
                    TextButton(onClick = { viewModel.setSort(ResourceSort.NAME_ASC) }) { Text("名称") }
                    TextButton(onClick = { viewModel.setSort(ResourceSort.SIZE_DESC) }) { Text("大小") }
                }
            }

            // Export progress
            state.exportProgress?.let { ep ->
                if (!ep.done && !ep.cancelled) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(ep.message.ifBlank { "导出中…" }, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { ep.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${ep.current} / ${ep.total}", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = viewModel::cancelExport) { Text("取消导出") }
                    }
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { it.id }) { res ->
                    ResourceRow(
                        resource = res,
                        selected = res.id in state.selectedIds,
                        onToggle = { viewModel.toggleSelection(res.id) }
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        deletePhysical = false
                        showDeleteDialog = true
                    },
                    enabled = state.selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("移出选择 (${state.selectedIds.size})") }
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.weight(1f)
                ) { Text("导出") }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("移除资源") },
            text = {
                Column {
                    Text("已选 ${state.selectedIds.size} 项。")
                    Text("「仅移出选择」不会删除磁盘上的镜像文件。")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deletePhysical, onCheckedChange = { deletePhysical = it })
                        Text("同时删除镜像文件（危险）")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.removeSelected(deleteFiles = deletePhysical)
                }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("选择导出方式") },
            text = {
                Column {
                    Text("范围", fontWeight = FontWeight.SemiBold)
                    ScopeRadio("仅已选择 (${state.selectedIds.size})", ExportScope.SELECTED, pendingScope) {
                        pendingScope = it
                    }
                    ScopeRadio("当前筛选 (${filtered.size})", ExportScope.FILTERED, pendingScope) {
                        pendingScope = it
                    }
                    ScopeRadio("整个镜像 (${state.stagingResources.size})", ExportScope.ENTIRE_MIRROR, pendingScope) {
                        pendingScope = it
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("格式", fontWeight = FontWeight.SemiBold)
                    FormatRadio(ExportFormat.ZIP_STORED, pendingFormat) { pendingFormat = it }
                    FormatRadio(ExportFormat.PDF, pendingFormat) { pendingFormat = it }
                    FormatRadio(ExportFormat.HTML, pendingFormat) { pendingFormat = it }
                    Text(
                        "HTML 会打成含 index.html + assets 的 ZIP，解压后可离线打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    viewModel.prepareExport(pendingFormat, pendingScope)
                    val name = viewModel.suggestExportFileName(pendingFormat)
                    onRequestExportDocument(pendingFormat.mimeType, name)
                }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ScopeRadio(
    label: String,
    value: ExportScope,
    selected: ExportScope,
    onSelect: (ExportScope) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(label)
    }
}

@Composable
private fun FormatRadio(
    value: ExportFormat,
    selected: ExportFormat,
    onSelect: (ExportFormat) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(value.label)
    }
}

@Composable
private fun ResourceRow(
    resource: ResourceEntity,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val path = resource.localPath.orEmpty()
    val name = FileTypeFilter.displayName(path)
    val ext = FileTypeFilter.extensionOf(path).uppercase().ifBlank { "—" }
    val size = formatBytes(resource.contentLength ?: 0L)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(ext.take(4), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "$ext · $size",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                resource.normalizedUrl,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
