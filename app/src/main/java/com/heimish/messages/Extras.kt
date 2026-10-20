package com.heimish.messages

import android.app.KeyguardManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private fun prefs(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)

// ── Spam ─────────────────────────────────────────────────────────────────────
object SpamStore {
    private const val KEY = "spam_threads"
    fun all(ctx: Context): Set<Long> = (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull() }.toSet()
    fun add(ctx: Context, t: Long) { val s = (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet(); s.add(t.toString()); prefs(ctx).edit().putStringSet(KEY, s).apply() }
    fun remove(ctx: Context, t: Long) { val s = (prefs(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet(); s.remove(t.toString()); prefs(ctx).edit().putStringSet(KEY, s).apply() }
}

object SpamFilter {
    private val strong = listOf(
        Regex("(?i)\\b(you('ve| have) (won|been selected)|claim your (prize|reward|gift)|free (gift|iphone|prize)|winner)\\b"),
        Regex("(?i)\\b(bit\\.ly|tinyurl\\.com|cutt\\.ly|t\\.co/|rb\\.gy|is\\.gd)/"),
        Regex("(?i)\\b(loan (approved|offer)|crypto (giveaway|investment)|double your (money|bitcoin)|adult dating|casino bonus)\\b"),
        Regex("(?i)\\b(your (account|package|parcel) (is|has been) (suspended|locked|on hold)|unusual activity).*https?://")
    )
    private val otp = Regex("(?i)(code|otp|verification|passcode)")
    fun isSpam(ctx: Context, address: String, body: String): Boolean {
        if (!prefs(ctx).getBoolean("spam_filter", true)) return false
        if (SmsRepository.getContactName(ctx, address) != null) return false
        if (otp.containsMatchIn(body) && !body.contains("http", true)) return false
        return strong.count { it.containsMatchIn(body) } >= 1
    }
}

// ── One-time codes auto delete ───────────────────────────────────────────────
object OtpCleaner {
    private val otpBody = Regex("(?i)(\\b\\d{4,8}\\b.*(code|otp|verification|passcode)|(code|otp|verification|passcode).*\\b\\d{4,8}\\b)")
    fun clean(ctx: Context) {
        if (!prefs(ctx).getBoolean("auto_delete_otp", false)) return
        runCatching {
            val cutoff = System.currentTimeMillis() - 24L * 3600_000L
            val ids = ArrayList<Long>()
            ctx.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                Telephony.Sms.DATE + " < ?", arrayOf(cutoff.toString()), null)?.use { c ->
                while (c.moveToNext()) if (otpBody.containsMatchIn(c.getString(1) ?: "") && (c.getString(1) ?: "").length < 200) ids.add(c.getLong(0))
            }
            ids.forEach { ctx.contentResolver.delete(Uri.parse("content://sms/$it"), null, null) }
        }
    }
}

// ── Backup / restore (JSON) ──────────────────────────────────────────────────
object Backup {
    fun export(ctx: Context, uri: Uri): Int {
        val arr = JSONArray()
        ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ),
            null, null, Telephony.Sms.DATE + " ASC")?.use { c ->
            while (c.moveToNext()) arr.put(JSONObject().put("a", c.getString(0) ?: "").put("b", c.getString(1) ?: "").put("d", c.getLong(2)).put("t", c.getInt(3)).put("r", c.getInt(4)))
        }
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(JSONObject().put("app", "message").put("version", 1).put("sms", arr).toString().toByteArray()) }
        return arr.length()
    }
    fun restore(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return 0
        val arr = JSONObject(text).getJSONArray("sms")
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val addr = o.getString("a"); val d = o.getLong("d"); val b = o.getString("b")
            val exists = ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                Telephony.Sms.ADDRESS + "=? AND " + Telephony.Sms.DATE + "=? AND " + Telephony.Sms.BODY + "=?", arrayOf(addr, d.toString(), b), null)?.use { it.count > 0 } ?: false
            if (exists) continue
            val v = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, addr); put(Telephony.Sms.BODY, b); put(Telephony.Sms.DATE, d)
                put(Telephony.Sms.TYPE, o.getInt("t")); put(Telephony.Sms.READ, o.optInt("r", 1))
            }
            if (ctx.contentResolver.insert(Telephony.Sms.CONTENT_URI, v) != null) n++
        }
        return n
    }
}

