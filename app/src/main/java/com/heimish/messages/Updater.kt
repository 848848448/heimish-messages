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

    @Volatile var lastError: String = ""
    private const val UA = "Message-App/1.0 (Android)"
    private const val WEB = "https://github.com/848848448/heimish-messages/releases"

    private fun conn(url: String, follow: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = follow
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    /** Blocking. Returns info about the latest release (even if not newer). Null on failure, with lastError set. */
    fun fetchLatest(): UpdateInfo? {
        lastError = ""
        val errs = ArrayList<String>()
        try {
            val c = conn(API); c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode == 200) {
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.optString("tag_name")
                val build = BUILD_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull()
                val assets = j.optJSONArray("assets")
                var url = ""; var size = 0L
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
                }
                if (build != null && url.isNotEmpty()) return UpdateInfo(build, tag, url, aggregateNotes(j.optString("body")), size)
                errs.add("api: no apk in latest release")
            } else errs.add("api: HTTP " + c.responseCode)
        } catch (e: Exception) { errs.add("api: " + e.javaClass.simpleName + " " + (e.message ?: "")) }
        // Fallback without the API (no rate limit): the web redirect of /releases/latest
        try {
            val c = conn("$WEB/latest", follow = false)
            val loc = c.getHeaderField("Location") ?: ""
            val tag = loc.substringAfterLast("/tag/", "")
            val build = BUILD_RE.find(tag)?.groupValues?.get(1)?.toIntOrNull()
            if (build != null) {
                val apk = "$WEB/download/$tag/app-debug.apk"
                val h = conn(apk); h.requestMethod = "HEAD"
                val size = if (h.responseCode == 200) h.contentLengthLong.coerceAtLeast(0L) else { errs.add("web: apk HTTP " + h.responseCode); -1L }
                if (size >= 0) return UpdateInfo(build, tag, apk, "", size)
            } else errs.add("web: HTTP " + c.responseCode + " " + loc.take(60))
        } catch (e: Exception) { errs.add("web: " + e.javaClass.simpleName + " " + (e.message ?: "")) }
        lastError = errs.joinToString("; ")
        return null
    }

    /** Notes of every release newer than the installed build, merged, repeated lines removed. */
    private fun aggregateNotes(latestBody: String): String {
        val news = LinkedHashSet<String>(); val fixed = LinkedHashSet<String>()
        fun add(body: String) {
            var sec = news
            for (raw in body.lines()) {
                val l = raw.trim(); if (l.isEmpty()) continue
                when {
                    l.equals("New", true) || l.equals("New:", true) -> sec = news
                    l.equals("Fixed", true) || l.equals("Fixed:", true) -> sec = fixed
                    l.startsWith("-") || l.startsWith("*") -> sec.add(l)
                }
            }
        }
        var got = false
        runCatching {
            val c = URL("https://api.github.com/repos/848848448/heimish-messages/releases?per_page=30").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", UA)
            if (c.responseCode == 200) {
                val arr = org.json.JSONArray(c.inputStream.bufferedReader().readText())
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    if (r.optBoolean("prerelease") || r.optBoolean("draft")) continue
                    val b = BUILD_RE.find(r.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    if (b > BuildConfig.BUILD_NUMBER) { add(r.optString("body")); got = true }
                }
            }
        }
        if (!got) add(latestBody)
        val sb = StringBuilder()
        if (news.isNotEmpty()) { sb.append("New\n"); news.forEach { sb.append(it).append('\n') } }
        if (fixed.isNotEmpty()) { sb.append("Fixed\n"); fixed.forEach { sb.append(it).append('\n') } }
        return sb.toString()
    }

    fun isNewer(info: UpdateInfo) = info.build > BuildConfig.BUILD_NUMBER

    /** Blocking download with progress 0..1. */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File? = runCatching {
        val out = File(ctx.cacheDir, "update.apk")
        if (out.exists()) out.delete()
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15_000; c.readTimeout = 30_000; c.setRequestProperty("User-Agent", UA)
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
        // An update drops the app's alarms: put scheduled messages and reminders back
        ScheduledSendReceiver.rearmAll(ctx)
        RemindReceiver.rearmAll(ctx)
        runCatching { AutoBackup.schedule(ctx) }
        // Download picture/group messages that are still waiting (e.g. from before this update)
        Thread { runCatching { MmsIncoming.retryPending(ctx.applicationContext, force = true) } }.start()
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { ctx.startActivity(open) }
        Notifications.showSimple(ctx, "Message updated", "Now on version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})", 7777)
    }
}
