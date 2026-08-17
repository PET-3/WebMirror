package com.example.webmirror

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.webmirror.ui.BrowserCaptureScreen
import com.example.webmirror.ui.MainViewModel
import com.example.webmirror.ui.ResourcesScreen
import com.example.webmirror.ui.SettingsScreen
import com.example.webmirror.ui.ToolsPagerScreen
import com.example.webmirror.ui.theme.WebMirrorTheme
import com.example.webmirror.util.StoragePaths

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
            viewModel.setTreeUri(uri, "自定义：$name")
            viewModel.showToast("已选择保存位置")
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.startExportToUri(uri)
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val ok = result.values.any { it }
        if (ok || hasLegacyWrite()) {
            val dir = StoragePaths.defaultPublicDownloadDir()
            dir.mkdirs()
            StoragePaths.setDownloadDir(this, dir.absolutePath)
            viewModel.refreshDownloadDir()
            viewModel.showToast("已使用 ${dir.absolutePath}")
        } else {
            viewModel.showToast("未授予存储权限，将使用应用私有目录")
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            WebMirrorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Stack-based navigation: edge swipe / system back = pop, not always exit
                    val stack = remember { mutableStateListOf("tools") }

                    fun push(route: String) {
                        if (stack.lastOrNull() != route) stack.add(route)
                    }

                    fun pop() {
                        if (stack.size > 1) stack.removeAt(stack.lastIndex)
                        else finish()
                    }

                    BackHandler {
                        pop()
                    }

                    val current = stack.last()
                    when {
                        current == "tools" -> ToolsPagerScreen(
                            viewModel = viewModel,
                            onOpenResources = { source ->
                                viewModel.openStaging(source)
                                push("resources")
                            },
                            onOpenSettings = { push("settings") },
                            onStartBrowserCapture = {
                                val u = viewModel.uiState.value.url.trim()
                                if (u.isBlank()) {
                                    viewModel.showToast("请先输入网站 URL")
                                } else {
                                    push("browser_capture")
                                }
                            }
                        )
                        current == "resources" -> ResourcesScreen(
                            viewModel = viewModel,
                            onBack = { pop() },
                            onRequestExportDocument = { _, name ->
                                createDocumentLauncher.launch(name)
                            }
                        )
                        current == "settings" -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = { pop() },
                            onPickSaveDirectory = { openTreeLauncher.launch(null) },
                            onRequestStoragePermission = { requestStoragePermission() }
                        )
                        current == "browser_capture" -> {
                            val state = viewModel.uiState.value
                            BrowserCaptureScreen(
                                startUrl = state.url,
                                outputDir = viewModel.mirrorBrowserRoot(),
                                sameHostOnly = state.sameDomainOnly,
                                onClose = { pop() },
                                onFinished = { count ->
                                    viewModel.onBrowserCaptureFinished(count)
                                    // Replace capture with resources on stack
                                    if (stack.lastOrNull() == "browser_capture") {
                                        stack.removeAt(stack.lastIndex)
                                    }
                                    viewModel.openStaging("browser")
                                    push("resources")
                                },
                                onResourceSaved = { url, bytes ->
                                    viewModel.recordCapturedResource(url, bytes)
                                }
                            )
                        }
                        else -> {
                            stack.clear()
                            stack.add("tools")
                        }
                    }
                }
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                val dir = StoragePaths.defaultPublicDownloadDir()
                dir.mkdirs()
                StoragePaths.setDownloadDir(this, dir.absolutePath)
                viewModel.refreshDownloadDir()
                viewModel.showToast("已可访问 ${dir.absolutePath}")
            } else {
                val perms = mutableListOf<String>()
                if (Build.VERSION.SDK_INT <= 32) {
                    perms += Manifest.permission.READ_EXTERNAL_STORAGE
                    perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
                }
                if (perms.isNotEmpty()) {
                    storagePermissionLauncher.launch(perms.toTypedArray())
                } else {
                    val dir = StoragePaths.defaultPublicDownloadDir()
                    val ok = runCatching {
                        dir.mkdirs()
                        val probe = java.io.File(dir, ".wm_write_test")
                        probe.writeText("ok")
                        probe.delete()
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        StoragePaths.setDownloadDir(this, dir.absolutePath)
                        viewModel.refreshDownloadDir()
                        viewModel.showToast("已使用 ${dir.absolutePath}")
                    } else {
                        viewModel.showToast("请使用「更改位置」选择可写文件夹")
                        openTreeLauncher.launch(null)
                    }
                }
            }
        } else {
            storagePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun hasLegacyWrite(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
