package com.example.webmirror.engine.extract

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Web App Manifest (manifest.json) resource discovery.
 */
object ManifestExtractor {

    fun extract(jsonText: String, baseUrl: String): Set<String> {
        val out = linkedSetOf<String>()
        try {
            val root = JSONObject(jsonText)
            add(root.optString("start_url"), baseUrl, out)
            add(root.optString("scope"), baseUrl, out)

            // icons
            val icons = root.optJSONArray("icons")
            if (icons != null) {
                for (i in 0 until icons.length()) {
                    val icon = icons.optJSONObject(i) ?: continue
                    add(icon.optString("src"), baseUrl, out)
                }
            }

            // screenshots
            val shots = root.optJSONArray("screenshots")
            if (shots != null) {
                for (i in 0 until shots.length()) {
                    val s = shots.optJSONObject(i) ?: continue
                    add(s.optString("src"), baseUrl, out)
                }
            }

            // related_applications may have URLs — skip store links usually
            val shortcuts = root.optJSONArray("shortcuts")
            if (shortcuts != null) {
                for (i in 0 until shortcuts.length()) {
                    val sc = shortcuts.optJSONObject(i) ?: continue
                    add(sc.optString("url"), baseUrl, out)
                    val scIcons = sc.optJSONArray("icons")
                    if (scIcons != null) {
                        for (j in 0 until scIcons.length()) {
                            add(scIcons.optJSONObject(j)?.optString("src"), baseUrl, out)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // not valid JSON — ignore
        }
        return out
    }

    private fun add(raw: String?, base: String, out: MutableSet<String>) {
        if (raw.isNullOrBlank()) return
        try {
            val abs = URI(base).resolve(raw.trim()).toString().substringBefore('#')
            if (abs.startsWith("http")) out.add(abs)
        } catch (_: Exception) {
        }
    }
}
