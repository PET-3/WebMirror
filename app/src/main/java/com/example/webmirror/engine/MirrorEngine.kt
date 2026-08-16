package com.example.webmirror.engine

import android.content.Context
import android.util.Log
import com.example.webmirror.data.MirrorDatabase
import com.example.webmirror.data.ProjectEntity
import com.example.webmirror.data.ResourceStatus
import com.example.webmirror.engine.http.HttpFetcher
import com.example.webmirror.engine.log.MirrorLogger
import com.example.webmirror.engine.robots.RobotsParser
import com.example.webmirror.engine.http.PersistentCookieJar
import com.example.webmirror.engine.http.HttpRequestPolicy
import com.example.webmirror.engine.model.UrlNormalizer
import com.example.webmirror.engine.extract.SitemapParser
import com.example.webmirror.engine.model.RunMode
import com.example.webmirror.engine.model.DomainMode
import com.example.webmirror.engine.model.DomainPolicy
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.CrawlLimits
import com.example.webmirror.engine.model.MimeTypeResolver

import com.example.webmirror.engine.queue.UrlQueue
import com.example.webmirror.engine.extract.ContentAnalyzer
import com.example.webmirror.engine.rewrite.OfflineLinkRewriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File

data class EngineStats(
    val queued: Int = 0,
    val downloading: Int = 0,
    val downloaded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val total: Int = 0,
    val currentUrl: String = "",
    val status: EngineStatus = EngineStatus.Idle,
    val errorMessage: String? = null
)

enum class EngineStatus {
    Idle, Running, Paused, Completed, Cancelled, Error
}

/**
 * Phase-1 Mirror Engine: URL Queue + Worker Pool + SQLite.
 * Non-recursive. Supports pause / cancel. Crash recovery via requeue DOWNLOADING.
 *
 * Later phases will plug in filters, robots, rewrite, service, etc.
 */
class MirrorEngine(context: Context) {

    private val appContext = context.applicationContext
    private val db = MirrorDatabase.getInstance(appContext)
    private val resourceDao = db.resourceDao()
    private val projectDao = db.projectDao()
    private val queue = UrlQueue(resourceDao)
    private var httpPolicy = HttpRequestPolicy()
    private val cookieJar = PersistentCookieJar(appContext)
    private val logger = MirrorLogger.get(appContext)
    private val robots = RobotsParser()
    private var fetcher = HttpFetcher(httpPolicy, cookieJar)
    @Volatile var respectRobotsDefault: Boolean = true

    private val _stats = MutableStateFlow(EngineStats())
    val stats: StateFlow<EngineStats> = _stats.asStateFlow()

    private var engineJob: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile private var paused = false
    @Volatile private var cancelled = false

    private var currentProject: ProjectEntity? = null
    private var outputDir: File? = null
    private var activeConfig: MirrorConfig? = null



    /**
     * Start a new mirror (clears previous resource rows for simplicity in Phase 1).
     * Full multi-project isolation comes in later phases.
     */
    suspend fun start(
        startUrl: String,
        outputDir: File,
        maxDepth: Int = 10,
        maxWorkers: Int = 4,
        maxRetries: Int = 3,
        sameDomainOnly: Boolean = true,
        projectName: String = "mirror"
    ) = withContext(Dispatchers.IO) {
        val domainMode = if (sameDomainOnly) DomainMode.SAME_HOST else DomainMode.EVERYWHERE
        val config = MirrorConfig(
            startUrl = startUrl,
            maxWorkers = maxWorkers.coerceIn(1, 16),
            maxRetries = maxRetries,
            domainPolicy = DomainPolicy(mode = domainMode),
            limits = CrawlLimits(maxDepth = maxDepth)
        )
        startWithConfig(config, outputDir, projectName)
    }

