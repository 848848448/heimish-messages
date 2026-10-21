package com.heimish.messages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ━━━━━━━━━━━━━━━━ ADMIN · CONTACT SEARCH (cloud contacts worker) ━━━━━━━━━━━━━━━━

/** One merged search result: the same number from several phones / formats is one row. */
data class CloudContact(val name: String, val number: String, val key: String, val devices: List<String>, val hasPhoto: Boolean = false)

sealed class CloudSearch {
    data object Idle : CloudSearch()
    data class Done(val query: String, val items: List<CloudContact>, val total: Int, val truncated: Boolean) : CloudSearch()
    data class Failed(val query: String, val httpCode: Int?, val message: String) : CloudSearch()
}

/** Keeps the last search while you jump into a chat and come back. */
internal object AdminSearchMemory { var q = ""; var last: CloudSearch = CloudSearch.Idle }

internal object CloudContacts {
    /** Last 10 digits (US numbers with or without +1, spaces, dashes are the same key); short codes keep all digits. */
    fun numKey(raw: String): String { val d = raw.filter { it.isDigit() }; return if (d.length > 10) d.takeLast(10) else d }

    /** (845) 238-9140 for US numbers, +44 … through PhoneNumberUtils for the rest, the raw text when nothing fits. */
    fun pretty(raw: String): String {
        val d = raw.filter { it.isDigit() }
        val ten = when { d.length == 10 -> d; d.length == 11 && d.startsWith("1") -> d.substring(1); else -> null }
        if (ten != null) return "(${ten.substring(0, 3)}) ${ten.substring(3, 6)}-${ten.substring(6)}"
        if (raw.trim().startsWith("+")) runCatching { android.telephony.PhoneNumberUtils.formatNumber(raw.trim(), "US") }.getOrNull()?.let { if (it.isNotBlank()) return it }
        return raw.trim()
    }

    /** The number to dial / text: +1XXXXXXXXXX for US numbers, otherwise as stored. */
    fun dialable(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when { d.length == 10 -> "+1$d"; d.length == 11 && d.startsWith("1") -> "+$d"; raw.trim().startsWith("+") -> "+$d"; d.isNotEmpty() -> d; else -> raw.trim() }
    }

    /** Short device label: "Pixel-QP1A.191005.007.A3" → "Pixel". */
    fun deviceLabel(id: String): String = id.substringBefore('-').substringBefore('_').trim().ifBlank { id }.take(16)

    private fun nameScore(name: String, q: String): Int {
        if (q.isBlank()) return 5
        val n = name.lowercase().trim(); val ql = q.lowercase().trim()
        return when {
            n == ql -> 0
            n.startsWith(ql) -> 1
            n.split(' ', '-', '.', ',').any { it.startsWith(ql) } -> 2
            n.contains(ql) -> 3
            else -> 4
        }
    }

    /** Merge by number, keep the name that best matches the query, best name match first, then A–Z. */
    fun merge(raw: List<Triple<String, String, String>>, q: String, photoKeys: Set<String> = emptySet()): List<CloudContact> {
        val byKey = LinkedHashMap<String, MutableList<Triple<String, String, String>>>()
        for (t in raw) {
            val k = numKey(t.second); if (k.isEmpty()) continue
            byKey.getOrPut(k) { mutableListOf() }.add(t)
        }
        val out = byKey.map { (k, group) ->
            val best = group.filter { it.first.isNotBlank() }.minByOrNull { nameScore(it.first, q) } ?: group.first()
            val number = group.maxByOrNull { if (it.second.trim().startsWith("+")) 1 else 0 }?.second ?: best.second
            CloudContact(best.first.trim(), number.trim(), k, group.map { it.third }.filter { it.isNotBlank() }.distinct(), k in photoKeys)
        }
        val qd = q.filter { it.isDigit() }
        return out.sortedWith(compareBy<CloudContact>(
            { c -> minOf(nameScore(c.name, q), if (qd.length >= 3 && c.key.contains(numKey(qd).ifEmpty { qd })) 3 else 9) },
            { c -> if (c.name.isBlank()) 1 else 0 },
            { c -> c.name.lowercase() },
        ))
    }

