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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.webmirror.data.ResourceEntity
import com.example.webmirror.export.ExportFormat
import com.example.webmirror.export.ExportScope
import com.example.webmirror.model.FileTypeFilter
import com.example.webmirror.model.ResourceCategory
import java.io.File

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
    var exportSuffix by remember { mutableStateOf("") }
    var folderPath by remember { mutableStateOf("") }

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
                    IconButton(
                        onClick = {
                            if (state.selectedIds.isNotEmpty()) {
                                deletePhysical = false
                                showDeleteDialog = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "删除选中")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (state.selectedIds.isEmpty()) {
                        viewModel.showToast("请先勾选要导出的文件")
                    } else {
                        exportName = ""
                        exportSuffix = ""
                        pendingFormat = ExportFormat.ZIP_STORED
                        showExportDialog = true
                    }
                }
            ) {
                Text("导出", modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
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
                placeholder = { Text("搜索文件名") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.height(8.dp))

            Text("类型", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ResourceCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = state.filter.category == cat,
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
            // Custom suffix chips (user-added) + suffix groups
            var showAddExt by remember { mutableStateOf(false) }
            var newExt by remember { mutableStateOf("") }
            var extraExts by remember { mutableStateOf(listOf<String>()) }

            Text("后缀", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                (listOf(
                    "png", "jpg", "jpeg", "webp", "gif", "svg",
                    "html", "css", "js", "ktx", "ktx2", "glb", "wasm", "woff2", "mp4", "webm"
                ) + extraExts).distinct().forEach { ext ->
                    FilterChip(
                        selected = ext == state.selectedExtension,
                        onClick = { viewModel.selectExtensionAndPin(ext) },
                        label = { Text(ext.uppercase()) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showAddExt = true; newExt = "" },
                    label = { Text("+ 增加") }
                )
            }

            Text("后缀组", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "图片" to listOf("png", "jpg", "jpeg", "webp", "gif", "svg"),
                    "网页" to listOf("html", "htm", "css", "js"),
                    "纹理3D" to listOf("ktx", "ktx2", "basis", "glb", "gltf", "wasm"),
                    "音视频" to listOf("mp3", "mp4", "webm", "wav")
                ).forEach { (label, exts) ->
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.selectExtensionGroup(exts) },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { showAddExt = true; newExt = "" },
                    label = { Text("+ 增加组") }
                )
            }

            if (showAddExt) {
                AlertDialog(
                    onDismissRequest = { showAddExt = false },
                    title = { Text("增加后缀") },
                    text = {
                        OutlinedTextField(
                            value = newExt,
                            onValueChange = { newExt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("后缀") },
                            placeholder = { Text("如 ktx2 或 data") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val e = newExt.trim().removePrefix(".").lowercase()
                            if (e.isNotEmpty()) {
                                extraExts = (extraExts + e).distinct()
                                viewModel.selectExtensionAndPin(e)
                            }
                            showAddExt = false
                        }) { Text("添加") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddExt = false }) { Text("取消") }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选 ${state.selectedIds.size} · 显示 ${ordered.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { viewModel.toggleSelectAllFiltered() }) {
                    Text(if (viewModel.isAllFilteredSelected()) "取消全选" else "全选当前")
                }
            }

            state.exportProgress?.let { prog ->
                LinearProgressIndicator(
                    progress = { if (prog.total > 0) prog.current.toFloat() / prog.total else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(prog.message, style = MaterialTheme.typography.labelSmall)
            }

            val mirrorRoot = when (state.stagingSource) {
                "browser" -> viewModel.mirrorBrowserRoot()
                "static" -> viewModel.mirrorStaticRoot()
                else -> viewModel.mirrorRoot()
            }

            if (state.stagingViewMode == "folder") {
                FolderView(
                    resources = ordered,
                    folderPath = folderPath,
                    selectedIds = state.selectedIds,
                    mirrorRoot = mirrorRoot,
                    onNavigate = { folderPath = it },
                    onToggle = viewModel::toggleSelection
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(ordered, key = { it.id }) { res ->
                        ResourceRow(
                            res = res,
                            selected = res.id in state.selectedIds,
                            mirrorRoot = mirrorRoot,
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
                    OutlinedTextField(
                        value = exportSuffix,
                        onValueChange = { exportSuffix = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("自定义后缀（可选）") },
                        singleLine = true,
                        placeholder = { Text("例如 _v2 或 -backup") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showExportDialog = false
                    val ext = pendingFormat.defaultExtension
                    val suffix = exportSuffix.trim()
                    val baseName = exportName.trim().ifBlank { "webmirror_export" }
                    val withSuffix = if (suffix.isEmpty()) baseName else {
                        if (baseName.endsWith(suffix)) baseName else baseName + suffix
                    }
                    val fileName = if (withSuffix.endsWith(".$ext")) withSuffix else "$withSuffix.$ext"
                    viewModel.prepareExport(pendingFormat, ExportScope.SELECTED)
                    onRequestExportDocument(pendingFormat.mimeType, fileName)
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
                    Text("移除 ${state.selectedIds.size} 项？")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SquareCheck(
                            checked = deletePhysical,
                            onToggle = { deletePhysical = !deletePhysical }
                        )
                        Spacer(Modifier.width(8.dp))
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

/**
 * Google Keep–style checklist mark: empty square / filled check.
 * Label sits beside the box (□ XXX  /  ☑ XXX).
 */
@Composable
fun SquareCheck(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(22.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(
                width = 1.5.dp,
                color = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                shape = RoundedCornerShape(3.dp)
            )
            .background(
                if (checked) MaterialTheme.colorScheme.primary else Color.Transparent
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
fun KeepCheckRow(
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquareCheck(checked = checked, onToggle = onToggle)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ResourceRow(
    res: ResourceEntity,
    selected: Boolean,
    mirrorRoot: File,
    onToggle: () -> Unit
) {
    val path = res.localPath ?: res.url
    val name = FileTypeFilter.displayName(path)
    val cat = FileTypeFilter.categoryOf(path, res.contentType)
    val file = res.localPath?.let { File(mirrorRoot, it) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquareCheck(checked = selected, onToggle = onToggle)
        Spacer(Modifier.width(10.dp))

        // Thumbnail for image / video placeholder
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            when (cat) {
                ResourceCategory.IMAGE -> {
                    if (file != null && file.exists()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(file)
                                .crossfade(true)
                                .size(96)
                                .build(),
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("图", style = MaterialTheme.typography.labelSmall)
                    }
                }
                ResourceCategory.VIDEO -> {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                }
                else -> {
                    Text(
                        FileTypeFilter.extensionOf(path).ifBlank { "?" }.take(4).uppercase(),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
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
    mirrorRoot: File,
    onNavigate: (String) -> Unit,
    onToggle: (Long) -> Unit
) {
    val prefix = folderPath.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    val folders = linkedSetOf<String>()
    val files = mutableListOf<ResourceEntity>()
    resources.forEach { r ->
        val path = (r.localPath ?: return@forEach).trimStart('/')
        if (prefix.isNotEmpty() && !path.startsWith(prefix)) return@forEach
        val rest = if (prefix.isEmpty()) path else path.removePrefix(prefix)
        val slash = rest.indexOf('/')
        if (slash >= 0) folders.add(rest.substring(0, slash))
        else if (rest.isNotEmpty()) files.add(r)
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        if (folderPath.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onNavigate(folderPath.trim('/').substringBeforeLast('/', ""))
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
                mirrorRoot = mirrorRoot,
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
