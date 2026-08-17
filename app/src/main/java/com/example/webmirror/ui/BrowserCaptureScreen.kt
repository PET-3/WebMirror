package com.example.webmirror.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.webmirror.capture.WebViewResourceCapture
import com.example.webmirror.engine.model.UrlNormalizer
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserCaptureScreen(
    startUrl: String,
    outputDir: File,
    sameHostOnly: Boolean,
    onClose: () -> Unit,
    onFinished: (captured: Int) -> Unit,
    onResourceSaved: (url: String, bytes: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf("准备打开…") }
    var captured by remember { mutableIntStateOf(0) }
    var lastUrl by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val seedHost = remember(startUrl) {
        UrlNormalizer.normalize(startUrl)?.let { UrlNormalizer.hostOf(it) }
    }

    val capture = remember(outputDir, sameHostOnly, seedHost) {
        WebViewResourceCapture(
            outputDir = outputDir,
            sameHostOnly = sameHostOnly,
            seedHost = seedHost,
            onCaptured = { url, bytes, total ->
                captured = total
                lastUrl = url
                onResourceSaved(url, bytes)
            },
            onPageEvent = { msg -> status = msg }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("浏览器捕获", fontWeight = FontWeight.SemiBold)
                        Text(
                            "已捕获 $captured · 请滚动/点击触发更多资源",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        onFinished(captured)
                        onClose()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    TextButton(onClick = {
                        onFinished(captured)
                        onClose()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("完成")
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
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (lastUrl.isNotBlank()) {
                Text(
                    text = lastUrl,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        capture.applySettings(this)
                        webViewRef = this
                        val url = startUrl.trim().let {
                            if (it.startsWith("http://") || it.startsWith("https://")) it
                            else "https://$it"
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                update = { /* keep */ }
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "提示：尽量滑完页面、点开需要的内容；点「完成」进入资源暂存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }
}