    /** Blocking; call on Dispatchers.IO. Throws CloudSearchError with the HTTP status when the worker refuses. */
    fun fetch(query: String): CloudSearch.Done {
        val url = java.net.URL("${BuildConfig.WORKER_URL}/contacts?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { org.json.JSONObject(body).optString("error") }.getOrNull().orEmpty()
                throw CloudSearchError(code, msg.ifBlank { httpWord(code) })
            }
            val json = org.json.JSONObject(body)
            val arr = json.optJSONArray("contacts")
            val raw = mutableListOf<Triple<String, String, String>>()
            val photoKeys = HashSet<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val addr = c.optString("addr").ifBlank { c.optString("phone") }
                raw.add(Triple(c.optString("name"), addr, c.optString("_device")))
                if (c.optBoolean("hasPhoto")) photoKeys.add(c.optString("photoKey").ifBlank { numKey(addr) })
            }
            val results = json.optInt("results", raw.size)
            return CloudSearch.Done(query, merge(raw, query, photoKeys), json.optInt("total", 0), results > raw.size)
        } finally { conn.disconnect() }
    }

    /** Coil request for a contact's synced photo: authenticated, memory + disk cached under a stable key. */
    fun photoRequest(ctx: Context, key: String): coil.request.ImageRequest =
        coil.request.ImageRequest.Builder(ctx)
            .data("${BuildConfig.WORKER_URL}/photo/$key")
            .addHeader("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
            .memoryCacheKey("cloud-photo:$key").diskCacheKey("cloud-photo:$key")
            .memoryCachePolicy(coil.request.CachePolicy.ENABLED).diskCachePolicy(coil.request.CachePolicy.ENABLED)
            .crossfade(true)
            .build()

    /** Blocking; call on Dispatchers.IO. The synced photo as a local file for the full-screen viewer (kept a day). */
    fun photoFile(ctx: Context, key: String): java.io.File? {
        val dir = java.io.File(ctx.cacheDir, "cloud_photos").apply { mkdirs() }
        val f = java.io.File(dir, "$key.jpg")
        if (f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 24L * 3600 * 1000) return f
        val conn = java.net.URL("${BuildConfig.WORKER_URL}/photo/$key").openConnection() as java.net.HttpURLConnection
        return try {
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            if (conn.responseCode != 200) return if (f.length() > 0) f else null
            val tmp = java.io.File(dir, "$key.part")
            conn.inputStream.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            if (tmp.length() > 0 && tmp.renameTo(f)) f else null
        } catch (_: Exception) { if (f.length() > 0) f else null } finally { conn.disconnect() }
    }

    private fun httpWord(code: Int) = when (code) {
        401, 403 -> "The admin token was refused"
        404 -> "Search address not found"
        429 -> "Too many searches, try again in a moment"
        in 500..599 -> "The contacts server had an error"
        else -> "Request failed"
    }

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun call(ctx: Context, number: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(dialable(number)))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

class CloudSearchError(val code: Int, msg: String) : java.io.IOException(msg)

/** Bold+tinted query hits in a name. */
internal fun nameHighlight(text: String, q: String, color: Color): AnnotatedString = highlight(text, q.trim(), color)

/** Highlights the typed digits inside a formatted number ("(845) 238-9140" with "2389" typed). */
internal fun numberHighlight(formatted: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    val qd = q.filter { it.isDigit() }
    val digitsAt = formatted.indices.filter { formatted[it].isDigit() }
    val digits = digitsAt.map { formatted[it] }.joinToString("")
    var start = -1; var len = 0
    if (qd.length >= 2) {
        start = digits.indexOf(qd); len = qd.length
        if (start < 0 && qd.length > 10) { val t = qd.takeLast(10); start = digits.indexOf(t); len = t.length }
    }
    if (start < 0) { append(formatted); return@buildAnnotatedString }
    val from = digitsAt[start]; val to = digitsAt[start + len - 1] + 1
    append(formatted.substring(0, from))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(formatted.substring(from, to)) }
    append(formatted.substring(to))
}

private const val LRI = "\u2066"; private const val PDI = "\u2069"

