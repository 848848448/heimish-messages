package com.heimish.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

// ━━━━━━━━━━━━━━━━ INSTALLS: each install says hello to the worker; Admin → Users lists them ━━━━━━━━━━━━━━━━

/**
 * Reports this install to the contacts worker (PUT /install/<id>, sync key) at most once a day, and again right after
 * an update: a random install id, this phone's own number(s) when Android lets us read them, model, Android and app
 * version, and the contacts-sync device id. The sync key can only write it; only the admin session can read the list.
 * Never throws: everything is best-effort and a failure is simply retried next time.
 */
object InstallReporter {
    private const val TAG = "InstallReporter"
    private const val PREFS = "heimish_install"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val running = AtomicBoolean(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Random id for this install, created once and kept in prefs. */
    fun installId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString("id", null)?.let { if (it.length == 36) return it }
        val id = java.util.UUID.randomUUID().toString()
        p.edit().putString("id", id).apply()
        return id
    }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * This phone's own number on every active SIM, as far as the system will say (often empty: many carriers never
     * write the number onto the SIM). API 33+: SubscriptionManager.getPhoneNumber; older: SubscriptionInfo.number,
     * then TelephonyManager.line1Number. Each route is tried separately and any refusal is ignored.
     */
    @Suppress("DEPRECATION", "MissingPermission")
    fun ownNumbers(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        fun add(n: String?) {
            val s = n?.trim().orEmpty()
            val d = s.filter { it.isDigit() }
            if (d.length in 7..15) out.add(if (s.startsWith("+")) "+$d" else d)
        }
        val canState = granted(ctx, Manifest.permission.READ_PHONE_STATE)
        val canNumbers = Build.VERSION.SDK_INT >= 26 && granted(ctx, Manifest.permission.READ_PHONE_NUMBERS)
        val defaultSms = runCatching { isDefaultSmsApp(ctx) }.getOrDefault(false)
        val sm = runCatching { ctx.getSystemService(SubscriptionManager::class.java) }.getOrNull()
        val subs = if (sm != null && (canState || canNumbers || defaultSms))
            runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty() else emptyList()
        for (info in subs) {
            if (Build.VERSION.SDK_INT >= 33) runCatching { add(sm!!.getPhoneNumber(info.subscriptionId)) }
            if (Build.VERSION.SDK_INT < 33 || out.isEmpty()) runCatching { add(info.number) }
            runCatching {
                val tm = ctx.getSystemService(TelephonyManager::class.java)
                add(tm?.createForSubscriptionId(info.subscriptionId)?.line1Number)
            }
        }
        if (out.isEmpty()) runCatching { add(ctx.getSystemService(TelephonyManager::class.java)?.line1Number) }
        return out.take(4)
    }

    /** True when it is worth asking (once) for READ_PHONE_NUMBERS: it is in the Phone group, so it is silent when READ_PHONE_STATE is granted. */
    fun shouldAskNumbersPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        if (granted(ctx, Manifest.permission.READ_PHONE_NUMBERS)) return false
        return !prefs(ctx).getBoolean("asked_numbers", false)
    }
    fun markAskedNumbersPermission(ctx: Context) { prefs(ctx).edit().putBoolean("asked_numbers", true).apply() }

    private fun versionName() = "3.6-b${BuildConfig.BUILD_NUMBER}"

    /** Report if the last good report was over a day ago, the app was updated since, or [force]. Runs on its own thread. */
    fun reportIfDue(context: Context, force: Boolean = false) {
        if (BuildConfig.WORKER_URL.isBlank() || BuildConfig.SYNC_TOKEN.isBlank()) return
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val due = force || System.currentTimeMillis() - p.getLong("last_ok", 0L) > DAY_MS ||
            p.getInt("last_vc", 0) != BuildConfig.BUILD_NUMBER
        if (!due) return
        // a failed try waits an hour before the next one (no hammering when offline)
        if (!force && System.currentTimeMillis() - p.getLong("last_try", 0L) < 60L * 60 * 1000 && p.getInt("last_vc", 0) == BuildConfig.BUILD_NUMBER) return
        if (!running.compareAndSet(false, true)) return
        Thread {
            try { report(ctx) } catch (e: Throwable) { Log.w(TAG, "report failed: ${e.message}") } finally { running.set(false) }
        }.start()
    }

    private fun report(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putLong("last_try", System.currentTimeMillis()).apply()
        val id = installId(ctx)
        val phones = runCatching { ownNumbers(ctx) }.getOrDefault(emptyList())
        val body = JSONObject().apply {
            put("installId", id)
            put("phones", JSONArray(phones))
            put("model", Build.MODEL.orEmpty().take(60))
            put("manufacturer", Build.MANUFACTURER.orEmpty().take(40))
            put("androidVersion", Build.VERSION.RELEASE.orEmpty().take(16))
            put("sdk", Build.VERSION.SDK_INT)
            put("versionCode", BuildConfig.BUILD_NUMBER)
            put("versionName", versionName())
            put("device", ContactsSyncService.deviceId(ctx).take(80))
        }.toString().toByteArray()
        val conn = java.net.URL("${BuildConfig.WORKER_URL}/install/$id").openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SYNC_TOKEN}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000; conn.readTimeout = 15000
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            runCatching { (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } }
            if (code == 200) {
                p.edit().putLong("last_ok", System.currentTimeMillis()).putInt("last_vc", BuildConfig.BUILD_NUMBER).apply()
            } else Log.w(TAG, "install report HTTP $code")
        } finally { conn.disconnect() }
    }

    /** After the numbers permission was granted: send the number now instead of tomorrow. */
    fun numbersPermissionChanged(ctx: Context) = reportIfDue(ctx, force = true)
}

