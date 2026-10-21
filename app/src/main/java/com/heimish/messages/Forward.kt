package com.heimish.messages

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ━━━━━━━━━━━━━━━━ FORWARD (Google Messages "Forward message") ━━━━━━━━━━━━━━━━
// Long-press → Forward (or Forward in the selection bar) → the "Forward message" picker → the chosen chat opens with the
// text and every attachment waiting in the compose bar → Send → you are back in the chat you came from, at the same spot,
// with "Forwarded to …". Backing out of the picker or of the chosen chat also lands back there.

/** One attachment being forwarded, copied into the app so it stays readable after the original message is gone. */
data class FwdMedia(val uri: Uri, val mime: String)

/** What is being forwarded. [target] is the chat it is staged into (set when a chat is picked). */
data class ForwardPayload(val text: String, val media: List<FwdMedia>, val fromThread: Long, val count: Int, val target: Long = -1L)

object ForwardState {
    var payload: ForwardPayload? = null
    /** "Forwarded to X" for the chat you came from: (thread id, text). Shown once there. */
    var doneNote by mutableStateOf<Pair<Long, String>?>(null)
    /** Opens the picker (set by the app's navigation). */
    var open: (() -> Unit)? = null
    private val mimes = HashMap<String, String>()
    fun mimeOf(u: Uri?): String? = u?.let { mimes[it.toString()] }
    fun remember(m: List<FwdMedia>) { m.forEach { mimes[it.uri.toString()] = it.mime } }
    fun clear() { payload = null }
}

object Forwarder {
    private const val MAX_MEDIA = 10
    /** Sends started from the picker keep going even though the picker closes right away. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(ctx: Context): File = File(ctx.cacheDir, "forward").apply {
        mkdirs()
        // forwarded copies older than two days are no longer needed
        listFiles()?.forEach { f -> if (System.currentTimeMillis() - f.lastModified() > 2 * 86_400_000L) f.delete() }
    }

    private fun extFor(mime: String): String {
        val m = mime.lowercase().substringBefore(";").trim()
        MimeTypeMap.getSingleton().getExtensionFromMimeType(m)?.let { return it }
        return when {
            m == "audio/mp4" || m == "audio/m4a" || m == "audio/x-m4a" || m == "audio/aac" -> "m4a"
            m.startsWith("audio/amr") -> "amr"
            m.startsWith("audio/") -> "ogg"
            m.startsWith("video/") -> "mp4"
            m.startsWith("image/") -> "jpg"
            m.contains("vcard") -> "vcf"
            else -> "bin"
        }
    }

    /** Copies one attachment into the app's forward folder; null if it can't be read. */
    fun copyLocal(ctx: Context, uri: Uri, mime: String, i: Int = 0): FwdMedia? = runCatching {
        val f = File(dir(ctx), "fwd_" + System.currentTimeMillis() + "_" + i + "." + extFor(mime))
        ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { o -> inp.copyTo(o) } }
        if (f.length() == 0L) { f.delete(); null } else FwdMedia(Uri.fromFile(f), mime)
    }.getOrNull()

    /** Text + every attachment of the chosen messages (oldest first). Every part of an MMS is included, not just the first. */
    suspend fun build(ctx: Context, msgs: List<Message>, fromThread: Long): ForwardPayload? = withContext(Dispatchers.IO) {
        val texts = ArrayList<String>(); val media = ArrayList<FwdMedia>()
        for (m in msgs.sortedBy { it.date }) {
            val body = m.body.trim()
            if (m.isMms) {
                val parts = SmsRepository.mmsAttachments(ctx, m.rowId).ifEmpty {
                    m.imageUri?.let { listOf(it to (m.mediaMime ?: ctx.contentResolver.getType(it) ?: "image/jpeg")) } ?: emptyList()
                }
                for ((u, mt) in parts) {
                    if (media.size >= MAX_MEDIA) break
                    copyLocal(ctx, u, mt, media.size)?.let { media += it }
                }
                // the placeholder line shown for a voice note / photo without text is not real text
                if (body.isNotBlank() && !(parts.isNotEmpty() && body.startsWith("\uD83C\uDFA4"))) texts += body
            } else if (body.isNotBlank()) texts += body
        }
        val text = texts.joinToString("\n")
        if (text.isBlank() && media.isEmpty()) null
        else ForwardPayload(text, media, fromThread, msgs.size).also { ForwardState.remember(media) }
    }

    /** A single photo or video from the full-screen viewer. */
    fun buildOne(ctx: Context, uri: Uri, fromThread: Long): ForwardPayload? {
        val mt = runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: "image/jpeg"
        val m = copyLocal(ctx, uri, mt) ?: return null
        ForwardState.remember(listOf(m))
        return ForwardPayload("", listOf(m), fromThread, 1)
    }

    /** Sends straight to one chat (several chats picked at once): first attachment carries the text, the rest follow. */
    private fun sendTo(ctx: Context, address: String, p: ForwardPayload): Boolean {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (p.media.isEmpty()) {
            return if (address.contains(";") && prefs.getBoolean("group_mms_send", true)) (SmsRepository.sendGroupText(ctx, address, p.text) || SmsRepository.sendSms(ctx, address, p.text))
            else SmsRepository.sendSms(ctx, address, p.text)
        }
        var ok = SmsRepository.sendMms(ctx, address, p.media.first().uri, p.text, p.media.first().mime.takeUnless { it.startsWith("image/") })
        if (ok) p.media.drop(1).forEach { m -> ok = SmsRepository.sendMms(ctx, address, m.uri, "", m.mime.takeUnless { it.startsWith("image/") }) && ok }
        return ok
    }

    fun sendToMany(ctx: Context, targets: List<Pair<String, String>>, p: ForwardPayload, onDone: (Int) -> Unit) {
        val app = ctx.applicationContext
        bg.launch {
            var sent = 0
            targets.forEach { (_, addr) -> if (runCatching { sendTo(app, addr, p) }.getOrDefault(false)) sent++ }
            withContext(Dispatchers.Main) {
                if (sent < targets.size) Toast.makeText(app, "Sent to $sent of ${targets.size}", Toast.LENGTH_LONG).show()
                onDone(sent)
            }
        }
    }
}

