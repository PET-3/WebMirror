package com.example.webmirror.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
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
    // Pin selected to top
    val ordered = remember(filtered, state.selectedIds) {
        val sel = filtered.filter { it.id in state.selectedIds }
        val rest = filtered.filter { it.id !in state.selectedIds }
        sel + rest
    }
    var showExportDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePhysical by remember { mutableStateOf(false) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.ZIP_STORED) }
    var exportName by remember { mutableStateOf("") }
    var folderPath by remember { mutableStateOf("") } // relative folder prefix for folder view

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.stagingSource) {
                            "static" -> "静态暂存"
                            "browser" -> "浏览器暂存"
                            else -> "资源暂存"
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.setStagingViewMode(
                            if (state.stagingViewMode == "folder") "list" else "folder"
                        )
                        folderPath = ""
                    }) {
                        Icon(
                            if (state.stagingViewMode == "folder") Icons.Default.List
                            else Icons.Default.Folder,
                            contentDescription = "切换视图"
                        )
                    }
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
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                    IconButton(onClick = {
                        if (state.selectedIds.isEmpty()) {
                            viewModel.showToast("请先选择要导出的文件")
                        } else {
                            exportName = ""
                            pendingFormat = ExportFormat.ZIP_STORED
                            showExportDialog = true
                        }
                    }) {
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
            // Source tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "全部", "static" to "静态", "browser" to "浏览器").forEach { (key, label) ->
                    FilterChip(
                        selected = state.stagingSource == key,
                        onClick = { viewModel.setStagingSource(key) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.resourceQuery,
                onValueChange = viewModel::updateResourceQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            // Category chips — click selects all of that type + pin
            Text("类型（点选即勾选该类）", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ResourceCategory.entries.forEach { cat ->
                    val selected = state.filter.category == cat
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (cat == ResourceCategory.ALL) {
                                viewModel.setCategory(ResourceCategory.ALL)
                                viewModel.clearSelection()
                            } else {
                                viewModel.selectCategoryAndPin(cat)
                            }
                        },
                        label = { Text(cat.label) }
                    )
                }
            }
            // Extension quick-select (e.g. png → select all png)
            Text("扩展名（点选即勾选并置顶）", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("png", "jpg", "jpeg", "webp", "gif", "svg", "html", "css", "js", "ktx2", "woff2", "mp4").forEach { ext ->
                    FilterChip(
                        selected = ext in state.filter.imageExtensions,
                        onClick = { viewModel.selectExtensionAndPin(ext) },
                        label = { Text(ext.uppercase()) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            Text(
                "已选 ${state.selectedIds.size} · 显示 ${ordered.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.exportProgress?.let { prog ->
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (prog.total > 0) prog.current.toFloat() / prog.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(prog.message, style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(8.dp))

            if (state.stagingViewMode == "folder") {
                FolderView(
                    resources = ordered,
                    folderPath = folderPath,
                    selectedIds = state.selectedIds,
                    onNavigate = { folderPath = it },
                    onToggle = viewModel::toggleSelection
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(ordered, key = { it.id }) { res ->
                        ResourceRow(
                            res = res,
                            selected = res.id in state.selectedIds,
                            onToggle = { viewModel.toggleSelection(res.id) }
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出 ${state.selectedIds.size} 项") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("格式", fontWeight = FontWeight.Medium)
                    ExportFormat.entries.forEach { fmt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pendingFormat = fmt }
                        ) {
                            RadioButton(
                                selected = pendingFormat == fmt,
                                onClick = { pendingFormat = fmt }
                            )
                            Text(fmt.label)
                        }
                    }
                    OutlinedTextField(
                        value = exportName,
                        onValueChange = { exportName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义名称（可选）") },
                        singleLine = true,
                        placeholder = { Text("留空则自动命名") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    val base = exportName.trim().ifBlank {
                        "webmirror_export.${pendingFormat.defaultExtension}"
                    }.let { name ->
                        val ext = pendingFormat.defaultExtension
                        if (name.endsWith(".$ext")) name else "$name.$ext"
                    }
                    val mime = pendingFormat.mimeType
                    viewModel.prepareExport(pendingFormat, ExportScope.SELECTED)
                    onRequestExportDocument(mime, base)
                }) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("移除选中项") },
            text = {
                Column {
                    Text("从暂存中移除 ${state.selectedIds.size} 项？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deletePhysical, onCheckedChange = { deletePhysical = it })
                        Text("同时删除磁盘文件")
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteDialog = false
                    viewModel.removeSelected(deletePhysical)
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ResourceRow(
    res: ResourceEntity,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val path = res.localPath ?: res.url
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                FileTypeFilter.displayName(path),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            formatSize(res.contentLength),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FolderView(
    resources: List<ResourceEntity>,
    folderPath: String,
    selectedIds: Set<Long>,
    onNavigate: (String) -> Unit,
    onToggle: (Long) -> Unit
) {
    val prefix = folderPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val folders = linkedSetOf<String>()
    val files = mutableListOf<ResourceEntity>()
    resources.forEach { r ->
        val path = (r.localPath ?: return@forEach).trimStart('/')
        if (!path.startsWith(prefix) && prefix.isNotEmpty()) return@forEach
        if (prefix.isEmpty() && path.isEmpty()) return@forEach
        val rest = if (prefix.isEmpty()) path else path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            folders.add(rest.substring(0, slash))
        } else if (rest.isNotEmpty()) {
            files.add(r)
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        if (folderPath.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val parent = folderPath.trim('/').substringBeforeLast('/', "")
                            onNavigate(parent)
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("..")
                }
            }
        }
        items(folders.toList()) { name ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                    .clickable {
                        onNavigate(if (folderPath.isEmpty()) name else "${folderPath.trimEnd('/')}/$name")
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(name, fontWeight = FontWeight.Medium)
            }
        }
        items(files, key = { it.id }) { res ->
            ResourceRow(
                res = res,
                selected = res.id in selectedIds,
                onToggle = { onToggle(res.id) }
            )
        }
    }
}

private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.2f MB", kb / 1024.0)
}