/** One install as the worker lists it. */
data class AppInstall(
    val id: String, val phones: List<String>, val model: String, val manufacturer: String, val androidVersion: String,
    val versionCode: Int, val versionName: String, val firstSeen: Long, val lastSeen: Long,
)

data class InstallsList(val people: Int, val installs: Int, val active7d: Int, val noNumber: Int,
                        val perVersion: List<Pair<String, Int>>, val items: List<AppInstall>)

sealed class UsersLoad {
    data object Idle : UsersLoad()
    data class Done(val list: InstallsList, val at: Long) : UsersLoad()
    data class Failed(val httpCode: Int?, val message: String) : UsersLoad()
}

/** Keeps the Users list while you jump into a chat and come back. */
internal object AdminUsersMemory { var q = ""; var last: UsersLoad = UsersLoad.Idle; var showAll = false }

internal object AdminUsers {
    /** The worker's ISO time ("2026-10-21T18:58:15.580Z"); java.time needs API 26, so SimpleDateFormat. */
    private fun iso(s: String): Long {
        if (s.isBlank()) return 0L
        for (f in arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            val fmt = java.text.SimpleDateFormat(f, java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            runCatching { fmt.parse(s)?.time }.getOrNull()?.let { return it }
        }
        return 0L
    }

    /** Blocking; call on Dispatchers.IO. Throws CloudSearchError with the HTTP status when the worker refuses. */
    fun fetch(ctx: Context): InstallsList {
        val session = AdminAuth.session(ctx) ?: throw CloudSearchError(401, "Admin is locked")
        val conn = java.net.URL("${BuildConfig.WORKER_URL}/admin/installs").openConnection() as java.net.HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $session")
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw CloudSearchError(code, msg.ifBlank { if (code == 404) "The server doesn't have the users list yet" else "Request failed" })
            }
            val j = JSONObject(body)
            val t = j.optJSONObject("totals") ?: JSONObject()
            val arr = j.optJSONArray("installs") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ph = o.optJSONArray("phones")
                AppInstall(
                    o.optString("id"), (0 until (ph?.length() ?: 0)).map { ph!!.optString(it) }.filter { it.isNotBlank() },
                    o.optString("model"), o.optString("manufacturer"), o.optString("androidVersion"),
                    o.optInt("versionCode"), o.optString("versionName"), iso(o.optString("firstSeen")), iso(o.optString("lastSeen")),
                )
            }.sortedByDescending { it.lastSeen }
            val pv = j.optJSONArray("perVersion") ?: JSONArray()
            val versions = (0 until pv.length()).mapNotNull { i -> pv.optJSONObject(i)?.let { it.optString("version") to it.optInt("count") } }
            return InstallsList(t.optInt("people"), t.optInt("installs", items.size), t.optInt("active7d"), t.optInt("noNumber"), versions, items)
        } finally { conn.disconnect() }
    }

    /** Search by number digits, contact name, model, maker, Android or app version. */
    fun filter(items: List<AppInstall>, q: String, nameOf: (String) -> String?): List<AppInstall> {
        val t = q.trim().lowercase()
        if (t.isEmpty()) return items
        val qd = t.filter { it.isDigit() }
        return items.filter { i ->
            val hay = listOf(i.model, i.manufacturer, "android " + i.androidVersion, versionLabel(i)).joinToString(" ").lowercase()
            hay.contains(t) ||
                (qd.length >= 3 && i.phones.any { it.filter { c -> c.isDigit() }.contains(qd) }) ||
                i.phones.any { n -> nameOf(n)?.lowercase()?.contains(t) == true }
        }
    }

    fun versionLabel(i: AppInstall): String = when {
        i.versionName.isNotBlank() -> "v" + i.versionName.removePrefix("v")
        i.versionCode > 0 -> "b${i.versionCode}"
        else -> ""
    }

    fun modelLabel(i: AppInstall): String {
        val m = i.model.trim(); val mk = i.manufacturer.trim()
        val base = if (mk.isNotEmpty() && !m.lowercase().contains(mk.lowercase())) "${mk.replaceFirstChar { it.uppercase() }} $m" else m
        return base.trim()
    }

    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= 0) return ""
        val m = (now - t) / 60000
        return when {
            m < 1 -> "Just now"
            m < 60 -> "$m min ago"
            m < 24 * 60 -> "${m / 60} h ago"
            m < 48 * 60 -> "Yesterday"
            m < 8 * 24 * 60 -> "${m / (24 * 60)} days ago"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(t))
        }
    }
}

