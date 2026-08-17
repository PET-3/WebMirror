package com.example.webmirror.engine.model

/**
 * Full crawl configuration for a project.
 */
data class MirrorConfig(
    val startUrl: String,
    val maxWorkers: Int = 4,
    val maxRetries: Int = 3,
    val domainPolicy: DomainPolicy = DomainPolicy(DomainMode.SAME_HOST),
    val urlFilter: UrlFilter = UrlFilter(),
    val queryPolicy: QueryPolicy = QueryPolicy(),
    val limits: CrawlLimits = CrawlLimits(),
    val rewriteLinks: Boolean = true,
    val allowedKinds: Set<MimeTypeResolver.Kind> = emptySet(),
    /** Lowercase extensions without dot; empty = all. Discovery docs still kept if non-empty. */
    val allowedExtensions: Set<String> = emptySet(),
    val keepDiscoveryDocs: Boolean = true,
    val respectRobots: Boolean = true,
    val proxyHost: String? = null,
    val proxyPort: Int = 0,
    val customHeaders: Map<String, String> = emptyMap()
)
