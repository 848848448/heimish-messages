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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(JSONObject().put("app", "message").put("version", 2).put("sms", arr).put("settings", settingsToJson(ctx)).toString().toByteArray()) }
        return arr.length()
    }
    fun restore(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return 0
        val root = JSONObject(text)
        runCatching { root.optJSONObject("settings")?.let { settingsFromJson(ctx, it) } }
        val arr = root.getJSONArray("sms")
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
        Icon(Ms.LockFill, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
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
        // Blocked chats show here too (they were hidden everywhere with no way to unblock them)
        list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }.filter { it.threadId in ids || BlockedStore.isBlocked(ctx, it.address) }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Spam & blocked", fontSize = 22.sp) }, navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } }) }) { pad ->
        if (list.isEmpty()) EmptyState(Ms.Shield, "No spam or blocked chats", "Chats you block or mark as spam show up here", Modifier.padding(pad))
        else LazyColumn(Modifier.padding(pad)) {
            items(list, key = { it.threadId }) { c ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(c) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.displayName, c.address, 44.dp, 17.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
                        Text(c.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (BlockedStore.isBlocked(ctx, c.address)) TextButton({ BlockedStore.unblock(ctx, c.address); SpamStore.remove(ctx, c.threadId); tick++ }) { Text("Unblock") }
                    else TextButton({ SpamStore.remove(ctx, c.threadId); tick++ }) { Text("Not spam") }
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
    val title = when (key) { "spam" -> "Spam protection"; "lock" -> "App lock"; "otp" -> "Verification codes"; "backup" -> "Backup & restore"; "quick" -> "Quick replies"; "access" -> "App text size"; "style" -> "Bubble shape & background"; "dnd" -> "Quiet hours"; "sim" -> "SIM card & group texts"; else -> "Settings" }
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
        Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface); Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.width(12.dp)); Switch(checked = on, onCheckedChange = onChange)
        }
    }
    Scaffold(containerColor = Bg, topBar = { LargeTopAppBar(colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Bg, scrolledContainerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
        title = { Text(title) }, navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } }) }) { pad ->
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
                "dnd" -> {
                    var on by remember { mutableStateOf(p.getBoolean("dnd_on", false)) }
                    var st by remember { mutableStateOf(p.getInt("dnd_start", 22 * 60)) }
                    var en by remember { mutableStateOf(p.getInt("dnd_end", 7 * 60)) }
                    SwitchRow("Quiet hours", "Messages arrive silently during these hours", on) { on = it; p.edit().putBoolean("dnd_on", it).apply() }
                    Row(Modifier.fillMaxWidth().clickable { android.app.TimePickerDialog(ctx, { _, h, m -> st = h * 60 + m; p.edit().putInt("dnd_start", st).apply() }, st / 60, st % 60, false).show() }.padding(horizontal = 18.dp, vertical = 14.dp)) { Text("Starts", Modifier.weight(1f), fontSize = 16.sp); Text(Dnd.fmt(st), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp) }
                    Row(Modifier.fillMaxWidth().clickable { android.app.TimePickerDialog(ctx, { _, h, m -> en = h * 60 + m; p.edit().putInt("dnd_end", en).apply() }, en / 60, en % 60, false).show() }.padding(horizontal = 18.dp, vertical = 14.dp)) { Text("Ends", Modifier.weight(1f), fontSize = 16.sp); Text(Dnd.fmt(en), color = MaterialTheme.colorScheme.primary, fontSize = 16.sp) }
                }
                "sim" -> {
                    var sub by remember { mutableStateOf(p.getInt("sim_sub", -1)) }
                    var grp by remember { mutableStateOf(p.getBoolean("group_mms_send", true)) }
                    var permOk by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
                    val phoneL = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permOk = it }
                    SwitchRow("Group texts as MMS", "Send one group message to everyone instead of separate texts", grp) { grp = it; p.edit().putBoolean("group_mms_send", it).apply() }
                    Text("Send messages with", Modifier.padding(18.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().clickable { sub = -1; p.edit().putInt("sim_sub", -1).apply() }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(sub == -1, { sub = -1; p.edit().putInt("sim_sub", -1).apply() }); Spacer(Modifier.width(12.dp)); Text("Phone default SIM", fontSize = 16.sp) }
                    if (!permOk) Button({ phoneL.launch(android.Manifest.permission.READ_PHONE_STATE) }, Modifier.padding(horizontal = 18.dp)) { Text("Show my SIM cards") }
                    else {
                        val subs = remember { runCatching { (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager).activeSubscriptionInfoList ?: emptyList() }.getOrElse { emptyList() } }
                        subs.forEach { si ->
                            Row(Modifier.fillMaxWidth().clickable { sub = si.subscriptionId; p.edit().putInt("sim_sub", si.subscriptionId).apply() }.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(sub == si.subscriptionId, { sub = si.subscriptionId; p.edit().putInt("sim_sub", si.subscriptionId).apply() }); Spacer(Modifier.width(12.dp)); Text("SIM " + (si.simSlotIndex + 1) + " · " + si.displayName, fontSize = 16.sp) }
                        }
                        if (subs.isEmpty()) Text("No SIM cards found", Modifier.padding(18.dp), fontSize = 14.sp)
                    }
                }
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
                    var cr by remember { mutableStateOf(p.getInt("bubble_corner", 24)) }
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


const val WHATS_NEW = """New
- Pin a message in a chat, it shows at the top
- Labels for chats, with a tab for each label
- Unknown senders tab
- Blocked words, texts with them go to Spam
- Auto-reply when busy
- Edit a scheduled message
- Backup now also saves your settings
- Chat menu is shorter, with a More item"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(info: UpdateInfo?, onBack: () -> Unit) {
    val notes = if (info != null && info.notes.isNotBlank() && !info.notes.startsWith("Install app-debug")) info.notes else WHATS_NEW
    Scaffold(topBar = { TopAppBar(title = { Text(if (info != null) "Update to build ${info.build}" else "What's new", fontWeight = FontWeight.SemiBold) }, navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } }) },
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

object Dnd {
    fun active(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (!p.getBoolean("dnd_on", false)) return false
        val s = p.getInt("dnd_start", 22 * 60); val en = p.getInt("dnd_end", 7 * 60)
        val c = java.util.Calendar.getInstance()
        val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        return if (s <= en) now in s until en else (now >= s || now < en)
    }
    fun fmt(m: Int): String = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, m / 60); set(java.util.Calendar.MINUTE, m % 60) }.time)
}


object Mute {
    fun isMuted(ctx: Context, tid: Long): Boolean { val p = prefs(ctx); return p.getBoolean("muted_$tid", false) || p.getLong("muted_until_$tid", 0L) > System.currentTimeMillis() }
    fun setFor(ctx: Context, tid: Long, ms: Long) { prefs(ctx).edit().apply { if (ms <= 0) { putBoolean("muted_$tid", true); remove("muted_until_$tid") } else putLong("muted_until_$tid", System.currentTimeMillis() + ms) }.apply() }
    fun clear(ctx: Context, tid: Long) { prefs(ctx).edit().remove("muted_$tid").remove("muted_until_$tid").apply() }
}

fun exportChat(ctx: Context, c: Conversation, msgs: List<Message>) {
    runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        val sb = StringBuilder("Chat with ").append(c.displayName).append(" (").append(c.address).append(")\n\n")
        msgs.forEach { m -> sb.append('[').append(fmt.format(java.util.Date(m.date))).append("] ").append(if (m.incoming) c.displayName else "Me").append(": ").append(m.body.ifBlank { "(media)" }).append('\n') }
        val f = java.io.File(ctx.cacheDir, "chat_" + c.threadId + ".txt"); f.writeText(sb.toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export chat"))
    }.onFailure { Toast.makeText(ctx, "Could not export", Toast.LENGTH_SHORT).show() }
}


class RemindReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Notifications.showSimple(ctx, "Reminder: " + (i.getStringExtra("name") ?: "chat"), "You asked to be reminded about this chat", 7000 + (i.getLongExtra("tid", 0L) % 1000).toInt())
    }
    companion object {
        fun set(ctx: Context, c: Conversation, at: Long) {
            val i = Intent(ctx, RemindReceiver::class.java).putExtra("name", c.displayName).putExtra("tid", c.threadId)
            val pi = PendingIntent.getBroadcast(ctx, (9000 + c.threadId % 100000).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

fun isLinkMsg(m: Message) = m.body.contains("http://", true) || m.body.contains("https://", true) || m.body.contains("www.", true)


object KeywordStore {
    fun all(ctx: Context): List<String> = (prefs(ctx).getString("blocked_keywords", "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    private fun save(ctx: Context, l: List<String>) { prefs(ctx).edit().putString("blocked_keywords", l.joinToString("\n")).apply() }
    fun add(ctx: Context, k: String) { val t = k.trim(); if (t.isNotEmpty() && all(ctx).none { it.equals(t, true) }) save(ctx, all(ctx) + t) }
    fun remove(ctx: Context, k: String) { save(ctx, all(ctx).filter { it != k }) }
    fun matches(ctx: Context, body: String) = all(ctx).any { body.contains(it, true) }
}

object LabelStore {
    fun get(ctx: Context, t: Long): String? = prefs(ctx).getString("label_$t", null)?.takeIf { it.isNotBlank() }
    fun set(ctx: Context, t: Long, l: String) {
        val p = prefs(ctx); val ids = (p.getStringSet("label_threads", emptySet()) ?: emptySet()).toMutableSet()
        if (l.isBlank()) { ids.remove(t.toString()); p.edit().remove("label_$t").putStringSet("label_threads", ids).apply() }
        else { ids.add(t.toString()); p.edit().putString("label_$t", l.trim()).putStringSet("label_threads", ids).apply() }
    }
    fun allLabels(ctx: Context): List<String> = (prefs(ctx).getStringSet("label_threads", emptySet()) ?: emptySet()).mapNotNull { it.toLongOrNull()?.let { t -> get(ctx, t) } }.distinct().sorted()
}

object MsgPinStore {
    fun has(ctx: Context, key: String) = (prefs(ctx).getStringSet("pinned_msgs", emptySet()) ?: emptySet()).contains(key)
    fun toggle(ctx: Context, key: String) {
        val s = (prefs(ctx).getStringSet("pinned_msgs", emptySet()) ?: emptySet()).toMutableSet()
        if (!s.add(key)) s.remove(key)
        prefs(ctx).edit().putStringSet("pinned_msgs", s).apply()
    }
}

object AutoReply {
    fun maybe(ctx: Context, address: String) {
        runCatching {
            val p = prefs(ctx)
            if (!p.getBoolean("auto_reply_on", false)) return
            val text = p.getString("auto_reply_text", "") ?: ""
            if (text.isBlank()) return
            val digits = address.filter { it.isDigit() }
            if (address.any { it.isLetter() } || digits.length < 7) return
            val k = "ar_last_$digits"; val now = System.currentTimeMillis()
            if (now - p.getLong(k, 0L) < 6 * 3600_000L) return
            p.edit().putLong(k, now).apply()
            SmsRepository.sendSms(ctx, address, text)
        }
    }
}

@Composable
fun KeywordEditor() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(KeywordStore.all(ctx)) }
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Blocked words", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text("Texts from unknown numbers containing these words go to Spam silently", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a word") })
            TextButton({ KeywordStore.add(ctx, input); input = ""; list = KeywordStore.all(ctx) }) { Text("Add") }
        }
        list.forEach { k -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(k, Modifier.weight(1f)); TextButton({ KeywordStore.remove(ctx, k); list = KeywordStore.all(ctx) }) { Text("Remove") } } }
    }
}

@Composable
fun AutoReplyEditor() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(prefs(ctx).getBoolean("auto_reply_on", false)) }
    var text by remember { mutableStateOf(prefs(ctx).getString("auto_reply_text", "I'm busy right now, I'll get back to you soon.") ?: "") }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Auto-reply when busy", fontSize = 16.sp); Text("Answers a new text once per sender every 6 hours", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(on, { on = it; prefs(ctx).edit().putBoolean("auto_reply_on", it).putString("auto_reply_text", text).apply() })
        }
        OutlinedTextField(text, { text = it; prefs(ctx).edit().putString("auto_reply_text", it).apply() }, Modifier.fillMaxWidth(), label = { Text("Reply text") })
    }
}

fun settingsToJson(ctx: Context): JSONObject {
    val o = JSONObject()
    for ((k, v) in prefs(ctx).all) {
        if (k.startsWith("ar_last_") || k.startsWith("draft_")) continue
        when (v) {
            is Boolean -> o.put(k, JSONObject().put("t", "b").put("v", v))
            is Int -> o.put(k, JSONObject().put("t", "i").put("v", v))
            is Long -> o.put(k, JSONObject().put("t", "l").put("v", v))
            is Float -> o.put(k, JSONObject().put("t", "f").put("v", v.toDouble()))
            is String -> o.put(k, JSONObject().put("t", "s").put("v", v))
            is Set<*> -> o.put(k, JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() })))
        }
    }
    return o
}

fun settingsFromJson(ctx: Context, o: JSONObject) {
    val ed = prefs(ctx).edit()
    o.keys().forEach { k ->
        val x = o.getJSONObject(k)
        when (x.getString("t")) {
            "b" -> ed.putBoolean(k, x.getBoolean("v")); "i" -> ed.putInt(k, x.getInt("v")); "l" -> ed.putLong(k, x.getLong("v"))
            "f" -> ed.putFloat(k, x.getDouble("v").toFloat()); "s" -> ed.putString(k, x.getString("v"))
            "set" -> { val a = x.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
        }
    }
    ed.apply()
}
