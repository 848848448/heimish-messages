package com.heimish.messages

import androidx.compose.ui.focus.focusRequester

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt


// ━━━━━━━━━━━━━━━━ THREAD / CHAT SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(conversation: Conversation, forwardTarget: Boolean = false, onForwardSent: (String) -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    // Last messages seen for this chat show at once (opening a chat again is instant); a fresh read follows
    var msgs by remember { mutableStateOf(ThreadCache.get(conversation.threadId) ?: emptyList()) }
    var draft by remember { mutableStateOf(DraftStore.get(ctx, conversation.threadId)) }
    val draftNow by rememberUpdatedState(draft)
    // Forwarded content staged here: if you back out without sending, the chat's own draft is kept, not the forwarded text
    var fwdPrior by remember { mutableStateOf<String?>(null) }
    var fwdFrom by remember { mutableLongStateOf(-1L) }
    val fwdPriorNow by rememberUpdatedState(fwdPrior)
    DisposableEffect(Unit) { onDispose { DraftStore.set(ctx, conversation.threadId, fwdPriorNow ?: draftNow) } }
    var showDetails by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var themeVer by remember { mutableIntStateOf(0) }
    val chatPal = remember(themeVer) { ChatThemeStore.palette(ctx, conversation.threadId) }
    val chatWall = remember(themeVer) { ChatThemeStore.wallpaper(ctx, conversation.threadId) }
    val screenActive = LocalScreenActive.current
    val openedAt = remember { System.currentTimeMillis() }
    val appearedKeys = remember { HashSet<String>() }
    val isRtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    if (screenActive && ThemeState.chat != chatPal) ThemeState.chat = chatPal
    val chatPalNow by rememberUpdatedState(chatPal)
    // Leaving the chat: hand the colors back as soon as the exit animation starts, not when it ends
    LaunchedEffect(screenActive) { if (!screenActive && ThemeState.chat == chatPalNow) ThemeState.chat = null }
    // Only clear the chat colors if they are still ours (opening another chat must not lose its colors)
    DisposableEffect(Unit) { onDispose { if (ThemeState.chat == chatPalNow) ThemeState.chat = null } }
    var moreOpen by remember { mutableStateOf(false) }
    // Opens at the newest message; when coming back to this chat (it is saved per back-stack entry) the old spot is restored.
    // The list is reversed (index 0 = newest, at the bottom), so a fresh chat starts at the bottom on its very first frame.
    // Coming back to this chat (it is saved per back-stack entry) restores the old spot.
    val listState = rememberLazyListState()
    // Unread boundary, read BEFORE the chat is marked read (-1 = not read yet, 0 = nothing unread, else the first unread's date)
    var unreadFrom by rememberSaveable { mutableLongStateOf(-1L) }
    var dividerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var boundaryResolved by rememberSaveable { mutableStateOf(false) }
    var openPlaced by rememberSaveable { mutableStateOf(false) }
    var fullLoaded by remember { mutableStateOf(false) }
    var newBelow by rememberSaveable { mutableIntStateOf(0) }
    // Shown at once when the chat is known to have nothing unread (it already sits at the bottom); otherwise after placing
    var positioned by rememberSaveable { mutableStateOf(msgs.isNotEmpty() && !conversation.unread) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var focusDecided by rememberSaveable { mutableStateOf(false) }
    val composeFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var ctxMsg by remember { mutableStateOf<Message?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var emojiTab by remember { mutableIntStateOf(0) }
    var showGallery by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaType by remember { mutableStateOf("image") }
    var extraMedia by remember { mutableStateOf(listOf<Uri>()) }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    // Reactions other people sent ("Liked “…”" texts folded into the message), reply quotes, jump-to-message, search hits
    var remoteReactions by remember { mutableStateOf<Map<String, List<RemoteReaction>>>(emptyMap()) }
    var replyVer by remember { mutableIntStateOf(0) }
    var flashKey by remember { mutableStateOf<String?>(null) }
    var jumpReq by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var hitIdx by remember { mutableIntStateOf(0) }
    // Text / photo shared in from another app (share sheet, sms: link without a number)
    LaunchedEffect(Unit) {
        if (!ShareState.isEmpty) {
            ShareState.text?.takeIf { it.isNotBlank() }?.let { t -> draft = if (draft.isBlank()) t else draft + "\n" + t }
            extraMedia = ShareState.uris.drop(1).take(9)
            ShareState.uris.firstOrNull()?.let { u ->
                val mt = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: ShareState.mime ?: ""
                pendingMediaUri = u
                pendingMediaType = when { mt.startsWith("video") -> "video"; mt.startsWith("audio") -> "audio"; else -> "image" }
            }
            ShareState.clear()
        }
        // Forward: the text and every attachment wait in the compose bar, ready to send
        ForwardState.payload?.takeIf { it.target == conversation.threadId }?.let { p ->
            ForwardState.clear()
            fwdPrior = draft; fwdFrom = p.fromThread
            if (p.text.isNotBlank()) draft = if (draft.isBlank()) p.text else draft + "\n" + p.text
            p.media.firstOrNull()?.let { f ->
                pendingMediaUri = f.uri
                pendingMediaType = when { f.mime.startsWith("video") -> "video"; f.mime.startsWith("audio") -> "audio"; f.mime.startsWith("image") -> "image"; else -> "file" }
                extraMedia = p.media.drop(1).map { it.uri }
            }
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    var searchFilter by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val rec = remember { VoiceRecState(ctx) }
    LaunchedEffect(Unit) { VoicePlayer.loadSpeed(ctx) }
    var reactTick by remember { mutableIntStateOf(0) }
    var lastCount by rememberSaveable { mutableIntStateOf(-1) }
    var lastKey by remember { mutableStateOf(msgs.lastOrNull()?.let { msgKey(it) }) }
    var loadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var loadAgain by remember { mutableStateOf(false) }
    var sendingNow by remember { mutableStateOf(false) }
    var msgLimit by remember { mutableIntStateOf(800) }
    var hasOlder by remember { mutableStateOf(false) }
    var loadingOlder by remember { mutableStateOf(false) }
    val enterSends = remember { prefs.getBoolean("enter_send", false) }
    var detailsMsg by remember { mutableStateOf<Message?>(null) }
    var schedMsg by remember { mutableStateOf<ScheduledMessage?>(null) }
    // Send to every member of the thread (groups), not just the address of the last message.
    var address by remember(conversation.threadId) { mutableStateOf(conversation.address) }
    LaunchedEffect(conversation.threadId) {
        address = withContext(Dispatchers.IO) { SmsRepository.threadAddress(ctx, conversation.threadId, conversation.address) }
    }
    val isGroupChat = address.contains(";")
    DisposableEffect(conversation.threadId) {
        ActiveThread.id = conversation.threadId
        Notifications.cancelThread(ctx, conversation.threadId)
        onDispose { if (ActiveThread.id == conversation.threadId) ActiveThread.id = -1L }
    }

    fun atBottom(): Boolean = listState.firstVisibleItemIndex <= 1
    suspend fun scrollToNewest(animated: Boolean) {
        runCatching { if (animated) listState.animateScrollToItem(0) else listState.scrollToItem(0) }
    }
    fun reload() {
        // One read at a time: the resume tick and the first composition used to start two full reads at once
        if (loadJob?.isActive == true) { loadAgain = true; return }
        loadJob = scope.launch {
            do {
                loadAgain = false
                val firstOpen = lastCount == -1
                val wasAtBottom = firstOpen || atBottom()
                if (firstOpen && msgs.isEmpty()) {
                    // Show the newest screenful right away, then read the rest
                    val quick = withContext(Dispatchers.IO) { SmsRepository.loadMessages(ctx, conversation.threadId, 40) }
                        .filter { it.body.isNotBlank() || it.imageUri != null }.let { Reactions.fold(ctx, it).msgs }
                    if (quick.isNotEmpty() && msgs.isEmpty()) { msgs = quick; lastKey = msgKey(quick.last()) }
                }
                val raw = withContext(Dispatchers.IO) { SmsRepository.loadMessages(ctx, conversation.threadId, msgLimit) }
                hasOlder = raw.size >= msgLimit
                val folded = withContext(Dispatchers.Default) { Reactions.fold(ctx, raw.filter { it.body.isNotBlank() || it.imageUri != null }) }
                val filteredMsgs = folded.msgs
                if (folded.reactions != remoteReactions) remoteReactions = folded.reactions
                if (withContext(Dispatchers.IO) { ReplyStore.bind(ctx, conversation.threadId, filteredMsgs) }) replyVer++
                val newLast = filteredMsgs.lastOrNull()?.let { msgKey(it) }
                val prevLast = lastKey
                val newArrived = newLast != prevLast
                val mineSent = filteredMsgs.lastOrNull()?.incoming == false
                if (filteredMsgs != msgs) msgs = filteredMsgs
                ThreadCache.put(conversation.threadId, filteredMsgs)
                lastKey = newLast
                fullLoaded = true
                if (loadingOlder) { loadingOlder = false; lastCount = filteredMsgs.size }
                else if (firstOpen) {
                    // where the chat opens is decided once, by the placing step (unread line or bottom), not here
                    lastCount = filteredMsgs.size
                } else if (newArrived) {
                    lastCount = filteredMsgs.size
                    // Follow new messages only when already at the bottom (or it's your own send); never yank someone reading older texts
                    if (filteredMsgs.isNotEmpty() && (wasAtBottom || mineSent)) { newBelow = 0; delay(40); scrollToNewest(true) }
                    else {
                        val at = filteredMsgs.indexOfLast { msgKey(it) == prevLast }
                        val added = if (at >= 0) filteredMsgs.subList(at + 1, filteredMsgs.size) else filteredMsgs.takeLast(1)
                        newBelow += added.count { it.incoming }.coerceAtLeast(if (added.isEmpty()) 0 else 1)
                    }
                }
            } while (loadAgain)
        }
    }
    // The unread line's message: decided once per opening, as soon as the loaded messages reach back to the first unread one
    LaunchedEffect(msgs, unreadFrom, fullLoaded) {
        if (boundaryResolved || unreadFrom < 0L) return@LaunchedEffect
        if (unreadFrom == 0L) { boundaryResolved = true; return@LaunchedEffect }
        val first = msgs.firstOrNull { it.incoming && it.date >= unreadFrom }
        val covers = msgs.isNotEmpty() && (msgs.first().date < unreadFrom || fullLoaded)
        if (first != null && covers) { dividerKey = msgKey(first); boundaryResolved = true }
        else if (fullLoaded) boundaryResolved = true
    }
    // No keyboard when a chat opens; it comes up only when the text field is tapped. Exceptions: a brand-new conversation
    // with no messages yet, or a chat opened to reply (from a notification).
    LaunchedEffect(Unit) { if (!focusDecided) { focusManager.clearFocus(true); keyboard?.hide() } }
    LaunchedEffect(fullLoaded) {
        if (!fullLoaded || focusDecided) return@LaunchedEffect
        focusDecided = true
        if (msgs.isEmpty() || ReplyFocus.take(conversation.threadId)) { delay(150); runCatching { composeFocus.requestFocus() }; keyboard?.show() }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    fun replyWho(m: Message): String = if (!m.incoming) "You" else if (isGroupChat) (m.address?.let { SmsRepository.getContactName(ctx, it) ?: it } ?: conversation.displayName) else conversation.displayName
    /** A forwarded message just went out: back to the chat it was forwarded from, which shows "Forwarded to …". */
    fun forwardWentOut(verb: String = "Forwarded to") {
        if (fwdPrior == null) return
        fwdPrior = null
        val note = verb + " " + conversation.displayName
        if (forwardTarget && fwdFrom > 0 && fwdFrom != conversation.threadId) { ForwardState.doneNote = fwdFrom to note; onForwardSent(note) }
    }
    fun doSend() {
        if (sendingNow) return  // a double tap used to send the same message twice
        val body = draft.trim()
        if (pendingMediaUri != null) {
            val uri = pendingMediaUri!!
            val mime = when (pendingMediaType) {
                "video" -> ForwardState.mimeOf(uri) ?: ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                "audio" -> ForwardState.mimeOf(uri) ?: ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
                "file" -> ForwardState.mimeOf(uri) ?: ctx.contentResolver.getType(uri)
                else -> null
            }
            sendingNow = true
            val extras = extraMedia
            replyTo?.let { ReplyStore.pending(ctx, conversation.threadId, body, it, replyWho(it)) }
            scope.launch(Dispatchers.IO) {
                val ok = SmsRepository.sendMms(ctx, address, uri, body, mime)
                if (ok) extras.forEach { u -> SmsRepository.sendMms(ctx, address, u, "", ForwardState.mimeOf(u)?.takeUnless { it.startsWith("image/") }) }
                withContext(Dispatchers.Main) {
                    sendingNow = false
                    if (ok) { draft = ""; pendingMediaUri = null; extraMedia = emptyList(); replyTo = null; SendSound.play(ctx); reload(); forwardWentOut() }
                    else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (body.isEmpty()) return
        sendingNow = true
        val text = body
        replyTo?.let { ReplyStore.pending(ctx, conversation.threadId, text, it, replyWho(it)) }
        draft = ""; replyTo = null
        scope.launch(Dispatchers.IO) {
            val ok = if (address.contains(";") && prefs.getBoolean("group_mms_send", true)) (SmsRepository.sendGroupText(ctx, address, text) || SmsRepository.sendSms(ctx, address, text)) else SmsRepository.sendSms(ctx, address, text)
            withContext(Dispatchers.Main) {
                sendingNow = false
                if (ok) { SendSound.play(ctx); reload(); forwardWentOut() }
                else { if (draft.isEmpty()) draft = text; Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /** Sends one text right away without touching what you typed or a photo waiting in the bar (stickers, smart replies). */
    fun sendText(text: String) {
        if (text.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val ok = if (address.contains(";") && prefs.getBoolean("group_mms_send", true)) (SmsRepository.sendGroupText(ctx, address, text) || SmsRepository.sendSms(ctx, address, text)) else SmsRepository.sendSms(ctx, address, text)
            withContext(Dispatchers.Main) { if (ok) { SendSound.play(ctx); reload() } else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
        }
    }

    var showQuick by remember { mutableStateOf(false) }
    /** Forward one or more messages: read their text and every attachment, then open the "Forward message" picker. */
    var forwarding by remember { mutableStateOf(false) }
    fun startForward(list: List<Message>) {
        if (forwarding || list.isEmpty()) return
        forwarding = true
        scope.launch {
            val p = Forwarder.build(ctx, list, conversation.threadId)
            forwarding = false
            if (p == null) Toast.makeText(ctx, "Nothing to forward", Toast.LENGTH_SHORT).show()
            else {
                ForwardState.payload = p; keyboard?.hide()
                // From the floating bubble the app opens on the picker (the bubble has no picker of its own)
                if (!openForwardPicker(ctx)) { ForwardState.clear(); Toast.makeText(ctx, "Could not forward", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    // Back from a forward: a short "Forwarded to …" at the bottom of this chat
    val fwdSnack = remember { SnackbarHostState() }
    val doneNote = ForwardState.doneNote
    LaunchedEffect(doneNote, screenActive) {
        val n = doneNote ?: return@LaunchedEffect
        if (n.first != conversation.threadId || !screenActive) return@LaunchedEffect
        ForwardState.doneNote = null
        fwdSnack.showSnackbar(n.second, duration = SnackbarDuration.Short)
    }
    if (showTheme) ChatThemeScreen(conversation) { applied -> showTheme = false; if (applied) { themeVer++; reactTick++ } }
    if (showDetails) ChatDetailsDialog(conversation, msgs, { showDetails = false }, onTheme = { showDetails = false; showTheme = true }, onSearch = { showDetails = false; showSearch = true }) { showDetails = false; onBack() }
    var showMedia by remember { mutableStateOf(false) }
    if (showQuick) {
        val qr = QuickReplies.get(ctx)
        AlertDialog(onDismissRequest = { showQuick = false }, containerColor = Surf, title = { Text("Quick replies", color = TextPrimary) },
            text = { Column { qr.forEach { r -> Text(r, Modifier.fillMaxWidth().clickable { draft = if (draft.isBlank()) r else draft + " " + r; showQuick = false }.padding(vertical = 12.dp), color = TextPrimary, fontSize = 16.sp) } } },
            confirmButton = { TextButton({ showQuick = false }) { Text("Close") } })
    }
    if (showMedia) MediaDialog(msgs) { showMedia = false }
    /** Long-press Send (or + → Schedule send): pick a time; the text and any photo/video waiting in the bar go out then. */
    fun pickScheduleTime() {
        if (draft.isBlank() && pendingMediaUri == null) { Toast.makeText(ctx, "Type a message first", Toast.LENGTH_SHORT).show(); return }
        TimeMenu.show(ctx, "Schedule send", reminder = false) { at ->
            val body = draft.trim(); val media = pendingMediaUri; val extras = extraMedia; val type = pendingMediaType
            draft = ""; pendingMediaUri = null; extraMedia = emptyList(); replyTo = null
            scope.launch(Dispatchers.IO) {
                val mt = media?.let { u -> if (type == "image") null else ForwardState.mimeOf(u) ?: ctx.contentResolver.getType(u) ?: (if (type == "video") "video/mp4" else "audio/mp4") }
                ScheduledSendReceiver.schedule(ctx, address, body, at, media, mt)
                extras.forEachIndexed { i, u -> ScheduledSendReceiver.schedule(ctx, address, "", at + 1000L * (i + 1), u, ForwardState.mimeOf(u)?.takeUnless { it.startsWith("image/") }) }
                withContext(Dispatchers.Main) { reactTick++; forwardWentOut("Scheduled for") }
            }
            Toast.makeText(ctx, "Scheduled for " + TimeMenu.label(at), Toast.LENGTH_LONG).show()
        }
    }

    /** A finished voice note goes straight out as MMS audio/mp4, like WhatsApp. */
    fun sendVoice(f: File) {
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendMms(ctx, address, Uri.fromFile(f), "", "audio/mp4")
            f.delete()
            withContext(Dispatchers.Main) {
                if (ok) { replyTo = null; SendSound.play(ctx); reload() }
                else Toast.makeText(ctx, "Voice note not sent", Toast.LENGTH_SHORT).show()
            }
        }
    }
    rec.onLimit = { rec.finish(true)?.let { sendVoice(it) }; Toast.makeText(ctx, "Voice note reached the MMS size limit and was sent", Toast.LENGTH_LONG).show() }

    // Pick several photos at once; they go out one after another (the first one carries the text)
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> if (uris.isNotEmpty()) { pendingMediaUri = uris.first(); extraMedia = uris.drop(1).take(9); pendingMediaType = "image"; showAttach = false } }
    // Photos/videos picked in the in-chat gallery go straight into the compose bar (the text you type is the caption)
    fun setPicked(l: List<Uri>) {
        if (l.isEmpty()) { pendingMediaUri = null; extraMedia = emptyList(); return }
        pendingMediaUri = l.first(); extraMedia = l.drop(1).take(9)
        pendingMediaType = if (isVideoUri(ctx, l.first())) "video" else "image"
    }
    val pickedNow: List<Uri> = if (pendingMediaUri != null && pendingMediaType != "audio") listOf(pendingMediaUri!!) + extraMedia else emptyList()
    // "Gallery" in the sheet: Android's full photo picker (albums, search), photos and videos, up to 10
    val fullPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) { setPicked((pickedNow + uris).distinct().take(10)); showGallery = false }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> pendingMediaUri = uri; pendingMediaType = "video"; showAttach = false } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val mime = ctx.contentResolver.getType(it) ?: ""
            when {
                mime.startsWith("image/") -> { pendingMediaUri = it; pendingMediaType = "image" }
                mime.startsWith("video/") -> { pendingMediaUri = it; pendingMediaType = "video" }
                mime.startsWith("audio/") -> { pendingMediaUri = it; pendingMediaType = "audio" }
                else -> Toast.makeText(ctx, "Only photos, videos and audio can be sent", Toast.LENGTH_SHORT).show()
            }
            showAttach = false
        }
    }
    val toneL = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            val u = if (Build.VERSION.SDK_INT >= 33) r.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) else @Suppress("DEPRECATION") r.data?.getParcelableExtra<Uri>(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            prefs.edit().putString("tone_${conversation.threadId}", u?.toString() ?: "").apply()
            Toast.makeText(ctx, "Notification sound saved for this chat", Toast.LENGTH_SHORT).show()
        }
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { pendingMediaUri = it; pendingMediaType = "image"; showAttach = false; showGallery = false }
        else cameraUri?.let { u -> scope.launch(Dispatchers.IO) { runCatching { ctx.contentResolver.delete(u, null, null) } } } // no empty photo left in the gallery
    }
    fun openCamera() {
        val uri = runCatching {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "message_${System.currentTimeMillis()}.jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri != null) { cameraUri = uri; runCatching { cameraLauncher.launch(uri) }.onFailure { Toast.makeText(ctx, "Camera unavailable", Toast.LENGTH_SHORT).show() } }
        else Toast.makeText(ctx, "Camera unavailable", Toast.LENGTH_SHORT).show()
    }
    // The camera needs the CAMERA permission first (the app declares it, so without it the camera tile did nothing)
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) openCamera() else Toast.makeText(ctx, "Camera permission needed", Toast.LENGTH_SHORT).show() }
    fun startCamera() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera()
        else camPerm.launch(android.Manifest.permission.CAMERA)
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.query(it, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts._ID), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0) ?: ""
                        val contactId = c.getLong(1)
                        var number = ""
                        // Look the number up by contact id (by name it picked the wrong person when two share a name)
                        ctx.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", arrayOf(contactId.toString()),
                            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY + " DESC"
                        )?.use { pc -> if (pc.moveToFirst()) number = pc.getString(0) ?: "" }
                        val add = if (number.isBlank()) name else "$name: $number"
                        draft = if (draft.isBlank()) add else "$draft\n$add"
                    }
                }
            }
            showAttach = false
        }
    }
    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(ctx, "Microphone permission needed for voice notes", Toast.LENGTH_SHORT).show()
    }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            Toast.makeText(ctx, "Getting your location…", Toast.LENGTH_SHORT).show()
            LocationFix.get(ctx) { loc ->
                if (loc != null) {
                    val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                    draft = if (draft.isBlank()) link else "$draft\n$link"
                } else Toast.makeText(ctx, "Location unavailable — is Location turned on?", Toast.LENGTH_LONG).show()
            }
        } else Toast.makeText(ctx, "Location permission needed", Toast.LENGTH_SHORT).show()
        showAttach = false
    }

    BackHandler { onBack() }
    val threadTick = rememberProviderTick()
    LaunchedEffect(threadTick) {
        if (threadTick > 1) delay(120)
        // Where the unread texts start is read before the chat is marked read, so marking it read can't move the line
        if (unreadFrom < 0L) unreadFrom = withContext(Dispatchers.IO) { SmsRepository.firstUnreadDate(ctx, conversation.threadId) }
        reload()
        withContext(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, conversation.threadId) }
    }
    DisposableEffect(Unit) { onDispose { rec.finish(false); VoicePlayer.stop() } }
    BackHandler(enabled = rec.mode == RecMode.LOCKED) { rec.finish(false) }

    // Photo viewer over every photo in this chat, opened at the tapped one (swipe for the others)
    var viewerAt by remember { mutableStateOf<String?>(null) }
    val photoOpener: (Message) -> Unit = remember { { m: Message -> viewerAt = msgKey(m) } }
    viewerAt?.let { k -> ChatPhotoViewer(msgs, k, isGroupChat, conversation.displayName, onClose = { viewerAt = null }, onReacted = { reactTick++ }) }
    detailsMsg?.let { dm -> MessageDetailsDialog(dm, conversation.displayName, address) { detailsMsg = null } }
    schedMsg?.let { sm -> ScheduledMessageDialog(sm, onDismiss = { schedMsg = null }, onChanged = { reactTick++; reload() }) }

    // ── Message context menu (long-press) ──
    // ── Message long-press: Google Messages overlay (reactions on top, menu card below) ──
    val bubbleYs = remember { mutableMapOf<String, Float>() }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting && ctxMsg == null) { selected = emptySet() }
    // When the keyboard comes up, close the + / emoji / gallery panel (it used to sit on top of the keyboard and squash the chat)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(imeUp) { if (imeUp) { showAttach = false; showEmoji = false; showGallery = false } }
    // Back closes an open panel or the in-chat search first, instead of leaving the chat
    BackHandler(enabled = ctxMsg == null && !selecting && (showAttach || showEmoji || showGallery)) { showAttach = false; showEmoji = false; showGallery = false }
    BackHandler(enabled = ctxMsg == null && !selecting && !(showAttach || showEmoji || showGallery) && showSearch) { showSearch = false; searchQuery = ""; searchFilter = 0 }
    // In-chat search: every match is highlighted; ↑ ↓ go through them, newest first (Google Messages style)
    val searchHits = remember(msgs, showSearch, searchQuery) {
        if (showSearch && searchQuery.isNotBlank()) msgs.filter { it.body.contains(searchQuery, true) }.map { msgKey(it) }.asReversed() else emptyList()
    }
    LaunchedEffect(searchHits) { hitIdx = 0 }
    val currentHit = searchHits.getOrNull(hitIdx.coerceIn(0, (searchHits.size - 1).coerceAtLeast(0)))
    LaunchedEffect(currentHit) { currentHit?.let { jumpReq = it to false } }
    Box(Modifier.fillMaxSize()) {
    Scaffold(containerColor = Chrome, modifier = if (ctxMsg != null) Modifier.blur(14.dp) else Modifier,
        snackbarHost = { SnackbarHost(fwdSnack) { d -> Snackbar(d, shape = RoundedCornerShape(12.dp), containerColor = TextPrimary, contentColor = Bg) } },
        topBar = {
            if (selecting) TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextSecond),
                navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Ms.Close, "Cancel") } },
                title = { Text("${selected.size}", fontSize = 20.sp) },
                actions = {
                    val chosen = msgs.filter { msgKey(it) in selected }.sortedBy { it.date }
                    IconButton({
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msgs", chosen.joinToString("\n") { it.body }))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); selected = emptySet()
                    }) { Icon(Ms.ContentCopy, "Copy") }
                    IconButton({ startForward(chosen); selected = emptySet() }) { Icon(Ms.Forward, "Forward") }
                    IconButton({ chosen.forEach { StarStore.toggle(ctx, msgKey(it)) }; reactTick++; selected = emptySet() }) { Icon(Ms.Star, "Star") }
                    IconButton({
                        scope.launch(Dispatchers.IO) { chosen.forEach { SmsRepository.deleteMessage(ctx, it) }; withContext(Dispatchers.Main) { reload() } }
                        selected = emptySet()
                    }) { Icon(Ms.Delete, "Delete") }
                }
            ) else
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextSecond),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Ms.ArrowBack, "Back") } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat…", color = TextHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), modifier = Modifier.fillMaxWidth())
                    } else Row(Modifier.clickable { showDetails = true }, verticalAlignment = Alignment.CenterVertically) {
                        val members = remember(address) { SmsRepository.splitRecipients(address).filter { it.isNotBlank() } }
                        // a group: its own picture when one was set in Details, else the members' photos together
                        key(showDetails) { if (members.size > 1) Avatar(conversation.displayName, address, 40.dp, 17.sp, tapToView = false) else Avatar(conversation.displayName, address, 40.dp, 17.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            // a group, like Google Messages: its own name when one was set, else first names only, one line, and "4 people" under it
                            val title = if (members.size > 1) remember(address, conversation.displayName, showDetails) { GroupInfo.name(ctx, conversation.threadId) ?: groupFirstNames(ctx, members) } else conversation.displayName
                            Text(title, fontWeight = FontWeight.Normal, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary, style = listTextStyle())
                            if (members.size > 1) Text("${members.size} people", fontSize = 12.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (showSearch) {
                        ChatSearchActions(searchHits.size, hitIdx, searchFilter, onPrev = { hitIdx++ }, onNext = { hitIdx-- },
                            onFilter = { searchFilter = (searchFilter + 1) % 3 }, onClose = { showSearch = false; searchQuery = ""; searchFilter = 0 })
                    } else {
                        if (!address.contains(";")) {
                            IconButton({ Caller.call(ctx, address) }) { Icon(Ms.Call, "Call") }
                        }
                        Box {
                            IconButton({ showMenu = true }) { Icon(Ms.MoreVert, "Menu") }
                            // The menu keeps the everyday actions; everything else is in one "More" sheet (it used to be 7 items, 15 expanded)
                            fun muteAction() {
                                if (Mute.isMuted(ctx, conversation.threadId)) { Mute.clear(ctx, conversation.threadId); Toast.makeText(ctx, "Unmuted", Toast.LENGTH_SHORT).show() }
                                else android.app.AlertDialog.Builder(ctx).setTitle("Mute notifications").setItems(arrayOf("1 hour", "8 hours", "24 hours", "Always")) { _, i -> Mute.setFor(ctx, conversation.threadId, longArrayOf(3_600_000L, 28_800_000L, 86_400_000L, 0L)[i]); Toast.makeText(ctx, "Muted", Toast.LENGTH_SHORT).show() }.show()
                            }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf), shape = RoundedCornerShape(16.dp)) {
                                DropdownMenuItem({ Text("Search", color = TextPrimary) }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Ms.Search, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Details", color = TextPrimary) }, { showMenu = false; showDetails = true }, leadingIcon = { Icon(Ms.Info, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Schedule send", color = TextPrimary) }, { showMenu = false; pickScheduleTime() }, leadingIcon = { Icon(Ms.Schedule, null, tint = TextSecond) })
                                DropdownMenuItem({ Text(if (Mute.isMuted(ctx, conversation.threadId)) "Unmute" else "Mute notifications", color = TextPrimary) }, { showMenu = false; muteAction() },
                                    leadingIcon = { Icon(Ms.NotificationsOff, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("More", color = TextPrimary) }, { showMenu = false; moreOpen = true }, leadingIcon = { Icon(Ms.MoreHoriz, null, tint = TextSecond) })
                            }
                            if (moreOpen) {
                                val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                                fun close(then: () -> Unit) { moreOpen = false; then() }
                                ModalBottomSheet({ moreOpen = false }, sheetState = sheet, containerColor = Surf) {
                                    @Composable fun Item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = TextSecond, onClick: () -> Unit) =
                                        Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, null, tint = tint); Spacer(Modifier.width(20.dp)); Text(label, fontSize = 16.sp, color = TextPrimary)
                                        }
                                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                                        Item(Ms.Palette, "Chat theme") { close { showTheme = true } }
                                        Item(Ms.PhotoLibrary, "Media and links") { close { showMedia = true } }
                                        Item(Ms.Reply, "Quick replies") { close { showQuick = true } }
                                        Item(Ms.Notifications, "Remind me") { close {
                                            android.app.AlertDialog.Builder(ctx).setTitle("Remind me").setItems(arrayOf("In 1 hour", "In 3 hours", "Tomorrow morning")) { _, i ->
                                                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0) }
                                                RemindReceiver.set(ctx, conversation, when (i) { 0 -> System.currentTimeMillis() + 3_600_000L; 1 -> System.currentTimeMillis() + 10_800_000L; else -> cal.timeInMillis })
                                                Toast.makeText(ctx, "Reminder set", Toast.LENGTH_SHORT).show() }.show()
                                        } }
                                        Item(Ms.Label, "Label") { close {
                                            val et = android.widget.EditText(ctx); et.setText(LabelStore.get(ctx, conversation.threadId) ?: "")
                                            android.app.AlertDialog.Builder(ctx).setTitle("Label this chat").setView(et).setPositiveButton("Save") { _, _ -> LabelStore.set(ctx, conversation.threadId, et.text.toString()); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancel", null).show()
                                        } }
                                        Item(Ms.MusicNote, "Notification sound") { close {
                                            toneL.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
                                        } }
                                        Item(Ms.Share, "Export chat") { close { exportChat(ctx, conversation, msgs) } }
                                        Item(Ms.AddToHomeScreen, "Add to home screen") { close { pinChatShortcut(ctx, conversation) } }
                                        Item(Ms.Block, "Block & report spam", Red) { close { BlockedStore.block(ctx, address); Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); onBack() } }
                                        Item(Ms.Delete, "Delete conversation", Red) { close {
                                            scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, conversation.threadId) }
                                            Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); onBack()
                                        } }
                                    }
                                }
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = ListBg) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (replyTo != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Brand))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reply to ${if (replyTo!!.incoming) conversation.displayName else "yourself"}", fontSize = 12.sp, color = Brand, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                PreviewLine(replyTo!!.body.take(80).ifBlank { "\uD83D\uDCF7 Photo" }, TextSecond, 13.sp, FontWeight.Normal)
                            }
                            IconButton({ replyTo = null }, Modifier.size(28.dp)) { Icon(Ms.Close, "Remove reply", Modifier.size(18.dp), tint = TextHint) }
                        }
                    }
                    if (pendingMediaUri != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (pendingMediaType) {
                                "image" -> AsyncImage(pendingMediaUri, "preview", Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                "video" -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    MediaThumb(pendingMediaUri!!, true, Modifier.fillMaxSize())
                                    Icon(Ms.PlayCircleFill, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                else -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Brand), contentAlignment = Alignment.Center) {
                                    Icon(if (pendingMediaType == "file") Ms.Description else Ms.MicFill, null, tint = OnBrand, modifier = Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            extraMedia.take(3).forEach { u ->
                                Spacer(Modifier.width(6.dp))
                                val em = ForwardState.mimeOf(u)
                                if (em != null && !em.startsWith("image/") && !em.startsWith("video/"))
                                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Tonal), contentAlignment = Alignment.Center) {
                                        Icon(if (em.startsWith("audio/")) Ms.MicFill else Ms.Description, null, tint = OnTonal, modifier = Modifier.size(26.dp))
                                    }
                                else MediaThumb(u, em?.startsWith("video/") ?: isVideoUri(ctx, u), Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(if (extraMedia.isNotEmpty()) "${extraMedia.size + 1} ready to send" else when (pendingMediaType) { "image" -> "Photo ready to send"; "video" -> "Video ready to send"; "file" -> "File ready to send"; else -> "Voice note ready to send" }, fontSize = 14.sp, color = TextSecond, modifier = Modifier.weight(1f))
                            IconButton({ pendingMediaUri = null; extraMedia = emptyList() }) { Icon(Ms.Close, "Remove", tint = TextHint) }
                        }
                    }
                    if (draft.length > 100) {
                        // Android's own GSM-7 / UCS-2 count (handles the GSM extension table, accents, emoji, Hebrew)
                        val segs = remember(draft) { smsCounter(draft).second }
                        Text("${draft.length} chars · $segs SMS", Modifier.fillMaxWidth().padding(end = 22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 11.sp, color = TextHint)
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        // Google Messages compose bar: one pill with ⊕, the text, emoji and gallery; a round voice/send button beside it
                        if (rec.mode != RecMode.IDLE) RecordingBar(rec, onTrash = { rec.finish(false) }, modifier = Modifier.weight(1f))
                        else Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(FieldBg).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton(onClick = { showAttach = !showAttach; showEmoji = false; showGallery = false; if (showAttach) keyboard?.hide() }, Modifier.size(48.dp)) {
                                Icon(if (showAttach) Ms.Close else Ms.AddCircleFill, "Attach", tint = TextPrimary, modifier = Modifier.size(24.dp))
                            }
                            androidx.compose.foundation.text.BasicTextField(
                                draft, { draft = it }, Modifier.weight(1f).padding(vertical = 14.dp).focusRequester(composeFocus)
                                    // GIFs and stickers from the keyboard (Gboard etc.) become a picture message
                                    .contentReceiver { tc ->
                                        if (!tc.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) return@contentReceiver tc
                                        tc.consume { item ->
                                            val u = item.uri ?: return@consume false
                                            val local = runCatching {
                                                val mt = ctx.contentResolver.getType(u) ?: "image/gif"
                                                val ext = if (mt.contains("gif")) "gif" else if (mt.contains("webp")) "webp" else if (mt.contains("png")) "png" else "jpg"
                                                val f = File(ctx.cacheDir, "kb_${System.currentTimeMillis()}.$ext")
                                                ctx.contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } }
                                                Uri.fromFile(f)
                                            }.getOrNull() ?: return@consume false
                                            pendingMediaUri = local; pendingMediaType = "image"; extraMedia = emptyList()
                                            true
                                        }
                                    },
                                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp, lineHeight = 22.sp, fontFamily = FontState.family),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
                                // Enter makes a new line (like Google Messages) unless "Press Enter to send" is on
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (enterSends) ImeAction.Send else ImeAction.Default),
                                keyboardActions = KeyboardActions(onSend = { doSend() }),
                                maxLines = 6,
                                decorationBox = { inner ->
                                    Column {
                                        Box { if (draft.isEmpty()) Text(if (address.contains(";")) "Group message" else "Text message", color = TextHint, fontSize = 16.sp); inner() }
                                    }
                                }
                            )
                            IconButton({ emojiTab = 0; showEmoji = !showEmoji; showAttach = false; showGallery = false; if (showEmoji) keyboard?.hide() }, Modifier.size(48.dp)) { Icon(Ms.Mood, "Emoji", tint = TextPrimary, modifier = Modifier.size(24.dp)) }
                            AnimatedVisibility(draft.isEmpty(), enter = fadeIn(androidx.compose.animation.core.tween(150)) + expandHorizontally(androidx.compose.animation.core.tween(180)), exit = fadeOut(androidx.compose.animation.core.tween(100)) + shrinkHorizontally(androidx.compose.animation.core.tween(180))) {
                                IconButton({ showGallery = !showGallery; showAttach = false; showEmoji = false; if (showGallery) keyboard?.hide() }, Modifier.size(48.dp)) { Icon(Ms.AddPhotoAlternate, "Photo", tint = TextPrimary, modifier = Modifier.size(24.dp)) }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        val hasCont = draft.isNotBlank() || pendingMediaUri != null
                        MicSendButton(hasCont, rec, onSendText = { doSend() }, onNeedPermission = { audioPerm.launch(android.Manifest.permission.RECORD_AUDIO) }, onVoiceDone = { f -> sendVoice(f) }, onLongSend = { pickScheduleTime() })
                    }
                    AnimatedVisibility(showAttach, enter = PanelEnter, exit = PanelExit) {
                        PlusPanel(listOf(
                            Triple(Ms.PhotoLibrary, "Gallery") { showAttach = false; showEmoji = false; showGallery = true },
                            Triple(Ms.Gif, "GIFs") { showAttach = false; emojiTab = 1; showEmoji = true },
                            Triple(Ms.StickyNote2, "Stickers") { showAttach = false; emojiTab = 2; showEmoji = true },
                            Triple(Ms.AttachFile, "Files") { filePicker.launch("*/*") },
                            Triple(Ms.LocationOn, "Location") { locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) },
                            Triple(Ms.AccountCircle, "Contacts") { contactPicker.launch(null) },
                            Triple(Ms.Schedule, "Schedule send") { showAttach = false; pickScheduleTime() },
                            Triple(Ms.PhotoCamera, "Camera") { startCamera() },
                            Triple(Ms.Videocam, "Video") { videoPicker.launch("video/*") },
                            Triple(Ms.Mic, "Voice") { showAttach = false; if (rec.hasPermission()) { if (rec.start()) rec.mode = RecMode.LOCKED } else audioPerm.launch(android.Manifest.permission.RECORD_AUDIO) },
                        ))
                    }
                    AnimatedVisibility(showGallery, enter = PanelEnter, exit = PanelExit) {
                        GalleryPanel(selected = pickedNow, onSelectionChange = { setPicked(it) }, onCamera = { startCamera() },
                            onFullPicker = { runCatching { fullPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }.onFailure { imagePicker.launch("image/*") } },
                            onCaption = { showGallery = false; runCatching { composeFocus.requestFocus() }; keyboard?.show() },
                            onSend = { showGallery = false; doSend() },
                            onClose = { showGallery = false })
                    }
                    AnimatedVisibility(showEmoji, enter = PanelEnter, exit = PanelExit) {
                        ExpressionPanel(emojiTab, onEmoji = { draft += it },
                            onGif = { u -> pendingMediaUri = u; pendingMediaType = "image"; extraMedia = emptyList(); showEmoji = false },
                            onSticker = { s -> showEmoji = false; sendText(s) })
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ListBg)
            .background(Color(ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getInt("chat_bg", 0)))) {
        ChatWallpaper(conversation.threadId, chatWall, themeVer, Modifier.matchParentSize())
        // Everything the list shows is worked out once per change, not on every recomposition of the list
        val displayMsgs = remember(msgs, showSearch, searchFilter) {
            if (showSearch && searchFilter != 0) msgs.filter { (searchFilter == 1 && it.imageUri != null) || (searchFilter == 2 && isLinkMsg(it)) } else msgs
        }
        val grouped = remember(displayMsgs) { displayMsgs.groupByDate() }
        val pins = remember(msgs, showSearch, reactTick) { if (showSearch) emptyList() else msgs.filter { MsgPinStore.has(ctx, msgKey(it)) } }
        val mine = remember(address, reactTick, msgs) {
            val norm = { a: String -> SmsRepository.splitRecipients(a).map { it.filter { c -> c.isDigit() }.takeLast(10) }.sorted().joinToString(";") }
            val me = norm(address)
            ScheduledStore.getAll(ctx).filter { norm(it.address) == me }.sortedBy { it.sendAt }
        }
        // Rows bottom-up (the list is reversed). The "Unread messages" line sits above the first message that was unread when
        // the chat was opened; it stays while the chat is open and is gone the next time it opens.
        val chatRows = remember(grouped, dividerKey, showSearch) {
            val out = ArrayList<ChatRow>(displayMsgs.size + grouped.size + 1)
            grouped.forEach { (day, dm) ->
                out.add(ChatRow.Day(day))
                dm.forEachIndexed { idx, m ->
                    if (!showSearch && dividerKey != null && msgKey(m) == dividerKey) out.add(ChatRow.Unread)
                    out.add(ChatRow.Msg(m, idx, dm))
                }
            }
            out.reverse(); out
        }
        val showSmart = msgs.isNotEmpty() && msgs.last().incoming
        val headerRows = (if (showSmart) 1 else 0) + (if (!showSearch) mine.size else 0)
        val dividerIndex = remember(chatRows, headerRows) { chatRows.indexOf(ChatRow.Unread).let { if (it < 0) -1 else headerRows + it } }
        // Where the chat opens, worked out before anything is shown (the list is invisible until then, so nothing visibly scrolls):
        // unread texts -> the first unread one near the top with the line above it (or simply the bottom if they all fit);
        // nothing unread -> the bottom, which is where a reversed list already starts.
        LaunchedEffect(boundaryResolved, chatRows, fullLoaded) {
            if (openPlaced || !boundaryResolved) return@LaunchedEffect
            if (chatRows.isEmpty()) { if (fullLoaded) { openPlaced = true; positioned = true }; return@LaunchedEffect }
            runCatching {
                listState.scrollToItem(0)
                val d = dividerIndex
                if (d > 0) {
                    withTimeoutOrNull(600) { snapshotFlow { listState.layoutInfo }.first { it.totalItemsCount > d && it.visibleItemsInfo.isNotEmpty() } }
                    val li = listState.layoutInfo
                    val fits = li.visibleItemsInfo.any { it.index == d } && (li.visibleItemsInfo.lastOrNull()?.index ?: 0) > d
                    if (!fits) {
                        listState.scrollToItem(d, 0)  // the line at the bottom edge…
                        withTimeoutOrNull(600) { snapshotFlow { listState.layoutInfo }.first { l -> l.visibleItemsInfo.any { it.index == d } } }
                        val l2 = listState.layoutInfo
                        val dv = l2.visibleItemsInfo.firstOrNull { it.index == d }
                        val vp = l2.viewportEndOffset - l2.viewportStartOffset
                        val margin = with(density) { 56.dp.toPx() }
                        // …then up to near the top, so the first new text is the first thing read
                        if (dv != null && vp > 0) listState.scrollBy(-(vp - dv.size - margin).coerceAtLeast(0f))
                    }
                }
            }
            openPlaced = true
            positioned = true
        }
        // Jump to a message (reply quote tap, search hit, reminder notification, Starred): scroll it to the middle and flash it.
        // Not loaded yet (older)? Load more and try again.
        LaunchedEffect(ChatJump.seq, openPlaced) { if (openPlaced) ChatJump.take(conversation.threadId)?.let { jumpReq = it to true } }
        LaunchedEffect(jumpReq, chatRows, openPlaced) {
            val (k, doFlash) = jumpReq ?: return@LaunchedEffect
            if (!openPlaced) return@LaunchedEffect
            val idx = chatRows.indexOfFirst { it is ChatRow.Msg && it.key == k }
            if (idx < 0) {
                if (hasOlder && !loadingOlder) { loadingOlder = true; msgLimit += 800; reload() }
                else if (!hasOlder && fullLoaded) { jumpReq = null; Toast.makeText(ctx, "That message isn't here anymore", Toast.LENGTH_SHORT).show() }
                return@LaunchedEffect
            }
            jumpReq = null
            val target = headerRows + idx
            runCatching {
                listState.scrollToItem(target)
                withTimeoutOrNull(500) { snapshotFlow { listState.layoutInfo }.first { l -> l.visibleItemsInfo.any { it.index == target } } }
                val l = listState.layoutInfo
                val it0 = l.visibleItemsInfo.firstOrNull { it.index == target }
                val vp = l.viewportEndOffset - l.viewportStartOffset
                if (it0 != null && vp > 0) listState.scrollBy(-((vp - it0.size) / 2f).coerceAtLeast(0f))
            }
            if (doFlash) { flashKey = k; delay(1600); if (flashKey == k) flashKey = null }
        }
        val bubbleExtras = remember(remoteReactions, showSearch, searchQuery, currentHit, flashKey, replyVer) {
            ChatBubbleExtras(remoteReactions, if (showSearch) searchQuery.trim() else "", currentHit, flashKey, replyVer, onJump = { k -> jumpReq = k to true })
        }
        CompositionLocalProvider(LocalChatExtras provides bubbleExtras) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp).graphicsLayer { alpha = if (positioned) 1f else 0f },
            // Newest message at the bottom, laid out from the bottom up: the chat opens already at the newest message on the
            // first frame (no scroll down), and when the keyboard opens or a photo loads the bottom stays put.
            reverseLayout = true,
            contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp)
        ) {
            // quick replies for the newest incoming message (SMS, MMS or group); a reaction text after it doesn't hide them
            val replyTarget = msgs.lastOrNull { Reactions.parse(it.body) == null }
            if (replyTarget != null && replyTarget.incoming) {
                item(key = "smart_replies", contentType = "smart") { SmartReplyRow(replyTarget, msgs) { sendText(it) } }
            }
            // Scheduled messages for this chat, shown in place like Google Messages
            if (!showSearch && mine.isNotEmpty()) {
                items(mine.asReversed(), key = { "sched_" + it.id }, contentType = { "sched" }) { sm -> ScheduledInChat(sm) { schedMsg = sm } }
            }
            items(chatRows, key = { it.key }, contentType = { it.type }) { row ->
                when (row) {
                    is ChatRow.Day -> DayHeader(row.label)
                    is ChatRow.Unread -> UnreadDivider()
                    is ChatRow.Msg -> {
                    val m = row.m
                    val i = row.i
                    val dayMsgs = row.day
                    val sameAsPrev = i > 0 && dayMsgs[i - 1].incoming == m.incoming
                    val mk = msgKey(m)
                    // Send / receive animation: a message that arrives while the chat is open rises in from its side, once
                    val appear = remember(mk) { androidx.compose.animation.core.Animatable(if (m.date >= openedAt - 1500 && mk !in appearedKeys) 0f else 1f) }
                    LaunchedEffect(mk) {
                        if (appear.value < 1f) { appearedKeys.add(mk); runCatching { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)) }; if (appear.value != 1f) runCatching { appear.snapTo(1f) } }
                    }
                    val pivotX = if (m.incoming != isRtl) 0f else 1f
                    Column(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(160))
                        .graphicsLayer { val p = appear.value; if (p < 1f) { alpha = p.coerceIn(0f, 1f); translationY = (1f - p) * 22.dp.toPx(); scaleX = 0.9f + 0.1f * p; scaleY = 0.9f + 0.1f * p; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, 1f) } }
                        .onGloballyPositioned { bubbleYs[mk] = it.positionInRoot().y }
                        .then(if (mk in selected) Modifier.clip(RoundedCornerShape(12.dp)).background(Brand.copy(alpha = .18f)) else Modifier)) {
                        if (i > 0) Spacer(Modifier.height(if (sameAsPrev) 2.dp else 10.dp))
                        // Group chats: who wrote it, above the first bubble of each run (like Google Messages)
                        if (isGroupChat && m.incoming && !m.address.isNullOrBlank() && (i == 0 || dayMsgs[i - 1].address != m.address || !dayMsgs[i - 1].incoming)) {
                            val who = rememberContactName(m.address)
                            Text(who, fontSize = 12.sp, color = avatarColor(m.address!!), fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp))
                        }
                        CompositionLocalProvider(LocalPhotoOpener provides photoOpener) { SwipeToReplyBubble(m, reactTick, onReply = { replyTo = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); if (selecting) selected = if (mk in selected) selected - mk else selected + mk else ctxMsg = m },
                            showMeta = i == dayMsgs.lastIndex || dayMsgs[i + 1].incoming != m.incoming || dayMsgs[i + 1].date - m.date > 300_000,
                            joinedAbove = i > 0 && dayMsgs[i - 1].incoming == m.incoming && m.date - dayMsgs[i - 1].date <= 300_000 && (!m.incoming || dayMsgs[i - 1].address == m.address),
                            joinedBelow = i < dayMsgs.lastIndex && dayMsgs[i + 1].incoming == m.incoming && dayMsgs[i + 1].date - m.date <= 300_000 && (!m.incoming || dayMsgs[i + 1].address == m.address),
                            onTap = if (selecting) ({ selected = if (mk in selected) selected - mk else selected + mk }) else if (m.failed && !m.isMms) ({ scope.launch(Dispatchers.IO) { SmsRepository.resend(ctx, m, address); withContext(Dispatchers.Main) { reload() } } }) else null) }
                    }
                    }
                }
            }
            if (pins.isNotEmpty()) item(key = "pinned_banner", contentType = "pins") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Tonal).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.PushPin, null, tint = OnTonal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(pins.last().body.ifBlank { "(media)" }, color = OnTonal, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (hasOlder && !showSearch) item(key = "load_older", contentType = "older") {
                Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    TextButton({ loadingOlder = true; msgLimit += 800; reload() }) { Text("Load older messages", color = Brand) }
                }
            }
        }
        }
        // Jump to the newest message when scrolled up (Google Messages' round arrow button); "↓ N new" when texts arrived meanwhile
        val farFromBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 4 } }
        val nearBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
        LaunchedEffect(nearBottom) { if (nearBottom) newBelow = 0 }
        AnimatedVisibility(positioned && (farFromBottom || newBelow > 0), Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 14.dp),
            enter = scaleIn(initialScale = 0.6f) + fadeIn(), exit = scaleOut(targetScale = 0.6f) + fadeOut()) {
            val jump: () -> Unit = {
                newBelow = 0
                scope.launch { runCatching { if (listState.firstVisibleItemIndex > 30) listState.scrollToItem(12); listState.animateScrollToItem(0) } }
            }
            if (newBelow > 0) {
                Surface(onClick = jump, shape = RoundedCornerShape(20.dp), color = Brand, contentColor = Color.White, shadowElevation = 2.dp) {
                    Row(Modifier.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Ms.KeyboardArrowDown, null, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(4.dp))
                        Text(if (newBelow == 1) "1 new" else "$newBelow new", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                SmallFloatingActionButton(onClick = jump,
                    shape = CircleShape, containerColor = MenuSurf, contentColor = TextPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp)) {
                    Icon(Ms.KeyboardArrowDown, "Jump to newest", modifier = Modifier.size(24.dp))
                }
            }
        }
        }
    }

    ctxMsg?.let { m ->
        MessageActionsOverlay(m, conversation, address, reactTick, bubbleYs[msgKey(m)],
            onReply = { replyTo = m },
            onResend = { scope.launch(Dispatchers.IO) { SmsRepository.resend(ctx, m, address); withContext(Dispatchers.Main) { reload() } } },
            onDelete = { scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m); withContext(Dispatchers.Main) { reload() } } },
            onSelect = { selected = setOf(msgKey(m)) },
            onDetails = { detailsMsg = m },
            onForward = { startForward(listOf(m)) },
            onChanged = { reactTick++ },
            onDismiss = { ctxMsg = null })
    }
    }
}


/** Bottom panels (+, gallery, emoji/GIF) slide up from the compose bar with a soft spring, and tuck away quickly. */
internal val PanelEnter: EnterTransition =
    expandVertically(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 520f), expandFrom = Alignment.Top) +
        fadeIn(androidx.compose.animation.core.tween(180))
internal val PanelExit: ExitTransition =
    shrinkVertically(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutLinearInEasing), shrinkTowards = Alignment.Top) +
        fadeOut(androidx.compose.animation.core.tween(120))