private const val U_LRI = "\u2066"; private const val U_PDI = "\u2069"

/** Call straight away (ACTION_CALL). CALL_PHONE is asked the first time; without it, the dialer opens with the number. */
@Composable
internal fun rememberDirectCall(): (String) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val n = pending; pending = null
        if (n != null) { if (ok) directCall(ctx, n) else CloudContacts.call(ctx, n) }
    }
    return remember(ctx, launcher) {
        { n: String ->
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) directCall(ctx, n)
            else { pending = n; runCatching { launcher.launch(Manifest.permission.CALL_PHONE) }.onFailure { pending = null; CloudContacts.call(ctx, n) } }
        }
    }
}

internal fun directCall(ctx: Context, number: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(CloudContacts.dialable(number)))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { CloudContacts.call(ctx, number) }
}

/** People · Installations · Active 7 days. */
@Composable
internal fun UsersTotals(list: InstallsList?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        UsersStat(list?.people, "People")
        UsersStat(list?.installs, "Installations")
        UsersStat(list?.active7d, "Active 7 days")
    }
}

@Composable
private fun RowScope.UsersStat(n: Int?, label: String) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(SettCardBg).padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(n?.let { "%,d".format(it) } ?: "–", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Brand, maxLines = 1)
        Text(label, fontSize = 12.sp, color = TextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** One install: name (when saved in contacts) or number · number LTR · model · version · last seen; Call / Copy / Message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserRow(i: AppInstall, first: Boolean, last: Boolean, onOpen: (String) -> Unit, onCopied: (String) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val num = i.phones.firstOrNull().orEmpty()
    val pretty = remember(num) { if (num.isBlank()) "" else CloudContacts.pretty(num) }
    val name = if (num.isBlank()) "" else rememberContactName(num).let { if (it == num || it.isBlank()) "" else it }
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 4.dp, topEnd = if (first) 20.dp else 4.dp, bottomStart = if (last) 20.dp else 4.dp, bottomEnd = if (last) 20.dp else 4.dp)
    val call = rememberDirectCall()
    val numStyle = TextStyle(textDirection = TextDirection.Ltr, textAlign = if (ltr) TextAlign.Left else TextAlign.Right)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape).background(SettCardBg)
            .then(if (num.isNotBlank()) Modifier.combinedClickable(
                onClick = { onOpen(num) },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); CloudContacts.copy(ctx, "Number", pretty); onCopied(pretty) },
            ) else Modifier)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name.ifBlank { num.ifBlank { i.model } }, num, 42.dp, 18.sp, tapToView = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (name.isNotBlank()) {
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = listTextStyle(), modifier = Modifier.fillMaxWidth())
                Text(U_LRI + pretty + U_PDI, fontSize = 14.sp, color = TextSecond, maxLines = 1, style = numStyle, modifier = Modifier.fillMaxWidth())
            } else {
                Text(if (pretty.isNotBlank()) U_LRI + pretty + U_PDI else "No number", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (pretty.isNotBlank()) TextPrimary else TextSecond, maxLines = 1, style = numStyle, modifier = Modifier.fillMaxWidth())
            }
            if (i.phones.size > 1) Text(U_LRI + i.phones.drop(1).joinToString(", ") { CloudContacts.pretty(it) } + U_PDI,
                fontSize = 13.sp, color = TextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis, style = numStyle, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(2.dp))
            val meta = listOf(AdminUsers.modelLabel(i), AdminUsers.versionLabel(i), AdminUsers.ago(i.lastSeen)).filter { it.isNotBlank() }.joinToString(" · ")
            Text(meta, fontSize = 13.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle(), modifier = Modifier.fillMaxWidth())
        }
        if (num.isNotBlank()) {
            IconButton({ call(num) }, Modifier.size(40.dp)) { Icon(Ms.Call, "Call", tint = TextSecond, modifier = Modifier.size(21.dp)) }
            IconButton({ CloudContacts.copy(ctx, "Number", pretty); onCopied(pretty) }, Modifier.size(40.dp)) { Icon(Ms.ContentCopy, "Copy number", tint = TextSecond, modifier = Modifier.size(20.dp)) }
            Box(Modifier.padding(horizontal = 4.dp).size(40.dp).clip(CircleShape).background(Tonal).combinedClickable(onClick = { onOpen(num) }), contentAlignment = Alignment.Center) {
                Icon(Ms.ChatFill, "Message", tint = OnTonal, modifier = Modifier.size(20.dp))
            }
        }
    }
}
