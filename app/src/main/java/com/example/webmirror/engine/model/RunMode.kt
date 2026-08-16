package com.example.webmirror.engine.model

/**
 * How a mirror run treats existing database state (Phase 6).
 */
enum class RunMode {
    /** Clear resources and start from seed. */
    FRESH,
    /** Keep finished rows; requeue interrupted; continue unfinished. */
    CONTINUE,
    /** Conditional GET on already DOWNLOADED (ETag / Last-Modified); enqueue seed if empty. */
    UPDATE
}
