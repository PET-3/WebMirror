package com.example.webmirror

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.webmirror.ui.BrowserCaptureScreen
import com.example.webmirror.ui.HomeScreen
import com.example.webmirror.ui.MainViewModel
import com.example.webmirror.ui.ResourcesScreen
import com.example.webmirror.ui.theme.WebMirrorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            val name = uri.lastPathSegment?.substringAfterLast(':') ?: uri.toString()
            viewModel.setTreeUri(uri, "用户选择: $name")
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.startExportToUri(uri)
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
                    var screen by remember { mutableStateOf("home") }
                    when (screen) {
                        "resources" -> ResourcesScreen(
                            viewModel = viewModel,
                            onBack = { screen = "home" },
                            onRequestExportDocument = { mime, name ->
                                createDocumentLauncher.launch(name)
                            }
                        )
                        "browser" -> {
                            val state = viewModel.uiState.value
                            BrowserCaptureScreen(
                                startUrl = state.url,
                                outputDir = viewModel.mirrorRoot(),
                                sameHostOnly = state.sameDomainOnly,
                                onClose = { screen = "home" },
                                onFinished = { count ->
                                    viewModel.onBrowserCaptureFinished(count)
                                    screen = "home"
                                },
                                onResourceSaved = { url, bytes ->
                                    viewModel.recordCapturedResource(url, bytes)
                                }
                            )
                        }
                        else -> HomeScreen(
                            viewModel = viewModel,
                            onPickDirectory = { openTreeLauncher.launch(null) },
                            onOpenResources = {
                                viewModel.refreshStaging()
                                screen = "resources"
                            },
                            onOpenBrowserCapture = {
                                val u = viewModel.uiState.value.url.trim()
                                if (u.isBlank()) {
                                    viewModel.showToast("请先输入网站 URL")
                                } else {
                                    screen = "browser"
                                }
                            }
                        )
                    }
                }
            }
        }
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
