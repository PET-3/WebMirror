package com.example.webmirror.data

/**
 * Persistent resource lifecycle states (HTTrack-like).
 */
enum class ResourceStatus {
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    SKIPPED,
    NOT_MODIFIED,
    CANCELLED
}
