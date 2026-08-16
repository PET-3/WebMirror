package com.example.webmirror.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One mirror project (one website mirror session / site).
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val startUrl: String,
    val rootPath: String,          // local root or SAF tree URI string
    val maxDepth: Int = 3,
    val sameDomainOnly: Boolean = true,
    val rewriteLinks: Boolean = true,
    val maxWorkers: Int = 4,
    val maxUrls: Int = 50_000,
    val maxRetries: Int = 3,

    val status: String = "IDLE",   // IDLE / RUNNING / PAUSED / COMPLETED / CANCELLED / ERROR
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
