package com.example.webmirror.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

enum class DefaultSaveFormat {
    FOLDER,
    ZIP
}

/**
 * Lightweight prefs (SharedPreferences) for settings that should survive restarts.
 */
class AppPreferences(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var defaultSaveFormat: DefaultSaveFormat
        get() = runCatching {
            DefaultSaveFormat.valueOf(sp.getString(KEY_SAVE_FORMAT, DefaultSaveFormat.FOLDER.name)!!)
        }.getOrDefault(DefaultSaveFormat.FOLDER)
        set(value) = sp.edit().putString(KEY_SAVE_FORMAT, value.name).apply()

    /** SAF tree URI string for custom output root; null = public Download/WebMirror */
    var saveTreeUri: String?
        get() = sp.getString(KEY_SAVE_TREE, null)
        set(value) = sp.edit().putString(KEY_SAVE_TREE, value).apply()

    var logRetentionDays: Int
        get() = sp.getInt(KEY_LOG_DAYS, 7).coerceIn(1, 90)
        set(value) = sp.edit().putInt(KEY_LOG_DAYS, value.coerceIn(1, 90)).apply()

    var autoCleanLogs: Boolean
        get() = sp.getBoolean(KEY_AUTO_LOG, true)
        set(value) = sp.edit().putBoolean(KEY_AUTO_LOG, value).apply()

    var rewriteLinks: Boolean
        get() = sp.getBoolean(KEY_REWRITE, true)
        set(value) = sp.edit().putBoolean(KEY_REWRITE, value).apply()

    var sameDomainOnly: Boolean
        get() = sp.getBoolean(KEY_SAME_DOMAIN, true)
        set(value) = sp.edit().putBoolean(KEY_SAME_DOMAIN, value).apply()

    var respectRobots: Boolean
        get() = sp.getBoolean(KEY_ROBOTS, true)
        set(value) = sp.edit().putBoolean(KEY_ROBOTS, value).apply()

    var maxDepth: Int
        get() = sp.getInt(KEY_DEPTH, 5).coerceAtLeast(0)
        set(value) = sp.edit().putInt(KEY_DEPTH, value.coerceAtLeast(0)).apply()

    var maxWorkers: Int
        get() = sp.getInt(KEY_WORKERS, 4).coerceIn(1, 16)
        set(value) = sp.edit().putInt(KEY_WORKERS, value.coerceIn(1, 16)).apply()

    fun resolvedTreeUri(): Uri? = saveTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * Default public folder: /storage/emulated/0/Download/WebMirror
     * May require storage permission on older APIs; scoped storage may still block on some devices.
     */
    fun defaultPublicDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "WebMirror").also { it.mkdirs() }
    }

    companion object {
        private const val PREFS = "webmirror_settings"
        private const val KEY_SAVE_FORMAT = "save_format"
        private const val KEY_SAVE_TREE = "save_tree_uri"
        private const val KEY_LOG_DAYS = "log_days"
        private const val KEY_AUTO_LOG = "auto_clean_logs"
        private const val KEY_REWRITE = "rewrite_links"
        private const val KEY_SAME_DOMAIN = "same_domain"
        private const val KEY_ROBOTS = "robots"
        private const val KEY_DEPTH = "max_depth"
        private const val KEY_WORKERS = "max_workers"
    }
}
