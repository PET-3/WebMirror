package com.example.webmirror.engine.http

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple persistent cookie jar (SharedPreferences).
 * Enough for session continuity across runs; not a full browser store.
 */
class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs = context.applicationContext.getSharedPreferences("webmirror_cookies", Context.MODE_PRIVATE)
    private val memory = mutableMapOf<String, MutableList<Cookie>>()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val list = memory.getOrPut(host) { mutableListOf() }
        for (c in cookies) {
            list.removeAll { it.name == c.name && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) {
                list.add(c)
            }
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val now = System.currentTimeMillis()
        val list = memory[host]?.filter { it.expiresAt > now && (it.hostOnly || host.endsWith(it.domain)) }
            ?: emptyList()
        return list.filter { url.encodedPath.startsWith(it.path) || it.path == "/" }
    }

    fun clear() {
        memory.clear()
        prefs.edit().remove(KEY).apply()
    }

    fun importFromHeader(setCookieLines: List<String>, url: String) {
        val httpUrl = HttpUrl.parse(url) ?: return
        val cookies = setCookieLines.mapNotNull { Cookie.parse(httpUrl, it) }
        if (cookies.isNotEmpty()) saveFromResponse(httpUrl, cookies)
    }

    private fun persist() {
        val arr = JSONArray()
        for ((_, cookies) in memory) {
            for (c in cookies) {
                val o = JSONObject()
                o.put("name", c.name)
                o.put("value", c.value)
                o.put("domain", c.domain)
                o.put("path", c.path)
                o.put("expiresAt", c.expiresAt)
                o.put("secure", c.secure)
                o.put("httpOnly", c.httpOnly)
                o.put("hostOnly", c.hostOnly)
                arr.put(o)
            }
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val expires = o.optLong("expiresAt", 0)
                if (expires in 1 until now) continue
                val domain = o.getString("domain")
                val builder = Cookie.Builder()
                    .name(o.getString("name"))
                    .value(o.getString("value"))
                    .path(o.optString("path", "/"))
                    .expiresAt(if (expires > 0) expires else now + 86400_000L * 30)
                if (o.optBoolean("hostOnly", true)) {
                    builder.hostOnlyDomain(domain)
                } else {
                    builder.domain(domain)
                }
                if (o.optBoolean("secure", false)) builder.secure()
                if (o.optBoolean("httpOnly", false)) builder.httpOnly()
                val cookie = builder.build()
                memory.getOrPut(domain.trimStart('.')) { mutableListOf() }.add(cookie)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val KEY = "cookies_json"
    }
}
