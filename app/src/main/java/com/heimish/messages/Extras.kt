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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    val title = when (key) { "spam" -> "Spam protection"; "lock" -> "App lock"; "otp" -> "Verification codes"; "backup" -> "Backup & restore"; "quick" -> "Quick replies"; "access" -> "Large text & display"; "style" -> "Chat style"; else -> "Settings" }
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
                "quick" -> {
                    var txt by remember { mutableStateOf(QuickReplies.get(ctx).joinToString("\n")) }
                    Text("One reply per line. They show up in a chat under the menu, Quick replies.", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(txt, { txt = it; QuickReplies.set(ctx, it) }, Modifier.fillMaxWidth().padding(horizontal = 18.dp), minLines = 6)
                }
                "access" -> {
                    var fs by remember { mutableStateOf(p.getFloat("font_scale", 0f)) }
                    Text("Text size across the whole app", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("System default" to 0f, "Small" to 0.9f, "Large" to 1.15f, "Larger" to 1.3f, "Largest" to 1.5f).forEach { (l, v) ->
                        Row(Modifier.fillMaxWidth().clickable { fs = v; p.edit().putFloat("font_scale", v).apply(); (ctx as? android.app.Activity)?.recreate() }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(fs == v, { fs = v; p.edit().putFloat("font_scale", v).apply(); (ctx as? android.app.Activity)?.recreate() }); Spacer(Modifier.width(12.dp)); Text(l, fontSize = 16.sp * (if (v == 0f) 1f else v))
                        }
                    }
                }
                "style" -> {
                    var cr by remember { mutableStateOf(p.getInt("bubble_corner", 20)) }
                    var bg by remember { mutableStateOf(p.getInt("chat_bg", 0)) }
                    Text("Bubble shape", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("Round" to 20, "Soft" to 12, "Square" to 4).forEach { (l, v) ->
                            if (cr == v) Button({ }) { Text(l) } else OutlinedButton({ cr = v; p.edit().putInt("bubble_corner", v).apply() }) { Text(l) }
                        }
                    }
                    Text("Chat background", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(0, 0x33FFB74D, 0x3381C784, 0x3364B5F6, 0x33BA68C8, 0x33F06292).forEach { v ->
                            val col = if (v == 0) androidx.compose.ui.graphics.Color.Gray.copy(alpha = .15f) else androidx.compose.ui.graphics.Color(v)
                            Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col)
                                .border(if (bg == v) 3.dp else 1.dp, if (bg == v) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray.copy(alpha = .4f), androidx.compose.foundation.shape.CircleShape)
                                .clickable { bg = v; p.edit().putInt("chat_bg", v).apply() })
                        }
                    }
                    Text("Open a chat to see the change.", Modifier.padding(18.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                }
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


object QuickReplies {
    private val DEF = listOf("On my way", "Can't talk now, I'll call you later", "Thanks!", "Yes", "No", "OK")
    fun get(ctx: Context): List<String> {
        val s = prefs(ctx).getString("quick_replies", null) ?: return DEF
        return s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun set(ctx: Context, text: String) { prefs(ctx).edit().putString("quick_replies", text).apply() }
}

fun chatCategory(c: Conversation): String {
    val a = c.address
    val saved = c.displayName.isNotBlank() && c.displayName != a
    if (saved) return "Personal"
    val t = c.snippet.lowercase()
    val digits = a.filter { it.isDigit() }
    val tollFree = listOf("1800", "1888", "1877", "1866", "1855", "1844", "1833", "800", "888", "877", "866", "855", "844", "833").any { digits.startsWith(it) } && digits.length >= 10
    val auto = listOf("http", "code", "otp", "order", "shipped", "deliver", "payment", "balance", "appointment", "reminder", "receipt", "account", "verify", "package", "tracking", "bill", "reply stop", "% off").any { t.contains(it) }
    val business = a.any { it.isLetter() } || digits.length <= 6 || tollFree || auto
    if (!business) return "Personal"
    val promo = listOf("sale", "% off", "offer", "deal", "discount", "promo", "coupon", "free ", "shop now", "unsubscribe", "reply stop", "stop to", "limited time").any { t.contains(it) }
    return if (promo) "Promos" else "Transactions"
}

fun pinChatShortcut(ctx: Context, c: Conversation) {
    val intent = Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
        .putExtra("thread_id", c.threadId).putExtra("address", c.address).putExtra("name", c.displayName)
    val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "chat_" + c.threadId)
        .setShortLabel(c.displayName.ifBlank { c.address }.take(24))
        .setIcon(androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
        .setIntent(intent).build()
    if (androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    } else Toast.makeText(ctx, "Your launcher can't add shortcuts", Toast.LENGTH_LONG).show()
}

private val LINK_RE = Regex("https?://[^\\s]+")

@Composable
fun MediaDialog(msgs: List<Message>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val media = msgs.filter { it.imageUri != null && (it.mediaMime ?: "image/").startsWith("image/") }
    val links = msgs.flatMap { m -> LINK_RE.findAll(m.body).map { it.value.trimEnd('.', ',', ')') }.toList() }.distinct()
    androidx.compose.ui.window.Dialog(onClose) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Text("Media and links", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                if (media.isEmpty() && links.isEmpty()) Text("Nothing shared in this chat yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (media.isNotEmpty()) {
                    Text("Photos (${media.size})", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(3), Modifier.fillMaxWidth().heightIn(max = 260.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(media.size) { i ->
                            coil.compose.AsyncImage(media[i].imageUri, "photo", Modifier.aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (links.isNotEmpty()) {
                    Text("Links (${links.size})", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(links) { l ->
                            Text(l, Modifier.fillMaxWidth().clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.padding(vertical = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                    }
                }
                TextButton(onClose, Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}


const val WHATS_NEW = """New
- One-tap update with a What's new screen (this one)
- Quick times when scheduling a message: in 1 hour, tonight, tomorrow morning or afternoon
- Quick replies you can edit in Settings
- Chat tabs on the main screen: Personal, Transactions, Promos
- Media and links for each chat
- Add a chat to your home screen
- Large text and display options, bubble shape and chat backgrounds
- Spam protection, app lock, backup and restore, home screen widget, per-chat notification sound
- Search across all messages, undo for delete and archive
Fixed
- Message bubbles now fit the text, times show under the message like Google Messages
- The chat list no longer looks empty when you come back from a chat
- Version and update status always visible on the main screen
- Tapping a notification opens that chat
- Cleaner message box and send button
- Day and time labels like Google Messages (Today, Yesterday, weekday)"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(info: UpdateInfo?, onBack: () -> Unit) {
    val notes = if (info != null && info.notes.isNotBlank() && !info.notes.startsWith("Install app-debug")) info.notes else WHATS_NEW
    Scaffold(topBar = { TopAppBar(title = { Text(if (info != null) "Update to build ${info.build}" else "What's new", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) },
        bottomBar = { if (info != null) Column(Modifier.padding(bottom = 12.dp)) { UpdateCard(info, null) } }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 20.dp)) {
            items(notes.lines().filter { it.isNotBlank() }) { l ->
                val t = l.trim()
                if (t == "New" || t == "Fixed" || t.endsWith(":") || t.startsWith("#")) Text(t.trimStart('#', ' ').trimEnd(':'), Modifier.padding(top = 18.dp, bottom = 6.dp), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                else Text("•  " + t.removePrefix("- ").removePrefix("* "), Modifier.padding(vertical = 4.dp), fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}


object DraftStore {
    private fun p(ctx: Context) = prefs(ctx)
    fun get(ctx: Context, threadId: Long): String = p(ctx).getString("draft_$threadId", "") ?: ""
    fun set(ctx: Context, threadId: Long, text: String) { p(ctx).edit().apply { if (text.isBlank()) remove("draft_$threadId") else putString("draft_$threadId", text) }.apply() }
}

@Composable
fun PhotoViewer(uri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            coil.compose.AsyncImage(uri, "photo", Modifier.fillMaxSize()
                .androidxPointer({ z, p -> scale = (scale * z).coerceIn(1f, 6f); off = if (scale <= 1f) androidx.compose.ui.geometry.Offset.Zero else off + p })
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            Row(Modifier.fillMaxWidth().padding(8.dp).align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.Close, "Close", tint = androidx.compose.ui.graphics.Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton({
                    runCatching {
                        val v = ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "Message_" + System.currentTimeMillis() + ".jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Message") }
                        val out = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                        ctx.contentResolver.openInputStream(uri)!!.use { i -> ctx.contentResolver.openOutputStream(out)!!.use { o -> i.copyTo(o) } }
                        Toast.makeText(ctx, "Saved to Pictures", Toast.LENGTH_SHORT).show()
                    }.onFailure { Toast.makeText(ctx, "Could not save", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.Download, "Save", tint = androidx.compose.ui.graphics.Color.White) }
                IconButton({
                    runCatching {
                        val f = java.io.File(ctx.cacheDir, "share_" + System.currentTimeMillis() + ".jpg")
                        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                        val u = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                    }.onFailure { Toast.makeText(ctx, "Could not share", Toast.LENGTH_SHORT).show() }
                }) { Icon(Icons.Default.Share, "Share", tint = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }
}

fun Modifier.androidxPointer(onGesture: (Float, androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> onGesture(zoom, pan) } }

@Composable
fun ChatDetailsDialog(c: Conversation, msgs: List<Message>, onClose: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    var muted by remember { mutableStateOf(prefs(ctx).getBoolean("muted_${c.threadId}", false)) }
    var media by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    val unsaved = c.displayName == c.address
    if (media) MediaDialog(msgs) { media = false }
    if (confirmDel) AlertDialog(onDismissRequest = { confirmDel = false }, title = { Text("Delete conversation?") }, text = { Text("This deletes every message in this chat from this phone.") },
        confirmButton = { TextButton({ confirmDel = false; onDelete() }) { Text("Delete") } }, dismissButton = { TextButton({ confirmDel = false }) { Text("Cancel") } })
    androidx.compose.ui.window.Dialog(onClose, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(8.dp)) { IconButton(onClose) { Icon(Icons.Default.ArrowBack, "Back") }; Text("Details", Modifier.padding(start = 8.dp, top = 12.dp), fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(c.displayName, c.address, 88.dp, 34.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(c.displayName, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    if (!unsaved) Text(c.address, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                @Composable fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, danger: Boolean = false, click: () -> Unit) {
                    Row(Modifier.fillMaxWidth().clickable { click() }.padding(horizontal = 24.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = if (danger) androidx.compose.ui.graphics.Color(0xFFD93025) else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(20.dp))
                        Text(label, fontSize = 16.sp, color = if (danger) androidx.compose.ui.graphics.Color(0xFFD93025) else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (!c.address.contains(";")) Item(Icons.Default.Phone, "Call") { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.address))) }
                if (unsaved && !c.address.contains(";")) Item(Icons.Default.PersonAdd, "Add to contacts") { runCatching { ctx.startActivity(Intent(Intent.ACTION_INSERT, android.provider.ContactsContract.Contacts.CONTENT_URI).putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, c.address)) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(20.dp))
                    Text("Notifications", Modifier.weight(1f), fontSize = 16.sp)
                    Switch(!muted, { muted = !it; prefs(ctx).edit().putBoolean("muted_${c.threadId}", muted).apply() })
                }
                Item(Icons.Default.PhotoLibrary, "Media and links (" + msgs.count { it.imageUri != null } + " photos)") { media = true }
                Item(Icons.Default.Block, "Block and report spam", true) { BlockedStore.block(ctx, c.address); Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show() }
                Item(Icons.Default.Delete, "Delete conversation", true) { confirmDel = true }
            }
        }
    }
}
