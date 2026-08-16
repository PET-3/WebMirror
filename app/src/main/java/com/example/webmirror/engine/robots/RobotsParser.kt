package com.example.webmirror.engine.robots

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal robots.txt parser: User-agent groups, Allow / Disallow.
 * Default: respect robots for User-agent: * (and matching agent).
 */
class RobotsParser(
    private val userAgentToken: String = "WebMirror"
) {
    private val cache = ConcurrentHashMap<String, RobotsRules>()

    data class RobotsRules(
        val allows: List<String> = emptyList(),
        val disallows: List<String> = emptyList()
    ) {
        fun isAllowed(path: String): Boolean {
            val p = if (path.startsWith("/")) path else "/$path"
            var bestAllow = -1
            var bestDisallow = -1
            for (a in allows) {
                if (pathMatches(p, a)) bestAllow = maxOf(bestAllow, a.length)
            }
            for (d in disallows) {
                if (d.isEmpty()) continue // empty disallow = allow all
                if (pathMatches(p, d)) bestDisallow = maxOf(bestDisallow, d.length)
            }
            if (bestDisallow < 0 && bestAllow < 0) return true
            return bestAllow >= bestDisallow
        }

        private fun pathMatches(path: String, pattern: String): Boolean {
            if (pattern.isEmpty()) return true
            // Support trailing * only
            return if (pattern.endsWith("*")) {
                path.startsWith(pattern.dropLast(1))
            } else {
                path.startsWith(pattern)
            }
        }
    }

    fun originOf(url: String): String? = try {
        val u = URI(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) {
        null
    }

    fun robotsUrl(origin: String): String = "$origin/robots.txt"

    fun parse(text: String): RobotsRules {
        val allows = mutableListOf<String>()
        val disallows = mutableListOf<String>()
        var applicable = false
        for (raw in text.lines()) {
            val line = raw.substringBefore("#").trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (key) {
                "user-agent" -> {
                    applicable = value == "*" ||
                            value.contains(userAgentToken, ignoreCase = true) ||
                            value.equals("WebMirror", true)
                }
                "disallow" -> if (applicable) disallows.add(value)
                "allow" -> if (applicable) allows.add(value)
            }
        }
        return RobotsRules(allows, disallows)
    }

    fun put(origin: String, rules: RobotsRules) {
        cache[origin] = rules
    }

    fun get(origin: String): RobotsRules? = cache[origin]

    fun isAllowed(url: String): Boolean {
        val origin = originOf(url) ?: return true
        val rules = cache[origin] ?: return true // not loaded yet → allow, engine will fetch robots first
        val path = try {
            URI(url).path ?: "/"
        } catch (_: Exception) {
            "/"
        }
        return rules.isAllowed(path)
    }

    fun clear() = cache.clear()
}
