package com.heimish.messages

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Runs before anything else in the app's process (activities, receivers, jobs) and installs the crash catcher. */
class HeimishApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashCatcher.install(this)
    }
}

/**
 * When the app crashes, the error is saved to a small file. The next time the app opens it shows
 * "The app closed unexpectedly" with the details and a Copy button, so the crash can be sent in.
 */
object CrashCatcher {
    private const val FILE = "last_crash.txt"

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Catcher) return
        Thread.setDefaultUncaughtExceptionHandler(Catcher(app, previous))
    }

    private class Catcher(val app: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { File(app.filesDir, FILE).writeText(report(t, e)) }
            previous?.uncaughtException(t, e)
        }
    }

    internal fun report(t: Thread, e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val trace = sw.toString().let { if (it.length > 12_000) it.take(12_000) + "\n…" else it }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "Heimish Messages 3.6 (build ${BuildConfig.BUILD_NUMBER})\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "$time, thread ${t.name}\n\n$trace"
    }

    /** The saved crash report, or null. */
    fun pending(ctx: Context): String? = runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() } }
}

/** Shown once after a crash: what happened, selectable, with Copy. */
@Composable
fun CrashReportDialog() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf(CrashCatcher.pending(ctx)) }
    val r = report ?: return
    val close = { CrashCatcher.clear(ctx); report = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("The app closed unexpectedly") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(r, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        },
        confirmButton = {
            TextButton({
                runCatching {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Crash report", r))
                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }
                close()
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(close) { Text("Close") } }
    )
}
