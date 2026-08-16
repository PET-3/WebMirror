package com.example.webmirror.engine.queue

import com.example.webmirror.data.ResourceDao
import com.example.webmirror.data.ResourceEntity
import com.example.webmirror.data.ResourceStatus
import com.example.webmirror.engine.model.DomainPolicy
import com.example.webmirror.engine.model.LocalPathMapper
import com.example.webmirror.engine.model.MirrorConfig
import com.example.webmirror.engine.model.QueryPolicy
import com.example.webmirror.engine.model.UrlFilter
import com.example.webmirror.engine.model.UrlNormalizer

/**
 * Persistent URL queue backed by Room.
 * Applies normalization, query policy, domain policy, and URL filters on enqueue.
 */
class UrlQueue(
    private val resourceDao: ResourceDao
) {
    @Volatile
    var config: MirrorConfig? = null

    @Volatile
    var seedHost: String? = null

    /**
     * Enqueue a URL if not already known and passes filters.
     * Returns true if newly inserted.
     */
    suspend fun enqueue(
        url: String,
        depth: Int,
        parentUrl: String? = null
    ): Boolean {
        val cfg = config
        var normalized = UrlNormalizer.normalize(url) ?: return false
        if (cfg != null) {
            normalized = cfg.queryPolicy.applyToNormalizedUrl(normalized)
        }

        val existing = resourceDao.findByNormalizedUrl(normalized)
        if (existing != null) return false

        // Limits
        if (cfg != null) {
            if (!cfg.limits.depthUnlimited() && depth > cfg.limits.maxDepth) {
                return false
            }
            val total = resourceDao.countAll()
            if (total >= cfg.limits.maxUrls) {
                return false
            }
        }

        val localPath = LocalPathMapper.toRelativePath(normalized)

        // Domain policy
        if (cfg != null) {
            val host = UrlNormalizer.hostOf(normalized)
            if (!cfg.domainPolicy.allows(seedHost, host)) {
                // Record as skipped for visibility
                val skipped = ResourceEntity(
                    url = url,
                    normalizedUrl = normalized,
                    localPath = localPath,
                    depth = depth,
                    parentUrl = parentUrl,
                    status = ResourceStatus.SKIPPED.name,
                    errorMessage = "domain policy"
                )
                resourceDao.insert(skipped)
                return false
            }
        }

        // URL filter
        if (cfg != null) {
            val reason = cfg.urlFilter.rejectReason(normalized, localPath)
            if (reason != null) {
                val skipped = ResourceEntity(
                    url = url,
                    normalizedUrl = normalized,
                    localPath = localPath,
                    depth = depth,
                    parentUrl = parentUrl,
                    status = ResourceStatus.SKIPPED.name,
                    errorMessage = reason
                )
                resourceDao.insert(skipped)
                return false
            }
        }

        val entity = ResourceEntity(
            url = url,
            normalizedUrl = normalized,
            localPath = localPath,
            depth = depth,
            parentUrl = parentUrl,
            status = ResourceStatus.QUEUED.name
        )
        val id = resourceDao.insert(entity)
        return id > 0
    }

    suspend fun enqueueAll(urls: Collection<String>, depth: Int, parentUrl: String?): Int {
        var added = 0
        for (u in urls) {
            if (enqueue(u, depth, parentUrl)) added++
        }
        return added
    }

    suspend fun claim(limit: Int): List<ResourceEntity> {
        val batch = resourceDao.takeByStatus(ResourceStatus.QUEUED.name, limit)
        val claimed = mutableListOf<ResourceEntity>()
        for (item in batch) {
            val updated = item.copy(
                status = ResourceStatus.DOWNLOADING.name,
                updatedAt = System.currentTimeMillis()
            )
            resourceDao.update(updated)
            claimed.add(updated)
        }
        return claimed
    }

    suspend fun markDownloaded(
        id: Long,
        httpCode: Int?,
        contentType: String?,
        contentLength: Long?,
        etag: String?,
        lastModified: String?,
        sha256: String?,
        localPath: String?
    ) {
        resourceDao.updateResult(
            id = id,
            status = ResourceStatus.DOWNLOADED.name,
            httpCode = httpCode,
            contentType = contentType,
            contentLength = contentLength,
            etag = etag,
            lastModified = lastModified,
            sha256 = sha256,
            localPath = localPath,
            errorMessage = null,
            retryCount = 0
        )
    }

    suspend fun markFailed(id: Long, error: String?, retryCount: Int, maxRetries: Int) {
        val status = if (retryCount >= maxRetries) {
            ResourceStatus.FAILED.name
        } else {
            ResourceStatus.QUEUED.name
        }
        resourceDao.updateResult(
            id = id,
            status = status,
            httpCode = null,
            contentType = null,
            contentLength = null,
            etag = null,
            lastModified = null,
            sha256 = null,
            localPath = null,
            errorMessage = error,
            retryCount = retryCount
        )
    }

    suspend fun markSkipped(id: Long, reason: String) {
        resourceDao.updateResult(
            id = id,
            status = ResourceStatus.SKIPPED.name,
            httpCode = null,
            contentType = null,
            contentLength = null,
            etag = null,
            lastModified = null,
            sha256 = null,
            localPath = null,
            errorMessage = reason,
            retryCount = 0
        )
    }

    suspend fun markNotModified(id: Long) {
        resourceDao.updateResult(
            id = id,
            status = ResourceStatus.NOT_MODIFIED.name,
            httpCode = 304,
            contentType = null,
            contentLength = null,
            etag = null,
            lastModified = null,
            sha256 = null,
            localPath = null,
            errorMessage = null,
            retryCount = 0
        )
    }

    suspend fun recoverInterrupted() {
        resourceDao.requeueStatus(
            oldStatus = ResourceStatus.DOWNLOADING.name,
            newStatus = ResourceStatus.QUEUED.name
        )
    }

    suspend fun queuedCount(): Int = resourceDao.countByStatus(ResourceStatus.QUEUED.name)
    suspend fun downloadedCount(): Int = resourceDao.countByStatus(ResourceStatus.DOWNLOADED.name)
    suspend fun failedCount(): Int = resourceDao.countByStatus(ResourceStatus.FAILED.name)
    suspend fun totalCount(): Int = resourceDao.countAll()
}