    suspend fun startWithConfig(
        config: MirrorConfig,
        outputDir: File,
        projectName: String = "mirror",
        runMode: RunMode = RunMode.FRESH
    ) = withContext(Dispatchers.IO) {
        stopInternal()
        cancelled = false
        paused = false

        when (runMode) {
            RunMode.FRESH -> {
                resourceDao.clearAll()
            }
            RunMode.CONTINUE -> {
                queue.recoverInterrupted()
            }
            RunMode.UPDATE -> {
                queue.recoverInterrupted()
                // Re-check previously downloaded via conditional GET
                resourceDao.requeueDownloadedForUpdate()
                resourceDao.requeueFailed()
            }
        }

        val normalized = UrlNormalizer.normalize(config.startUrl)
            ?: run {
                _stats.value = EngineStats(status = EngineStatus.Error, errorMessage = "无效 URL")
                return@withContext
            }

        this.outputDir = outputDir.also { it.mkdirs() }
        queue.config = config
        queue.seedHost = UrlNormalizer.hostOf(normalized)
        activeConfig = config
        respectRobotsDefault = config.respectRobots
        logger.i("MirrorEngine", "start mode=$runMode url=${config.startUrl} depth=${config.limits.maxDepth} workers=${config.maxWorkers}")
        httpPolicy = HttpRequestPolicy(customHeaders = config.customHeaders)
        fetcher = HttpFetcher(
            policy = httpPolicy,
            cookieJar = cookieJar,
            proxyHost = config.proxyHost,
            proxyPort = config.proxyPort
        )
        // Fetch robots.txt once per origin when respecting robots
        if (config.respectRobots) {
            val origin = robots.originOf(normalized)
            if (origin != null && robots.get(origin) == null) {
                try {
                    val robotsFile = File(outputDir, ".robots_cache.txt")
                    val rr = fetcher.fetchToFile(robots.robotsUrl(origin), robotsFile)
                    if (rr.success && robotsFile.exists()) {
                        robots.put(origin, robots.parse(robotsFile.readText()))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "robots.txt fetch failed: ${e.message}")
                }
            }
        }

        val project = ProjectEntity(
            name = projectName,
            startUrl = normalized,
            rootPath = outputDir.absolutePath,
            maxDepth = config.limits.maxDepth,
            sameDomainOnly = config.domainPolicy.mode == DomainMode.SAME_HOST,
            maxWorkers = config.maxWorkers.coerceIn(1, 16),
            maxRetries = config.maxRetries
        )
        val pid = projectDao.insert(project)
        currentProject = project.copy(id = pid)
        projectDao.updateStatus(pid, "RUNNING")

        queue.enqueue(normalized, depth = 0, parentUrl = null)
        // Phase 5: seed common sitemap endpoints (depth 0)
        for (sm in SitemapParser.candidateSitemapUrls(normalized)) {
            queue.enqueue(sm, depth = 0, parentUrl = normalized)
        }

        val baseHost = UrlNormalizer.hostOf(normalized)
        _stats.value = EngineStats(status = EngineStatus.Running, currentUrl = normalized)

        val supervisor = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + supervisor)
        engineJob = scope!!.launch {
            try {
                runWorkers(
                    maxWorkers = config.maxWorkers.coerceIn(1, 16),
                    maxDepth = config.limits.maxDepth,
                    maxRetries = config.maxRetries,
                    sameDomainOnly = config.domainPolicy.mode != DomainMode.EVERYWHERE,
                    baseHost = baseHost
                )
                if (!cancelled) {
                    projectDao.updateStatus(pid, "COMPLETED")
                    refreshStats(EngineStatus.Completed)
                }
            } catch (e: CancellationException) {
                projectDao.updateStatus(pid, "CANCELLED")
                refreshStats(EngineStatus.Cancelled)
            } catch (e: Exception) {
                Log.e(TAG, "Engine error", e)
                projectDao.updateStatus(pid, "ERROR")
                _stats.value = _stats.value.copy(
                    status = EngineStatus.Error,
                    errorMessage = e.message
                )
            }
        }
    }

    /** Resume after app kill / pause: requeue DOWNLOADING and continue. */
    suspend fun resume(
        outputDir: File,
        maxWorkers: Int = 4,
        maxDepth: Int = 3,
        maxRetries: Int = 3,
        sameDomainOnly: Boolean = true,
        baseHost: String? = null
    ) = withContext(Dispatchers.IO) {
        stopInternal()
        cancelled = false
        paused = false
        this.outputDir = outputDir
        queue.recoverInterrupted()

        val host = baseHost
        _stats.value = EngineStats(status = EngineStatus.Running)
        val supervisor = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + supervisor)
        engineJob = scope!!.launch {
            try {
                runWorkers(maxWorkers, maxDepth, maxRetries, sameDomainOnly, host)
                if (!cancelled) refreshStats(EngineStatus.Completed)
            } catch (e: CancellationException) {
                refreshStats(EngineStatus.Cancelled)
            } catch (e: Exception) {
                _stats.value = _stats.value.copy(status = EngineStatus.Error, errorMessage = e.message)
            }
        }
    }

    fun pause() {
        paused = true
        _stats.value = _stats.value.copy(status = EngineStatus.Paused)
    }

    fun unpause() {
        paused = false
        if (_stats.value.status == EngineStatus.Paused) {
            _stats.value = _stats.value.copy(status = EngineStatus.Running)
        }
    }

    fun cancel() {
        cancelled = true
        paused = false
        engineJob?.cancel()
        scope?.cancel()
        _stats.value = _stats.value.copy(status = EngineStatus.Cancelled)
    }

    private fun stopInternal() {
        engineJob?.cancel()
        scope?.cancel()
        engineJob = null
        scope = null
    }

    private suspend fun runWorkers(
        maxWorkers: Int,
        maxDepth: Int,
        maxRetries: Int,
        sameDomainOnly: Boolean,
        baseHost: String?
    ) {
        val semaphore = Semaphore(maxWorkers)
        while (!cancelled) {
            while (paused && !cancelled) {
                delay(200)
            }
            if (cancelled) break

            val batch = queue.claim(limit = maxWorkers)
            if (batch.isEmpty()) {
                // Wait briefly for any in-flight discoveries
                delay(300)
                val stillQueued = queue.queuedCount()
                if (stillQueued == 0) break
                continue
            }

            coroutineScopeSafe {
                batch.map { resource ->
                    async {
                        semaphore.acquire()
                        try {
                            processOne(
                                resource.id, resource.normalizedUrl, resource.depth, resource.retryCount,
                                maxDepth, maxRetries, sameDomainOnly, baseHost, resource.localPath,
                                resource.etag, resource.lastModified
                            )
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }
            refreshStats(EngineStatus.Running)
        }
    }

    private suspend fun processOne(
        id: Long,
        normalizedUrl: String,
        depth: Int,
        retryCount: Int,
        maxDepth: Int,
        maxRetries: Int,
        sameDomainOnly: Boolean,
        baseHost: String?,
        localPath: String?,
        etag: String? = null,
        lastModified: String? = null
    ) {
        if (cancelled) return

        _stats.value = _stats.value.copy(currentUrl = normalizedUrl)

        val cfg = activeConfig
        val respectRobots = cfg?.respectRobots ?: respectRobotsDefault
        if (respectRobots && !robots.isAllowed(normalizedUrl)) {
            queue.markSkipped(id, "robots.txt")
            return
        }
        if (cfg != null) {
            val host = UrlNormalizer.hostOf(normalizedUrl)
            if (!cfg.domainPolicy.allows(baseHost, host)) {
                queue.markSkipped(id, "domain policy")
                return
            }
            if (!cfg.limits.depthUnlimited() && depth > cfg.limits.maxDepth) {
                queue.markSkipped(id, "max depth")
                return
            }
        } else {
            if (sameDomainOnly && baseHost != null) {
                val host = UrlNormalizer.hostOf(normalizedUrl)
                if (host != baseHost) {
                    queue.markSkipped(id, "different host")
                    return
                }
            }
            if (depth > maxDepth) {
                queue.markSkipped(id, "max depth")
                return
            }
        }

        val dir = outputDir ?: return
        val rel = localPath ?: return
        val file = File(dir, rel)

        val t0 = System.currentTimeMillis()
        val result = try {
            // Phase 6: conditional GET when we already have validators
            fetcher.fetchToFile(
                url = normalizedUrl,
                file = file,
                etag = etag,
                lastModified = lastModified
            )
        } catch (e: Exception) {
            logger.e("MirrorEngine", "fetch exception $normalizedUrl", e)
            queue.markFailed(id, e.message, retryCount + 1, maxRetries)
            return
        }
        val elapsed = System.currentTimeMillis() - t0
        logger.logHttp(
            url = normalizedUrl,
            code = result.httpCode,
            bytes = result.bytesWritten.takeIf { it > 0 },
            ms = elapsed,
            status = when {
                result.notModified -> "NOT_MODIFIED"
                result.success -> "OK"
                else -> "FAIL"
            },
            extra = result.errorMessage
        )

        // If redirected, also register final URL so path mapping stays consistent
        val finalNorm = result.finalUrl?.let { UrlNormalizer.normalize(it) }
        if (finalNorm != null && finalNorm != normalizedUrl) {
            queue.enqueue(finalNorm, depth, parentUrl = normalizedUrl)
        }

        when {
            result.notModified -> {
                queue.markNotModified(id)
            }
            result.success -> {
                // Optional MIME kind filter
                if (cfg != null && cfg.allowedKinds.isNotEmpty()) {
                    val kind = MimeTypeResolver.resolve(result.contentType, rel)
                    if (kind !in cfg.allowedKinds) {
                        file.delete()
                        queue.markSkipped(id, "mime kind filtered: $kind")
                        return
                    }
                }
                // Max file size
                if (cfg != null && result.bytesWritten > cfg.limits.maxFileBytes) {
                    file.delete()
                    queue.markSkipped(id, "file too large")
                    return
                }
                queue.markDownloaded(
                    id = id,
                    httpCode = result.httpCode,
                    contentType = result.contentType,
                    contentLength = result.contentLength,
                    etag = result.etag,
                    lastModified = result.lastModified,
                    sha256 = result.sha256,
                    localPath = rel
                )
                // Discover children (Phase 4 extractors)
                val parseBase = finalNorm ?: normalizedUrl
                if (depth < maxDepth && isParseable(result.contentType, rel)) {
                    try {
                        val text = file.readText(Charsets.UTF_8)
                        val children = ContentAnalyzer.discoverUrls(
                            text, result.contentType, rel, parseBase
                        )
                        queue.enqueueAll(children, depth + 1, parseBase)
                        // Incremental offline rewrite using current DB mappings
                        maybeRewriteFile(file, rel, parseBase, result.contentType)
                    } catch (e: Exception) {
                        Log.w(TAG, "parse failed $normalizedUrl: ${e.message}")
                    }
                }
            }
            else -> {
                // Phase 2: use retryable flag + Retry-After
                if (!result.retryable) {
                    queue.markFailed(id, result.errorMessage, maxRetries, maxRetries)
                } else {
                    val waitMs = httpPolicy.delayForAttempt(retryCount, result.retryAfterSec)
                    if (waitMs > 0 && !cancelled) {
                        try { kotlinx.coroutines.delay(waitMs) } catch (_: Exception) {}
                    }
                    queue.markFailed(id, result.errorMessage, retryCount + 1, maxRetries)
                }
            }
        }
    }

    private fun isParseable(contentType: String?, path: String): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        val p = path.lowercase()
        return ct.contains("text/html") || ct.contains("text/css") ||
                p.endsWith(".html") || p.endsWith(".htm") || p.endsWith(".css") ||
                !p.contains('.')
    }

    private suspend fun refreshStats(status: EngineStatus) {
        _stats.value = EngineStats(
            queued = queue.queuedCount(),
            downloading = resourceDao.countByStatus(ResourceStatus.DOWNLOADING.name),
            downloaded = queue.downloadedCount(),
            failed = queue.failedCount(),
            skipped = resourceDao.countByStatus(ResourceStatus.SKIPPED.name),
            total = queue.totalCount(),
            currentUrl = _stats.value.currentUrl,
            status = status
        )
    }
//
    private suspend fun <T> coroutineScopeSafe(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        return kotlinx.coroutines.coroutineScope(block)
    }

    companion object {
        private const val TAG = "MirrorEngine"
    }
}
