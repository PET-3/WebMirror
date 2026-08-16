package com.example.webmirror.engine.model

/**
 * Hard safety limits (replace the old maxDepth coerceIn(0,5)).
 */
data class CrawlLimits(
    val maxDepth: Int = 10,          // Int.MAX_VALUE = unlimited
    val maxUrls: Int = 50_000,
    val maxFiles: Int = 50_000,
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GiB
    val maxFileBytes: Long = 200L * 1024 * 1024,       // 200 MiB per file
    val maxDurationMs: Long = 0                      // 0 = no time limit
) {
    fun depthUnlimited(): Boolean = maxDepth == Int.MAX_VALUE || maxDepth < 0
}
