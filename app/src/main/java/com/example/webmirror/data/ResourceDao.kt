package com.example.webmirror.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ResourceDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(resource: ResourceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(resources: List<ResourceEntity>): List<Long>

    @Update
    suspend fun update(resource: ResourceEntity)

    @Query("SELECT * FROM resources WHERE normalized_url = :normalizedUrl LIMIT 1")
    suspend fun findByNormalizedUrl(normalizedUrl: String): ResourceEntity?

    @Query("SELECT * FROM resources WHERE id = :id")
    suspend fun findById(id: Long): ResourceEntity?

    @Query(
        """
        SELECT * FROM resources
        WHERE status = :status
        ORDER BY depth ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun takeByStatus(status: String, limit: Int): List<ResourceEntity>

    @Query("SELECT COUNT(*) FROM resources WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM resources")
    suspend fun countAll(): Int

    @Query("SELECT * FROM resources WHERE status = :status")
    fun observeByStatus(status: String): Flow<List<ResourceEntity>>

    @Query(
        """
        UPDATE resources SET status = :newStatus, updated_at = :now
        WHERE status = :oldStatus
        """
    )
    suspend fun requeueStatus(oldStatus: String, newStatus: String, now: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE resources SET
            status = :status,
            http_code = :httpCode,
            content_type = :contentType,
            content_length = :contentLength,
            etag = :etag,
            last_modified = :lastModified,
            sha256 = :sha256,
            local_path = :localPath,
            error_message = :errorMessage,
            retry_count = :retryCount,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun updateResult(
        id: Long,
        status: String,
        httpCode: Int?,
        contentType: String?,
        contentLength: Long?,
        etag: String?,
        lastModified: String?,
        sha256: String?,
        localPath: String?,
        errorMessage: String?,
        retryCount: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("SELECT local_path FROM resources WHERE normalized_url = :normalizedUrl AND local_path IS NOT NULL LIMIT 1")
    suspend fun getLocalPath(normalizedUrl: String): String?

    @Query("SELECT normalized_url, local_path FROM resources WHERE local_path IS NOT NULL AND status = 'DOWNLOADED'")
    suspend fun allDownloadedMappings(): List<UrlPathPair>

    @Query("DELETE FROM resources")
    suspend fun clearAll()

    @Query(
        """
        UPDATE resources SET status = 'QUEUED', updated_at = :now
        WHERE status IN ('DOWNLOADED', 'NOT_MODIFIED')
        """
    )
    suspend fun requeueDownloadedForUpdate(now: Long = System.currentTimeMillis()): Int

    @Query(
        """
        UPDATE resources SET status = 'QUEUED', updated_at = :now
        WHERE status IN ('FAILED')
        """
    )
    suspend fun requeueFailed(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM resources WHERE status = 'DOWNLOADED' AND etag IS NOT NULL LIMIT :limit")
    suspend fun downloadedWithEtag(limit: Int = 1000): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE status = 'DOWNLOADED' AND local_path IS NOT NULL ORDER BY updated_at DESC")
    suspend fun allDownloaded(): List<ResourceEntity>

    @Query("SELECT * FROM resources WHERE status = 'DOWNLOADED' AND local_path IS NOT NULL ORDER BY updated_at DESC")
    fun observeDownloaded(): Flow<List<ResourceEntity>>

    @Query("SELECT COALESCE(SUM(content_length), 0) FROM resources WHERE status = 'DOWNLOADED'")
    suspend fun sumDownloadedBytes(): Long

    @Query("DELETE FROM resources WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM resources WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

data class UrlPathPair(
    val normalized_url: String,
    val local_path: String
)
