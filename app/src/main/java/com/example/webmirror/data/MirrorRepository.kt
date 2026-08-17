package com.example.webmirror.data

import android.content.Context
import com.example.webmirror.engine.MirrorEngine
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.RunMode
import java.io.File

/**
 * Facade between UI and MirrorEngine + DB.
 */
class MirrorRepository(context: Context) {

    private val appContext = context.applicationContext
    val engine = MirrorEngine(appContext)
    private val db = MirrorDatabase.getInstance(appContext)

    val stats = engine.stats

    fun setRespectRobots(value: Boolean) {
        engine.respectRobotsDefault = value
    }

    suspend fun startMirror(
        startUrl: String,
        outputDir: File,
        maxDepth: Int = 10,
        maxWorkers: Int = 4,
        sameDomainOnly: Boolean = true
    ) {
        engine.start(
            startUrl = startUrl,
            outputDir = outputDir,
            maxDepth = maxDepth,
            maxWorkers = maxWorkers,
            sameDomainOnly = sameDomainOnly
        )
    }

    suspend fun startMirror(config: MirrorConfig, outputDir: File, projectName: String = "mirror") {
        engine.startWithConfig(config, outputDir, projectName, RunMode.FRESH)
    }

    suspend fun continueMirror(config: MirrorConfig, outputDir: File, projectName: String = "mirror") {
        engine.startWithConfig(config, outputDir, projectName, RunMode.CONTINUE)
    }

    suspend fun updateMirror(config: MirrorConfig, outputDir: File, projectName: String = "mirror") {
        engine.startWithConfig(config, outputDir, projectName, RunMode.UPDATE)
    }

    fun pause() = engine.pause()
    fun unpause() = engine.unpause()
    fun cancel() = engine.cancel()

    suspend fun resumeMirror(
        outputDir: File,
        maxWorkers: Int = 4,
        maxDepth: Int = 10,
        sameDomainOnly: Boolean = true
    ) {
        engine.resume(outputDir, maxWorkers, maxDepth, sameDomainOnly = sameDomainOnly)
    }

    fun resourceDao() = db.resourceDao()
    fun projectDao() = db.projectDao()

    suspend fun allDownloadedResources() = db.resourceDao().allDownloaded()

    /**
     * Remove from export staging conceptually: optional physical delete of mirror file.
     * Default is DB row only when [deleteFiles] is false — keeps mirror intact.
     */
    suspend fun removeResources(ids: List<Long>, mirrorRoot: File, deleteFiles: Boolean) {
        if (ids.isEmpty()) return
        if (deleteFiles) {
            for (id in ids) {
                val row = db.resourceDao().findById(id) ?: continue
                val rel = row.localPath ?: continue
                runCatching { File(mirrorRoot, rel).delete() }
            }
        }
        db.resourceDao().deleteByIds(ids)
    }
}
