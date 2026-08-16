package com.example.webmirror.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.webmirror.MainActivity
import com.example.webmirror.R
import com.example.webmirror.data.MirrorRepository
import com.example.webmirror.engine.EngineStatus
import com.example.webmirror.engine.model.DomainMode
import com.example.webmirror.engine.model.DomainPolicy
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.CrawlLimits
import com.example.webmirror.engine.model.RunMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service so long mirrors survive backgrounding (Phase 8).
 */
class MirrorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statsJob: Job? = null
    private lateinit var repo: MirrorRepository

    override fun onCreate() {
        super.onCreate()
        repo = MirrorRepository(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                repo.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                repo.pause()
                return START_STICKY
            }
            ACTION_RESUME_PAUSE -> {
                repo.unpause()
                return START_STICKY
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val dir = intent.getStringExtra(EXTRA_DIR) ?: return START_NOT_STICKY
                val depth = intent.getIntExtra(EXTRA_DEPTH, 10)
                val workers = intent.getIntExtra(EXTRA_WORKERS, 4)
                val sameDomain = intent.getBooleanExtra(EXTRA_SAME_DOMAIN, true)
                val mode = RunMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RunMode.FRESH.name)
                val respectRobots = intent.getBooleanExtra(EXTRA_ROBOTS, true)

                startForeground(NOTIF_ID, buildNotification("准备中…", "0 / 0"))
                observeStats()

                val config = MirrorConfig(
                    startUrl = url,
                    maxWorkers = workers,
                    domainPolicy = DomainPolicy(
                        mode = if (sameDomain) DomainMode.SAME_HOST else DomainMode.EVERYWHERE
                    ),
                    limits = CrawlLimits(maxDepth = depth),
                    // robots flag stored via engine active config extension — use domain only here;
                    // full robots toggled in repository/engine config field below
                )

                scope.launch(Dispatchers.IO) {
                    // Pass robots via a side channel on repository if available
                    repo.setRespectRobots(respectRobots)
                    when (mode) {
                        RunMode.FRESH -> repo.startMirror(config, File(dir))
                        RunMode.CONTINUE -> repo.continueMirror(config, File(dir))
                        RunMode.UPDATE -> repo.updateMirror(config, File(dir))
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun observeStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            repo.stats.collectLatest { s ->
                val title = when (s.status) {
                    EngineStatus.Running -> "正在镜像"
                    EngineStatus.Paused -> "已暂停"
                    EngineStatus.Completed -> "已完成"
                    EngineStatus.Cancelled -> "已取消"
                    EngineStatus.Error -> "出错"
                    else -> "WebMirror"
                }
                val body = "已下载 ${s.downloaded} · 队列 ${s.queued} · 失败 ${s.failed} · 共 ${s.total}"
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIF_ID, buildNotification(title, body))
                if (s.status == EngineStatus.Completed ||
                    s.status == EngineStatus.Cancelled ||
                    s.status == EngineStatus.Error
                ) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(title: String, body: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MirrorForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "镜像下载", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        statsJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "webmirror_mirror"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.example.webmirror.STOP"
        const val ACTION_PAUSE = "com.example.webmirror.PAUSE"
        const val ACTION_RESUME_PAUSE = "com.example.webmirror.RESUME"
        const val EXTRA_URL = "url"
        const val EXTRA_DIR = "dir"
        const val EXTRA_DEPTH = "depth"
        const val EXTRA_WORKERS = "workers"
        const val EXTRA_SAME_DOMAIN = "same_domain"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ROBOTS = "robots"

        fun start(
            context: Context,
            url: String,
            dir: String,
            depth: Int = 10,
            workers: Int = 4,
            sameDomain: Boolean = true,
            mode: RunMode = RunMode.FRESH,
            respectRobots: Boolean = true
        ) {
            val i = Intent(context, MirrorForegroundService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DIR, dir)
                putExtra(EXTRA_DEPTH, depth)
                putExtra(EXTRA_WORKERS, workers)
                putExtra(EXTRA_SAME_DOMAIN, sameDomain)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_ROBOTS, respectRobots)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MirrorForegroundService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