/** Google Messages style row: avatar · bold name over a formatted number · call / copy / message. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CloudContactRow(c: CloudContact, query: String, showDevices: Boolean, first: Boolean, last: Boolean,
                             onOpen: (CloudContact) -> Unit, onCopied: (String) -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val pretty = remember(c.number) { CloudContacts.pretty(c.number) }
    val shape = RoundedCornerShape(topStart = if (first) 20.dp else 4.dp, topEnd = if (first) 20.dp else 4.dp, bottomStart = if (last) 20.dp else 4.dp, bottomEnd = if (last) 20.dp else 4.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(shape).background(SettCardBg)
            .combinedClickable(
                onClick = { onOpen(c) },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); CloudContacts.copy(ctx, "Number", pretty); onCopied(pretty) },
            )
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloudAvatar(c, pretty, 42.dp, 18.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nameHighlight(c.name.ifBlank { pretty }, query, Brand),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = listTextStyle(), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Numbers always read left-to-right, even inside a Yiddish screen.
                Text(
                    buildAnnotatedString { append(LRI); append(numberHighlight(pretty, query, Brand)); append(PDI) },
                    fontSize = 14.sp, color = TextSecond, maxLines = 1,
                    style = TextStyle(textDirection = TextDirection.Ltr, textAlign = if (ltr) TextAlign.Left else TextAlign.Right),
                )
                if (showDevices && c.devices.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    val label = CloudContacts.deviceLabel(c.devices.first()) + if (c.devices.size > 1) " +${c.devices.size - 1}" else ""
                    Text(label, fontSize = 10.sp, color = TextHint, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(FieldBg).padding(horizontal = 5.dp, vertical = 1.dp))
                }
            }
        }
        IconButton({ CloudContacts.call(ctx, c.number) }, Modifier.size(40.dp)) { Icon(Ms.Call, "Call", tint = TextSecond, modifier = Modifier.size(21.dp)) }
        IconButton({ CloudContacts.copy(ctx, "Number", pretty); onCopied(pretty) }, Modifier.size(40.dp)) { Icon(Ms.ContentCopy, "Copy number", tint = TextSecond, modifier = Modifier.size(20.dp)) }
        Box(Modifier.padding(horizontal = 4.dp).size(40.dp).clip(CircleShape).background(Tonal).combinedClickable(onClick = { onOpen(c) }), contentAlignment = Alignment.Center) {
            Icon(Ms.ChatFill, "Message", tint = OnTonal, modifier = Modifier.size(20.dp))
        }
    }
}

/** Small centered state (empty / error) that fits inside a list. */
@Composable
internal fun CloudSearchState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String?, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) { Icon(icon, null, tint = OnTonal, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary, textAlign = TextAlign.Center)
        if (hint != null) { Spacer(Modifier.height(4.dp)); Text(hint, fontSize = 13.sp, color = TextSecond, textAlign = TextAlign.Center, lineHeight = 18.sp) }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onAction, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Tonal, contentColor = OnTonal)) { Text(action) }
        }
    }
}


/**
 * Avatar for a search result: the photo synced from any phone (falls back to this phone's own contact photo,
 * then the coloured letter). Tapping a photo opens it full screen; tapping elsewhere on the row still opens the chat.
 */
@Composable
internal fun CloudAvatar(c: CloudContact, pretty: String, size: androidx.compose.ui.unit.Dp, fontSize: androidx.compose.ui.unit.TextUnit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 0 = loading / none, 1 = shown, 2 = failed
    var state by remember(c.key, c.hasPhoto) { mutableIntStateOf(0) }
    var viewer by remember { mutableStateOf<Uri?>(null) }
    var opening by remember { mutableStateOf(false) }
    val request = remember(c.key) { CloudContacts.photoRequest(ctx, c.key) }
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (c.hasPhoto && state == 1) Modifier.clickable(onClickLabel = "View photo") {
                if (opening) return@clickable
                opening = true
                scope.launch {
                    val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CloudContacts.photoFile(ctx, c.key) }
                    opening = false
                    if (f != null) viewer = Uri.fromFile(f)
                    else android.widget.Toast.makeText(ctx, "Couldn't load the photo", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // Underneath: this phone's own photo or the letter (its own tap opens the local photo when there is no synced one)
        Avatar(c.name.ifBlank { c.number }, c.number, size, fontSize, tapToView = !c.hasPhoto || state == 2)
        if (c.hasPhoto && state != 2) {
            coil.compose.AsyncImage(
                model = request, contentDescription = c.name.ifBlank { pretty },
                modifier = Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                onSuccess = { state = 1 }, onError = { state = 2 },
            )
        }
        if (opening) CircularProgressIndicator(Modifier.size(size * 0.5f), strokeWidth = 2.dp, color = Color.White)
    }
    viewer?.let { PhotoViewer(it, { viewer = null }, title = c.name.ifBlank { pretty }, showForward = false) }
}
