package com.heimish.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val build: Int, val tag: String, val url: String, val notes: String, val sizeBytes: Long)

object Updater {
    private const val API = "https://api.github.com/repos/848848448/heimish-messages/releases/latest"
    private val BUILD_RE = Regex("-b(\\d+)")

    /** Blocking. Returns info about the latest release (even if not newer). Null on network/parse failure. */
    fun fetchLatest(): UpdateInfo? = runCatching {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (c.responseCode != 200) return null
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = j.optString("tag_name")
        val build = BUILD_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        var url = ""; var size = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
        }
        if (url.isEmpty()) return null
        UpdateInfo(build, tag, url, j.optString("body"), size)
    }.getOrNull()

    fun isNewer(info: UpdateInfo) = info.build > BuildConfig.BUILD_NUMBER

    /** Blocking download with progress 0..1. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? = runCatching {
        val out = File(ctx.cacheDir, "update.apk")
        if (out.exists()) out.delete()
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
        val total = (if (c.contentLengthLong > 0) c.contentLengthLong else info.sizeBytes).coerceAtLeast(1)
        var done = 0L; var lastP = 0f
        c.inputStream.use { ins -> out.outputStream().use { os ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                os.write(buf, 0, n); done += n
                val p = done.toFloat() / total
                if (p - lastP > 0.01f) { lastP = p; onProgress(p.coerceAtMost(1f)) }
            }
        } }
        onProgress(1f)
        if (out.length() < 100_000) null else out
    }.getOrNull()

    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
