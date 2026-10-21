package com.heimish.messages

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Where an update stands, shared by the in-app screens and the notifications. */
sealed class UpdState {
    data object Idle : UpdState()
    data class Downloading(val progress: Float, val doneBytes: Long, val totalBytes: Long) : UpdState()
    data class Ready(val file: File) : UpdState()
    data object Installing : UpdState()
    data class Error(val message: String) : UpdState()
}

/**
 * One update flow for the whole app: the download keeps going when you leave the screen,
 * shows a progress notification like every app store, then a "ready to install" notification.
 */
object UpdateManager {
    var state by mutableStateOf<UpdState>(UpdState.Idle)
    var info by mutableStateOf<UpdateInfo?>(null)
    @Volatile var uiVisible = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    const val CH_PROGRESS = "upd_progress"
    const val CH_AVAILABLE = "upd_available"
    const val NID_PROGRESS = 7700
    const val NID_AVAILABLE = 7701
    const val NID_READY = 7702

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Update download", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress while an app update downloads"; setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_AVAILABLE, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a new version of Message is available or ready to install" })
    }

    fun mb(bytes: Long): String = if (bytes <= 0) "" else String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)

    /** Opens the app on the update screen (and installs, when [install] is true). */
    fun openAppIntent(ctx: Context, install: Boolean): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_update", true).putExtra("install_update", install)
        return PendingIntent.getActivity(ctx, if (install) 7711 else 7710, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun downloadPendingIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, UpdateDownloadService::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) PendingIntent.getForegroundService(ctx, 7712, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        else PendingIntent.getService(ctx, 7712, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Starts (or resumes showing) the download. Safe to call repeatedly. */
    fun startDownload(ctx: Context, i: UpdateInfo? = info) {
        if (state is UpdState.Downloading || state is UpdState.Installing) return
        (state as? UpdState.Ready)?.let { if (it.file.exists()) return }
        if (i != null) info = i
        val app = ctx.applicationContext
        val svc = Intent(app, UpdateDownloadService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(svc) else app.startService(svc)
        }.onFailure { runDownload(app) { } } // fall back to an in-process download
    }

    /** The actual download; [onDone] runs when it finishes either way. */
    internal fun runDownload(ctx: Context, onDone: () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                ensureChannels(ctx)
                val i = info ?: Updater.fetchLatest()?.also { info = it }
                if (i == null || !Updater.isNewer(i)) { state = UpdState.Error(if (i == null) "Could not check: " + Updater.lastError.ifBlank { "unknown error" } else "You already have the newest version"); return@launch }
                state = UpdState.Downloading(0f, 0, i.sizeBytes)
                var lastShown = -1
                val file = Updater.download(ctx, i) { p ->
                    val total = i.sizeBytes
                    state = UpdState.Downloading(p, (p * total).toLong(), total)
                    val pct = (p * 100).toInt()
                    if (pct != lastShown && (pct - lastShown >= 2 || pct == 100)) { lastShown = pct; showProgress(ctx, i, pct) }
                }
                NotificationManagerCompat.from(ctx).cancel(NID_PROGRESS)
                if (file == null) {
                    state = UpdState.Error("Download failed. Tap to try again.")
                    notifyError(ctx)
                    return@launch
                }
                state = UpdState.Ready(file)
                if (uiVisible && Updater.canInstall(ctx)) install(ctx) else notifyReady(ctx, i)
            } finally { onDone() }
        }
    }

    fun install(ctx: Context) {
        val f = (state as? UpdState.Ready)?.file ?: return
        NotificationManagerCompat.from(ctx).cancel(NID_READY)
        state = UpdState.Installing
        if (!Updater.install(ctx, f)) state = UpdState.Error("Could not start the install. Tap to try again.")
    }

    fun progressNotification(ctx: Context, i: UpdateInfo?, pct: Int): android.app.Notification {
        ensureChannels(ctx)
        val total = i?.sizeBytes ?: 0L
        val text = if (total > 0) "${mb((total * pct / 100.0).toLong())} of ${mb(total)}" else "$pct%"
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(R.drawable.ms_download)
            .setContentTitle("Downloading update" + (i?.let { " · build ${it.build}" } ?: ""))
            .setContentText(text)
            .setSubText("$pct%")
            .setProgress(100, pct, pct <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(ctx, false))
            .build()
    }

    private fun showProgress(ctx: Context, i: UpdateInfo, pct: Int) {
        try { NotificationManagerCompat.from(ctx).notify(NID_PROGRESS, progressNotification(ctx, i, pct)) } catch (_: SecurityException) {}
    }

    fun notifyReady(ctx: Context, i: UpdateInfo) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_AVAILABLE)
            .setSmallIcon(R.drawable.ms_download_done)
            .setContentTitle("Update ready to install")
            .setContentText("Message ${BuildConfig.VERSION_NAME} build ${i.build} · tap to install")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notesSummary(i.notes).ifBlank { "Tap to install build ${i.build}" }))
            .setContentIntent(openAppIntent(ctx, true))
            .addAction(R.drawable.ms_download_done, "Install", openAppIntent(ctx, true))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NID_READY, n) } catch (_: SecurityException) {}
    }

    private fun notifyError(ctx: Context) {
        val n = NotificationCompat.Builder(ctx, CH_AVAILABLE)
            .setSmallIcon(R.drawable.ms_error)
            .setContentTitle("Update download failed")
            .setContentText("Tap to try again")
            .setContentIntent(openAppIntent(ctx, false))
            .setAutoCancel(true).build()
        try { NotificationManagerCompat.from(ctx).notify(NID_READY, n) } catch (_: SecurityException) {}
    }

    fun notifyAvailable(ctx: Context, i: UpdateInfo) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_AVAILABLE)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("New update available · build ${i.build}")
            .setContentText(notesSummary(i.notes).ifBlank { "Tap to see what's new" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notesSummary(i.notes).ifBlank { "Tap to see what's new" }))
            .setContentIntent(openAppIntent(ctx, false))
            .addAction(R.drawable.ms_download, "Update now", downloadPendingIntent(ctx))
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NID_AVAILABLE, n) } catch (_: SecurityException) {}
    }

    fun notesSummary(notes: String): String =
        notes.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("*") }.take(4)
            .joinToString("\n") { "• " + it.trimStart('-', '*', ' ') }
}

/** Foreground service that runs the APK download with a progress notification. */
class UpdateDownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = UpdateManager.progressNotification(this, UpdateManager.info, 0)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(UpdateManager.NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(UpdateManager.NID_PROGRESS, n)
        }
        NotificationManagerCompat.from(this).cancel(UpdateManager.NID_AVAILABLE)
        UpdateManager.runDownload(applicationContext) {
            runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}

/** Checks GitHub for a new release a few times a day and posts "New update available". */
class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("upd_notify", true)) return
        val pending = goAsync()
        Thread {
            try {
                val i = Updater.fetchLatest() ?: return@Thread
                prefs.edit().putLong("upd_last", System.currentTimeMillis()).putInt("upd_latest", i.build).apply()
                if (Updater.isNewer(i) && prefs.getInt("upd_notified", 0) < i.build) {
                    UpdateManager.info = i
                    prefs.edit().putInt("upd_notified", i.build).apply()
                    UpdateManager.notifyAvailable(ctx, i)
                }
            } catch (_: Exception) {
            } finally { pending.finish() }
        }.start()
    }

    companion object {
        fun schedule(ctx: Context) {
            runCatching {
                val pi = PendingIntent.getBroadcast(ctx, 7720, Intent(ctx, UpdateCheckReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 15 * 60_000L, 6 * 60 * 60_000L, pi)
            }
        }
    }
}
