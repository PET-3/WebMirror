package com.example.webmirror.engine.model

/**
 * HTTrack-style include / exclude rules evaluated before enqueue.
 *
 * Pattern syntax (simplified):
 * - Exact substring match if no wildcards
 * - `*` matches any sequence
 * - Rules starting with `+` are includes, `-` are excludes
 * - If any include rules exist, URL must match at least one include
 * - Exclude always wins over include
 *
 * Examples:
 * +*/docs/*
 * -*/login/*
 * -*.zip
 * -*.mp4
 */
data class UrlFilter(
    val rules: List<String> = emptyList()
) {
    private data class Compiled(
        val include: Boolean,
        val regex: Regex
    )

    private val compiled: List<Compiled> by lazy {
        rules.mapNotNull { raw ->
            val t = raw.trim()
            if (t.isEmpty()) return@mapNotNull null
            val include = !t.startsWith("-")
            val body = when {
                t.startsWith("+") || t.startsWith("-") -> t.substring(1)
                else -> t
            }
            Compiled(include = include, regex = globToRegex(body))
        }
    }

    private val hasIncludes: Boolean by lazy { compiled.any { it.include } }

    /**
     * @return null if allowed; otherwise skip reason
     */
    fun rejectReason(url: String, localPath: String? = null): String? {
        if (compiled.isEmpty()) return null
        val target = url
        val pathTarget = localPath ?: url

        var matchedInclude = !hasIncludes
        for (c in compiled) {
            val hit = c.regex.containsMatchIn(target) || c.regex.containsMatchIn(pathTarget)
            if (!hit) continue
            if (!c.include) {
                return "excluded by filter"
            }
            matchedInclude = true
        }
        if (hasIncludes && !matchedInclude) {
            return "not in include rules"
        }
        return null
    }

    fun allows(url: String, localPath: String? = null): Boolean =
        rejectReason(url, localPath) == null

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        sb.append('^')
        for (ch in glob) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    companion object {
        fun fromLines(text: String): UrlFilter {
            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            return UrlFilter(lines)
        }
    }
}
