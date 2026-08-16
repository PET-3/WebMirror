package com.example.webmirror.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per unique normalized URL in a mirror project.
 * Used for dedup, resume, continue, update, path mapping.
 */
@Entity(
    tableName = "resources",
    indices = [
        Index(value = ["normalized_url"], unique = true),
        Index(value = ["status"]),
        Index(value = ["local_path"])
    ]
)
data class ResourceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val url: String,
    val normalizedUrl: String,
    val localPath: String? = null,
    val depth: Int = 0,
    val parentUrl: String? = null,

    val status: String = ResourceStatus.QUEUED.name,

    val httpCode: Int? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val sha256: String? = null,

    val retryCount: Int = 0,
    val errorMessage: String? = null,

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
