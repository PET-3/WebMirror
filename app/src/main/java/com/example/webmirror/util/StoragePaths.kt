package com.example.webmirror.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * Resolves mirror output directory.
 * Default: public Download/WebMirror (when creatable), else app external files.
 * User can override path in Settings (absolute path string).
 */
object StoragePaths {

    private const val PREFS = "webmirror_settings"
    private const val KEY_PATH = "download_dir"
    private const val KEY_LOG_AUTO_CLEAN = "log_auto_clean"
    private const val KEY_LOG_KEEP_DAYS = "log_keep_days"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun defaultPublicDownloadDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WebMirror"
        )

    fun appFallbackDir(ctx: Context): File {
        val external = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        return if (external != null) {
            File(external, "WebMirror")
        } else {
            File(ctx.filesDir, "WebMirror")
        }
    }

    fun getDownloadDir(ctx: Context): File {
        val custom = prefs(ctx).getString(KEY_PATH, null)
        if (!custom.isNullOrBlank()) {
            val f = File(custom)
            if (f.mkdirs() || f.isDirectory) return f
        }
        val pub = defaultPublicDownloadDir()
        return try {
            if (pub.mkdirs() || pub.isDirectory) {
                // verify write
                val probe = File(pub, ".wm_write_test")
                probe.writeText("ok")
                probe.delete()
                pub
            } else {
                appFallbackDir(ctx).also { it.mkdirs() }
            }
        } catch (_: Exception) {
            appFallbackDir(ctx).also { it.mkdirs() }
        }
    }

    fun setDownloadDir(ctx: Context, path: String?) {
        prefs(ctx).edit().apply {
            if (path.isNullOrBlank()) remove(KEY_PATH) else putString(KEY_PATH, path)
            apply()
        }
    }

    fun isLogAutoCleanEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOG_AUTO_CLEAN, true)

    fun setLogAutoCleanEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOG_AUTO_CLEAN, enabled).apply()
    }

    fun logKeepDays(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LOG_KEEP_DAYS, 7).coerceIn(1, 90)

    fun setLogKeepDays(ctx: Context, days: Int) {
        prefs(ctx).edit().putInt(KEY_LOG_KEEP_DAYS, days.coerceIn(1, 90)).apply()
    }
}