// ── Widget ───────────────────────────────────────────────────────────────────
class MessageWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val convs = runCatching { SmsRepository.loadConversations(ctx) }.getOrDefault(emptyList())
                val unread = convs.sumOf { it.unreadCount }
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                for (id in ids) {
                    val rv = RemoteViews(ctx.packageName, R.layout.widget_messages)
                    rv.setTextViewText(R.id.w_title, if (unread > 0) "Message · $unread unread" else "Message")
                    val rows = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3)
                    for (i in rows.indices) {
                        val c = convs.getOrNull(i)
                        rv.setTextViewText(rows[i], if (c == null) "" else (c.displayName + ": " + c.snippet.replace('\n', ' ')))
                    }
                    rv.setOnClickPendingIntent(R.id.w_root, open)
                    mgr.updateAppWidget(id, rv)
                }
            } finally { pending.finish() }
        }.start()
    }
    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, MessageWidget::class.java))
            if (ids.isNotEmpty()) ctx.sendBroadcast(Intent(ctx, MessageWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }
}

// ── App lock ─────────────────────────────────────────────────────────────────
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) onUnlock() }
    fun ask() {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val i = km.createConfirmDeviceCredentialIntent("Message", "Unlock to read your messages")
        if (i == null) onUnlock() else launcher.launch(i)
    }
    LaunchedEffect(Unit) { ask() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("Message is locked", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { ask() }) { Text("Unlock") }
    }
}

// ── Screens ──────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    LaunchedEffect(tick) {
        val ids = SpamStore.all(ctx)
        list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }.filter { it.threadId in ids }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Spam", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { pad ->
        if (list.isEmpty()) Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No spam", color = MaterialTheme.colorScheme.outline) }
        else LazyColumn(Modifier.padding(pad)) {
            items(list, key = { it.threadId }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.displayName, c.address, 44.dp, 17.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                        Text(c.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ SpamStore.remove(ctx, c.threadId); tick++ }) { Text("Not spam") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtraScreen(key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = prefs(ctx)
    val scope = rememberCoroutineScope()
    val title = when (key) { "spam" -> "Spam protection"; "lock" -> "App lock"; "otp" -> "Verification codes"; "backup" -> "Backup & restore"; else -> "Settings" }
    var spamOn by remember { mutableStateOf(p.getBoolean("spam_filter", true)) }
    var lockOn by remember { mutableStateOf(p.getBoolean("app_lock", false)) }
    var otpOn by remember { mutableStateOf(p.getBoolean("auto_delete_otp", false)) }
    var status by remember { mutableStateOf("") }
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { status = "Exporting…"; val n = withContext(Dispatchers.IO) { runCatching { Backup.export(ctx, uri) }.getOrElse { -1 } }; status = if (n >= 0) "Saved $n messages" else "Export failed" }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { status = "Restoring…"; val n = withContext(Dispatchers.IO) { runCatching { Backup.restore(ctx, uri) }.getOrElse { -1 } }; status = if (n >= 0) "Restored $n new messages" else "Restore failed. Is this a Message backup?" }
    }
    @Composable fun SwitchRow(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface); Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(checked = on, onCheckedChange = onChange)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.padding(pad)) {
            when (key) {
                "spam" -> {
                    SwitchRow("Filter spam", "Texts from unknown numbers that look like scams go to Spam without a notification", spamOn) { spamOn = it; p.edit().putBoolean("spam_filter", it).apply() }
                    Text("Open the menu on the main screen and choose Spam to review them.", Modifier.padding(18.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
                "lock" -> SwitchRow("Lock with screen lock", "Ask for your fingerprint, face, PIN or pattern when you open the app", lockOn) {
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (it && !km.isDeviceSecure) Toast.makeText(ctx, "Set a screen lock in Android settings first", Toast.LENGTH_LONG).show()
                    else { lockOn = it; p.edit().putBoolean("app_lock", it).apply() }
                }
                "otp" -> SwitchRow("Delete codes after 24 hours", "Removes old one-time and verification code texts", otpOn) { otpOn = it; p.edit().putBoolean("auto_delete_otp", it).apply(); if (it) scope.launch(Dispatchers.IO) { OtpCleaner.clean(ctx) } }
                "backup" -> {
                    Text("Save all your text messages to a file, or restore them from one. Restoring skips messages that already exist.", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ exportL.launch("message-backup.json") }, Modifier.padding(horizontal = 18.dp)) { Text("Back up messages") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton({ importL.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, Modifier.padding(horizontal = 18.dp)) { Text("Restore from file") }
                    if (status.isNotEmpty()) Text(status, Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