/** Forward from the full-screen photo viewer: the same picker, and back to the same chat afterwards. */
internal fun startForwardOne(ctx: Context, uri: Uri): Boolean {
    val p = Forwarder.buildOne(ctx, uri, ActiveThread.id) ?: run { Toast.makeText(ctx, "Could not forward", Toast.LENGTH_SHORT).show(); return false }
    ForwardState.payload = p
    if (openForwardPicker(ctx)) return true
    ForwardState.clear()
    return false
}

/**
 * Opens the "Forward message" picker for [ForwardState.payload]. In the app it slides in on top of the chat you are in.
 * From the floating chat bubble (which has no picker) the app itself opens on the picker; that used to say "Could not forward".
 */
internal fun openForwardPicker(ctx: Context): Boolean {
    val open = ForwardState.open
    if (open != null && ctx.findActivity() !is BubbleActivity) { open(); return true }
    return runCatching {
        ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("open_forward", true))
        true
    }.getOrDefault(false)
}

internal fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}

/** A row in the picker: a chat that already exists, a contact, or a typed number. */
private data class FwdTarget(val key: String, val name: String, val address: String, val threadId: Long, val sub: String)

private fun digits(s: String) = s.filter { it.isDigit() }
private fun last10(s: String) = digits(s).takeLast(10)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ForwardScreen(onPick: (Conversation) -> Unit, onNew: () -> Unit, onDone: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val payload = remember { ForwardState.payload }
    if (payload == null) { LaunchedEffect(Unit) { onBack() }; return }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var q by rememberSaveable { mutableStateOf("") }
    var convs by remember { mutableStateOf(ConvCache.list ?: emptyList()) }
    var contacts by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var picked by remember { mutableStateOf(listOf<FwdTarget>()) }
    var sending by remember { mutableStateOf(false) }
    val multi = picked.isNotEmpty()
    LaunchedEffect(Unit) {
        val fresh = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }
        if (fresh.isNotEmpty()) { convs = fresh; ConvCache.list = fresh }
        contacts = withContext(Dispatchers.IO) { SmsRepository.loadContactsList(ctx) }
    }
    BackHandler(enabled = multi) { picked = emptyList() }
    BackHandler(enabled = !multi) { onBack() }

    val needle = q.trim()
    val needleDigits = digits(needle)
    val typedNumber = needle.takeIf { t -> needleDigits.length >= 3 && t.all { it.isDigit() || it in "+-() ." } }?.replace(Regex("[\\s()\\-.]"), "")
    fun matches(name: String, addr: String): Boolean = needle.isEmpty() || name.contains(needle, true) ||
        (needleDigits.length >= 2 && digits(addr).contains(needleDigits)) || addr.contains(needle, true)
    val recent = remember(convs, needle) {
        convs.filter { matches(it.displayName, it.address) }.map { c ->
            val group = c.address.contains(";")
            FwdTarget("t" + c.threadId, c.displayName, c.address, c.threadId,
                if (group) "Group · ${c.address.split(";").size} people" else if (c.displayName != c.address) prettyNumber(c.address) else "")
        }
    }
    val people = remember(contacts, convs, needle) {
        if (needle.isEmpty()) emptyList() else {
            val inChats = convs.filter { !it.address.contains(";") }.map { last10(it.address) }.toSet()
            contacts.filter { (n, num) -> matches(n, num) && last10(num) !in inChats }.take(60).map { (n, num) -> FwdTarget("c$num", n, num, -1L, prettyNumber(num)) }
        }
    }

    fun open(t: FwdTarget) {
        focus.clearFocus()
        scope.launch {
            val tid = if (t.threadId > 0) t.threadId else withContext(Dispatchers.IO) { SmsRepository.getOrCreateThreadId(ctx, t.address) }
            if (tid <= 0) { Toast.makeText(ctx, "Could not open that chat", Toast.LENGTH_SHORT).show(); return@launch }
            onPick(Conversation(tid, t.address, t.name, "", System.currentTimeMillis(), false))
        }
    }
    fun toggle(t: FwdTarget) { picked = if (picked.any { it.key == t.key }) picked.filterNot { it.key == t.key } else picked + t }
    fun tap(t: FwdTarget) = if (multi) toggle(t) else open(t)

    Scaffold(containerColor = Chrome,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
                navigationIcon = { IconButton({ if (multi) picked = emptyList() else onBack() }) { Icon(if (multi) Ms.Close else Ms.ArrowBack, if (multi) "Cancel" else "Back") } },
                title = { Text(if (multi) "${picked.size} selected" else "Forward message", fontWeight = FontWeight.Normal, fontSize = 22.sp) })
        },
        bottomBar = {
            AnimatedVisibility(multi, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = ListBg) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(picked.joinToString(", ") { it.name }, Modifier.weight(1f), color = TextSecond, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                        Spacer(Modifier.width(12.dp))
                        Button(onClick = {
                            if (sending) return@Button
                            sending = true
                            val targets = picked.map { it.name to it.address }
                            val n = targets.size
                            Forwarder.sendToMany(ctx, targets, payload) { sent ->
                                if (sent > 0) ForwardState.doneNote = payload.fromThread to (if (n == 1) "Forwarded to ${targets[0].first}" else "Forwarded to $sent chats")
                            }
                            ForwardState.clear()
                            onDone()
                        }, enabled = !sending, colors = ButtonDefaults.buttonColors(Brand, contentColor = OnBrand), shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                            Icon(Ms.SendFill, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Send", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Search: filters chats and contacts as you type; a typed number offers "Send to …"
            TextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search or type a number", color = TextSecond) },
                leadingIcon = { Icon(Ms.Search, null, tint = TextSecond) },
                trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Ms.Close, "Clear", tint = TextSecond) } },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, keyboardType = KeyboardType.Text))
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                if (typedNumber != null && recent.none { last10(it.address) == last10(typedNumber) } && people.none { last10(it.address) == last10(typedNumber) }) item(key = "typed") {
                    val t = FwdTarget("n$typedNumber", typedNumber, typedNumber, -1L, "")
                    FwdRow(t, picked.any { it.key == t.key }, label = "Send to " + prettyNumber(typedNumber), onTap = { tap(t) }, onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(t) })
                }
                if (needle.isEmpty() && !multi) item(key = "new") {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onNew).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) { Icon(Ms.PersonAdd, null, tint = OnTonal, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.width(16.dp))
                        Text("New message", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "h_recent") { FwdHeader(if (needle.isEmpty()) "Recent conversations" else "Conversations") }
                    items(recent, key = { it.key }) { t ->
                        FwdRow(t, picked.any { it.key == t.key }, onTap = { tap(t) }, onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(t) })
                    }
                }
                if (people.isNotEmpty()) {
                    item(key = "h_people") { FwdHeader("Contacts") }
                    items(people, key = { it.key }) { t ->
                        FwdRow(t, picked.any { it.key == t.key }, onTap = { tap(t) }, onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(t) })
                    }
                }
                if (needle.isNotEmpty() && recent.isEmpty() && people.isEmpty() && typedNumber == null) item(key = "none") {
                    Text("No results", Modifier.fillMaxWidth().padding(32.dp), color = TextSecond, fontSize = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun FwdHeader(text: String) {
    Text(text, color = TextSecond, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FwdRow(t: FwdTarget, checked: Boolean, label: String? = null, onTap: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (checked) Brand.copy(alpha = .12f) else Color.Transparent)
        .combinedClickable(onClick = onTap, onLongClick = onLong).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp)) {
            // a tap on the picture picks the chat here; it does not open the photo
            CompositionLocalProvider(LocalAvatarTap provides false) { Avatar(t.name, t.address, 48.dp, 19.sp, tapToView = false) }
            androidx.compose.animation.AnimatedVisibility(checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Chrome).padding(1.dp), contentAlignment = Alignment.Center) {
                    Icon(Ms.CheckCircleFill, null, tint = Brand, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (label != null) Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Brand, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else {
                Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                if (t.sub.isNotBlank()) Text("\u2066" + t.sub + "\u2069", fontSize = 14.sp, color = TextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
            }
        }
    }
}

/** (845) 238-9140 for US numbers; anything else as it was. */
internal fun prettyNumber(raw: String): String {
    if (raw.contains(";")) return raw
    val d = raw.filter { it.isDigit() }
    val ten = when { d.length == 10 -> d; d.length == 11 && d.startsWith("1") -> d.drop(1); else -> return raw }
    return "(" + ten.substring(0, 3) + ") " + ten.substring(3, 6) + "-" + ten.substring(6)
}
