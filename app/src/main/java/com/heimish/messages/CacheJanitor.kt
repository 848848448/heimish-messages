package com.heimish.messages

import android.content.Context
import android.util.Log
import java.io.File

/** Removes temporary files the app leaves in its cache (camera photos, keyboard GIFs/stickers, voice recordings,
 *  forward copies, a downloaded update) once they are 2 days old. Runs at most once a day, off the main thread. */
object CacheJanitor {
    private const val MAX_AGE = 2 * 24 * 60 * 60_000L
    private val PREFIXES = listOf("photo_", "kb_", "voice_", "share_", "cam_")

    fun runIfDue(ctx: Context) = runCatching {
        val p = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("cache_clean_last", 0L) < 24 * 60 * 60_000L) return@runCatching
        p.edit().putLong("cache_clean_last", now).apply()
        val cutoff = now - MAX_AGE
        var n = 0; var bytes = 0L
        fun del(f: File) { val l = f.length(); if (f.delete()) { n++; bytes += l } }
        ctx.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff && (PREFIXES.any { f.name.startsWith(it) } || f.name == "update.apk")) del(f)
        }
        for (dir in listOf("forward", "media_open")) File(ctx.cacheDir, dir).listFiles()?.forEach { f -> if (f.isFile && f.lastModified() < cutoff) del(f) }
        Log.d("CacheJanitor", "removed $n old cache files, ${bytes / 1024} KB")
    }.onFailure { Log.w("CacheJanitor", "cleanup failed", it) }
}
