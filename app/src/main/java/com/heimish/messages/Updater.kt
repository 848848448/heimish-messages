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

    /** Installs through a PackageInstaller session. On Android 12+ an update from the installer of record needs no confirmation. */
    fun install(ctx: Context, apk: File): Boolean = runCatching {
        val pi = ctx.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            apk.inputStream().use { ins -> session.openWrite("update", 0, apk.length()).use { out -> ins.copyTo(out); session.fsync(out) } }
            val cb = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
            val pend = android.app.PendingIntent.getBroadcast(ctx, id, cb, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
            session.commit(pend.intentSender)
        }
        true
    }.getOrElse { android.util.Log.e("Updater", "install failed", it); false }
}

/** Receives the installer's result. Shows the system confirmation only when Android requires it. */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -1)
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) runCatching { ctx.startActivity(confirm) }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> prefs.edit().remove("upd_error").apply()
            else -> {
                val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                prefs.edit().putString("upd_error", msg).apply()
                android.util.Log.e("Updater", "status=$status $msg")
            }
        }
    }
}

/** After the app is replaced, tell the user and try to reopen. */
class PackageReplacedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Notifications.init(ctx)
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putLong("upd_last", 0L).apply()
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { ctx.startActivity(open) }
        Notifications.showSimple(ctx, "Message updated", "Now on version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER}). Tap to open.", 7777)
    }
}
