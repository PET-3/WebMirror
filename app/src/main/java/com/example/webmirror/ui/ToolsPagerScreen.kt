package com.example.webmirror.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

/**
 * Top-level tools hub: swipe between 静态下载 and 浏览器捕获.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ToolsPagerScreen(
    viewModel: MainViewModel,
    onOpenResources: (source: String) -> Unit,
    onOpenSettings: () -> Unit,
    onStartBrowserCapture: () -> Unit,
    initialPage: Int = 0,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, 1), pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearToast()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("WebMirror", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                Tab(
                    selected = pagerState.currentPage == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    text = { Text("静态下载") }
                )
                Tab(
                    selected = pagerState.currentPage == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    text = { Text("浏览器捕获") }
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) { page ->
                when (page) {
                    0 -> StaticDownloadScreen(
                        viewModel = viewModel,
                        onBack = { /* nested in pager — no-op */ },
                        onOpenResources = { onOpenResources("static") },
                        onOpenSettings = onOpenSettings,
                        embedded = true
                    )
                    else -> BrowserModeScreen(
                        viewModel = viewModel,
                        onBack = { },
                        onStartCapture = onStartBrowserCapture,
                        onOpenSettings = onOpenSettings,
                        onOpenResources = { onOpenResources("browser") },
                        embedded = true
                    )
                }
            }
        }
    }
}
