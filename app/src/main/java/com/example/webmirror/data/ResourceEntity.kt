package com.example.webmirror.data

import androidx.room.ColumnInfo
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

    @ColumnInfo(name = "normalized_url")
    val normalizedUrl: String,

    @ColumnInfo(name = "local_path")
    val localPath: String? = null,

    val depth: Int = 0,
    val parentUrl: String? = null,

    val status: String = ResourceStatus.QUEUED.name,

    @ColumnInfo(name = "http_code")
    val httpCode: Int? = null,

    @ColumnInfo(name = "content_type")
    val contentType: String? = null,

    @ColumnInfo(name = "content_length")
    val contentLength: Long? = null,

    val etag: String? = null,

    @ColumnInfo(name = "last_modified")
    val lastModified: String? = null,

    val sha256: String? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
