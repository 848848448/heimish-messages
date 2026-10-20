package com.heimish.messages

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
fun ThreadScreen(conversation: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    var msgs by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf(DraftStore.get(ctx, conversation.threadId)) }
    val draftNow by rememberUpdatedState(draft)
    DisposableEffect(Unit) { onDispose { DraftStore.set(ctx, conversation.threadId, draftNow) } }
    var showDetails by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var ctxMsg by remember { mutableStateOf<Message?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaType by remember { mutableStateOf("image") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    // Text / photo shared in from another app (share sheet, sms: link without a number)
    LaunchedEffect(Unit) {
        if (!ShareState.isEmpty) {
            ShareState.text?.takeIf { it.isNotBlank() }?.let { t -> draft = if (draft.isBlank()) t else draft + "\n" + t }
            ShareState.uris.firstOrNull()?.let { u ->
                val mt = runCatching { ctx.contentResolver.getType(u) }.getOrNull() ?: ShareState.mime ?: ""
                pendingMediaUri = u
                pendingMediaType = when { mt.startsWith("video") -> "video"; mt.startsWith("audio") -> "audio"; else -> "image" }
            }
            ShareState.clear()
        }
    }
    var showSearch by remember { mutableStateOf(false) }
    var searchFilter by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var reactTick by remember { mutableIntStateOf(0) }
    var lastCount by remember { mutableIntStateOf(-1) }
    var sendingNow by remember { mutableStateOf(false) }
    var detailsMsg by remember { mutableStateOf<Message?>(null) }
    // Send to every member of the thread (groups), not just the address of the last message.
    var address by remember(conversation.threadId) { mutableStateOf(conversation.address) }
    LaunchedEffect(conversation.threadId) {
        address = withContext(Dispatchers.IO) { SmsRepository.threadAddress(ctx, conversation.threadId, conversation.address) }
    }
    DisposableEffect(conversation.threadId) {
        ActiveThread.id = conversation.threadId
        Notifications.cancelThread(ctx, conversation.threadId)
        onDispose { if (ActiveThread.id == conversation.threadId) ActiveThread.id = -1L }
    }

    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { SmsRepository.loadMessages(ctx, conversation.threadId) }
            val filteredMsgs = raw.filter { it.body.isNotBlank() || it.imageUri != null }
            val grew = filteredMsgs.size != msgs.size
            msgs = filteredMsgs
            if (grew || lastCount == -1) {
                lastCount = filteredMsgs.size
                if (filteredMsgs.isNotEmpty()) { delay(80); listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), 100000) }
            }
        }
    }

    fun doSend() {
        if (sendingNow) return  // a double tap used to send the same message twice
        val body = draft.trim()
        if (pendingMediaUri != null) {
            val uri = pendingMediaUri!!
            val mime = when (pendingMediaType) {
                "video" -> ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
                "audio" -> ctx.contentResolver.getType(uri)?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
                else -> null
            }
            sendingNow = true
            scope.launch(Dispatchers.IO) {
                val ok = SmsRepository.sendMms(ctx, address, uri, body, mime)
                withContext(Dispatchers.Main) {
                    sendingNow = false
                    if (ok) { draft = ""; pendingMediaUri = null; replyTo = null; SendSound.play(ctx); reload() }
                    else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (body.isEmpty()) return
        sendingNow = true
        val text = body
        draft = ""; replyTo = null
        scope.launch(Dispatchers.IO) {
            val ok = if (address.contains(";") && prefs.getBoolean("group_mms_send", true)) (SmsRepository.sendGroupText(ctx, address, text) || SmsRepository.sendSms(ctx, address, text)) else SmsRepository.sendSms(ctx, address, text)
            withContext(Dispatchers.Main) {
                sendingNow = false
                if (ok) { SendSound.play(ctx); reload() }
                else { if (draft.isEmpty()) draft = text; Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    var showQuick by remember { mutableStateOf(false) }
    if (showDetails) ChatDetailsDialog(conversation, msgs, { showDetails = false }) { showDetails = false; scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, conversation.threadId) }; onBack() }
    var showMedia by remember { mutableStateOf(false) }
    if (showQuick) {
        val qr = QuickReplies.get(ctx)
        AlertDialog(onDismissRequest = { showQuick = false }, containerColor = Surf, title = { Text("Quick replies", color = TextPrimary) },
            text = { Column { qr.forEach { r -> Text(r, Modifier.fillMaxWidth().clickable { draft = if (draft.isBlank()) r else draft + " " + r; showQuick = false }.padding(vertical = 12.dp), color = TextPrimary, fontSize = 16.sp) } } },
            confirmButton = { TextButton({ showQuick = false }) { Text("Close") } })
    }
    if (showMedia) MediaDialog(msgs) { showMedia = false }
    fun doSchedule(at: Long) {
        val body = draft.trim()
        ScheduledSendReceiver.schedule(ctx, address, body, at)
        draft = ""
        Toast.makeText(ctx, "Scheduled for " + SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(at)), Toast.LENGTH_LONG).show()
    }
    fun pickCustomTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(ctx, { _, y, mo, d ->
            TimePickerDialog(ctx, { _, h, mi ->
                val t = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0) }
                if (t.timeInMillis <= System.currentTimeMillis()) {
                    Toast.makeText(ctx, "Pick a future time", Toast.LENGTH_SHORT).show()
                } else {
                    val body = draft.trim()
                    if (body.isEmpty()) {
                        Toast.makeText(ctx, "Type a message first", Toast.LENGTH_SHORT).show()
                    } else {
                        ScheduledSendReceiver.schedule(ctx, address, body, t.timeInMillis)
                        draft = ""
                        Toast.makeText(ctx, "Scheduled for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(t.time), Toast.LENGTH_LONG).show()
                    }
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
    }

    fun pickScheduleTime() {
        if (draft.trim().isEmpty()) { Toast.makeText(ctx, "Type a message first", Toast.LENGTH_SHORT).show(); return }
        fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
        val opts = ArrayList<Pair<String, Long>>()
        opts.add("In 1 hour" to System.currentTimeMillis() + 3_600_000L)
        if (at(0, 20) > System.currentTimeMillis() + 600_000L) opts.add("Tonight, 8:00 PM" to at(0, 20))
        opts.add("Tomorrow morning, 8:00 AM" to at(1, 8))
        opts.add("Tomorrow afternoon, 1:00 PM" to at(1, 13))
        val labels = (opts.map { it.first } + "Pick date and time…").toTypedArray()
        android.app.AlertDialog.Builder(ctx).setTitle("Schedule send").setItems(labels) { _, i -> if (i < opts.size) doSchedule(opts[i].second) else pickCustomTime() }.show()
    }

    fun startRecording() {
        val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96000)
            r.setAudioSamplingRate(44100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; recordFile = f; isRecording = true
        }.onFailure {
            runCatching { r.release() }
            Toast.makeText(ctx, "Recording failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecording(attach: Boolean) {
        val r = recorder; val f = recordFile
        recorder = null; isRecording = false
        runCatching { r?.stop() }
        runCatching { r?.release() }
        if (attach && f != null && f.exists() && f.length() > 0) {
            pendingMediaUri = Uri.fromFile(f); pendingMediaType = "audio"
        } else {
            f?.delete()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { uri -> pendingMediaUri = uri; pendingMediaType = "image"; showAttach = false } }
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
        if (ok) cameraUri?.let { pendingMediaUri = it; pendingMediaType = "image"; showAttach = false }
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
        if (granted) startRecording() else Toast.makeText(ctx, "Microphone permission needed", Toast.LENGTH_SHORT).show()
    }
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) {
            runCatching {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc = lm.getProviders(true).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                if (loc != null) {
                    val link = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                    draft = if (draft.isBlank()) link else "$draft\n$link"
                } else Toast.makeText(ctx, "Location unavailable", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(ctx, "Location unavailable", Toast.LENGTH_SHORT).show() }
        } else Toast.makeText(ctx, "Location permission needed", Toast.LENGTH_SHORT).show()
        showAttach = false
    }

    BackHandler { onBack() }
    val threadTick = rememberProviderTick()
    LaunchedEffect(threadTick) {
        if (threadTick > 1) delay(120)
        reload()
        withContext(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, conversation.threadId) }
    }
    LaunchedEffect(isRecording) {
        recordSeconds = 0
        while (isRecording) { delay(1000); recordSeconds++ }
    }
    DisposableEffect(Unit) { onDispose { if (isRecording) stopRecording(false) } }

    detailsMsg?.let { dm -> MessageDetailsDialog(dm, conversation.displayName, address) { detailsMsg = null } }

    // ── Message context menu (long-press) ──
    if (ctxMsg != null) {
        val m = ctxMsg!!
        val key = msgKey(m)
        val starred = StarStore.isStarred(ctx, key)
        val failed = m.failed
        AlertDialog(
            onDismissRequest = { ctxMsg = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(m.body.take(60).ifBlank { "Message" }, fontSize = 14.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                            Box(Modifier.size(44.dp).clip(CircleShape).background(SearchBg).clickable {
                                val cur = ReactionStore.get(ctx, key)
                                ReactionStore.set(ctx, key, if (cur == emoji) null else emoji)
                                reactTick++; ctxMsg = null
                            }, contentAlignment = Alignment.Center) {
                                Text(emoji, fontSize = 22.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = DivLt, thickness = .5.dp)
                    Spacer(Modifier.height(4.dp))
                    if (failed) {
                        MenuBtn(Icons.Default.Refresh, "Resend", Brand) {
                            scope.launch(Dispatchers.IO) {
                                SmsRepository.resend(ctx, m, address)
                            }
                            ctxMsg = null; reload()
                        }
                    }
                    MenuBtn(Icons.Default.Reply, "Reply") { replyTo = m; ctxMsg = null }
                    MenuBtn(Icons.Default.ContentCopy, "Copy") {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.body))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Forward, "Forward") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, m.body) }, "Forward"))
                        ctxMsg = null
                    }
                    MenuBtn(Icons.Default.PushPin, if (MsgPinStore.has(ctx, key)) "Unpin" else "Pin") {
                        MsgPinStore.toggle(ctx, key); reactTick++; ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Star, if (starred) "Unstar" else "Star", Amber) {
                        StarStore.toggle(ctx, key); reactTick++
                        Toast.makeText(ctx, if (starred) "Unstarred" else "Starred", Toast.LENGTH_SHORT).show(); ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Info, "Details") { detailsMsg = m; ctxMsg = null }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m); withContext(Dispatchers.Main) { reload() } }
                        ctxMsg = null; reload()
                    }
                }
            },
            confirmButton = { TextButton({ ctxMsg = null }) { Text("Cancel", color = Brand) } }
        )
    }

    Scaffold(containerColor = Bg,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextSecond),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat…", color = TextHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), modifier = Modifier.fillMaxWidth())
                    } else Row(Modifier.clickable { showDetails = true }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(conversation.displayName, address, 40.dp, 16.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                            if (address.contains(";")) Text("Group · ${address.split(";").size} people", fontSize = 12.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (showSearch) {
                        TextButton({ searchFilter = (searchFilter + 1) % 3 }) { Text(listOf("All", "Photos", "Links")[searchFilter], color = Brand) }
                        IconButton({ showSearch = false; searchQuery = ""; searchFilter = 0 }) { Icon(Icons.Default.Close, "Close search") }
                    } else {
                        if (!address.contains(";")) {
                            IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${address}"))) }) { Icon(Icons.Default.Phone, "Call") }
                        }
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf), shape = RoundedCornerShape(16.dp)) {
                                DropdownMenuItem({ Text("Search", color = TextPrimary) }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Details", color = TextPrimary) }, { showMenu = false; showDetails = true }, leadingIcon = { Icon(Icons.Default.Info, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Quick replies", color = TextPrimary) }, { showMenu = false; showQuick = true }, leadingIcon = { Icon(Icons.Default.Reply, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Media and links", color = TextPrimary) }, { showMenu = false; showMedia = true }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Add to home screen", color = TextPrimary) }, { showMenu = false; pinChatShortcut(ctx, conversation) }, leadingIcon = { Icon(Icons.Default.AddToHomeScreen, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Schedule send", color = TextPrimary) }, { showMenu = false; pickScheduleTime() }, leadingIcon = { Icon(Icons.Default.Schedule, null, tint = TextSecond) })
                                DropdownMenuItem({ Text(if (Mute.isMuted(ctx, conversation.threadId)) "Unmute" else "Mute notifications", color = TextPrimary) }, {
                                    showMenu = false
                                    if (Mute.isMuted(ctx, conversation.threadId)) { Mute.clear(ctx, conversation.threadId); Toast.makeText(ctx, "Unmuted", Toast.LENGTH_SHORT).show() }
                                    else android.app.AlertDialog.Builder(ctx).setTitle("Mute notifications").setItems(arrayOf("1 hour", "8 hours", "24 hours", "Always")) { _, i -> Mute.setFor(ctx, conversation.threadId, longArrayOf(3_600_000L, 28_800_000L, 86_400_000L, 0L)[i]); Toast.makeText(ctx, "Muted", Toast.LENGTH_SHORT).show() }.show()
                                }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Remind me", color = TextPrimary) }, {
                                    showMenu = false
                                    android.app.AlertDialog.Builder(ctx).setTitle("Remind me").setItems(arrayOf("In 1 hour", "In 3 hours", "Tomorrow morning")) { _, i ->
                                        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 9); set(java.util.Calendar.MINUTE, 0) }
                                        RemindReceiver.set(ctx, conversation, when (i) { 0 -> System.currentTimeMillis() + 3_600_000L; 1 -> System.currentTimeMillis() + 10_800_000L; else -> cal.timeInMillis })
                                        Toast.makeText(ctx, "Reminder set", Toast.LENGTH_SHORT).show() }.show()
                                }, leadingIcon = { Icon(Icons.Default.Notifications, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Export chat", color = TextPrimary) }, { showMenu = false; exportChat(ctx, conversation, msgs) }, leadingIcon = { Icon(Icons.Default.Share, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Notification sound", color = TextPrimary) }, {
                                    showMenu = false
                                    toneL.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
                                }, leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = TextSecond) })
                                if (moreOpen) DropdownMenuItem({ Text("Label", color = TextPrimary) }, {
                                    showMenu = false
                                    val et = android.widget.EditText(ctx); et.setText(LabelStore.get(ctx, conversation.threadId) ?: ""); et.hint = "Label (empty to remove)"
                                    android.app.AlertDialog.Builder(ctx).setTitle("Label this chat").setView(et).setPositiveButton("Save") { _, _ -> LabelStore.set(ctx, conversation.threadId, et.text.toString()); Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show() }.setNegativeButton("Cancel", null).show()
                                }, leadingIcon = { Icon(Icons.Default.Label, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Block & report spam", color = TextPrimary) }, {
                                    showMenu = false; BlockedStore.block(ctx, address)
                                    Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Red) })
                                DropdownMenuItem({ Text("Delete conversation", color = TextPrimary) }, {
                                    showMenu = false; scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, conversation.threadId) }
                                    Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Red) })
                                DropdownMenuItem({ Text(if (moreOpen) "Less" else "More", color = TextPrimary) }, { moreOpen = !moreOpen }, leadingIcon = { Icon(if (moreOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecond) })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = Bg) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (replyTo != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Brand))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reply to ${if (replyTo!!.incoming) conversation.displayName else "yourself"}", fontSize = 12.sp, color = Brand, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(replyTo!!.body.take(60).ifBlank { "📷 Media" }, fontSize = 13.sp, color = TextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ replyTo = null }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remove reply", Modifier.size(18.dp), tint = TextHint) }
                        }
                    }
                    if (isRecording) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)).background(SearchBg).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFD93025)))
                            Spacer(Modifier.width(10.dp))
                            Text(String.format("%d:%02d", recordSeconds / 60, recordSeconds % 60), fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(10.dp))
                            Text("Recording…", fontSize = 14.sp, color = TextHint, modifier = Modifier.weight(1f))
                            TextButton(onClick = { stopRecording(false) }) { Text("Discard", color = Red) }
                            TextButton(onClick = { stopRecording(true) }) { Text("Done", color = Brand, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    if (pendingMediaUri != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (pendingMediaType) {
                                "image" -> AsyncImage(pendingMediaUri, "preview", Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                "video" -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                else -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Brand), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Mic, null, tint = OnBrand, modifier = Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(when (pendingMediaType) { "image" -> "Photo ready to send"; "video" -> "Video ready to send"; else -> "Voice note ready to send" }, fontSize = 14.sp, color = TextSecond, modifier = Modifier.weight(1f))
                            IconButton({ pendingMediaUri = null }) { Icon(Icons.Default.Close, "Remove", tint = TextHint) }
                        }
                    }
                    if (draft.length > 100) {
                        val gsm = draft.all { it.code < 128 }
                        val per = if (gsm) (if (draft.length <= 160) 160 else 153) else (if (draft.length <= 70) 70 else 67)
                        Text("${draft.length} chars · ${(draft.length + per - 1) / per} SMS", Modifier.fillMaxWidth().padding(end = 22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 11.sp, color = TextHint)
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { showAttach = !showAttach; showEmoji = false }, Modifier.size(44.dp).clip(CircleShape).background(Tonal)) {
                            Icon(if (showAttach) Icons.Default.Close else Icons.Default.Add, "Attach", tint = OnTonal, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(24.dp)).background(SearchBg).padding(horizontal = 4.dp), verticalAlignment = Alignment.Bottom) {
                            IconButton({ showEmoji = !showEmoji; showAttach = false }, Modifier.size(40.dp)) { Icon(Icons.Default.EmojiEmotions, "Emoji", tint = TextHint, modifier = Modifier.size(22.dp)) }
                            androidx.compose.foundation.text.BasicTextField(
                                draft, { draft = it }, Modifier.weight(1f).padding(vertical = 11.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp, lineHeight = 22.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { doSend() }),
                                maxLines = 5,
                                decorationBox = { inner -> Box { if (draft.isEmpty()) Text(if (address.contains(";")) "Group message" else "Text message", color = TextHint, fontSize = 16.sp); inner() } }
                            )
                            IconButton({ imagePicker.launch("image/*") }, Modifier.size(40.dp)) { Icon(Icons.Default.Image, "Photo", tint = TextHint, modifier = Modifier.size(22.dp)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        val hasCont = draft.isNotBlank() || pendingMediaUri != null
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .background(if (hasCont) Brand else SearchBg)
                                .clickable { if (hasCont) doSend() else audioPerm.launch(android.Manifest.permission.RECORD_AUDIO) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(if (hasCont) Icons.Default.Send else Icons.Default.Mic,
                                if (hasCont) "Send" else "Voice note",
                                tint = if (hasCont) OnBrand else Brand,
                                modifier = Modifier.size(22.dp))
                        }
                    }
                    AnimatedVisibility(showAttach, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        LazyVerticalGrid(
                            GridCells.Fixed(4),
                            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(SheetBg).padding(horizontal = 16.dp, vertical = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            gridItems(listOf(
                                Triple(Icons.Default.Image, "Gallery") { imagePicker.launch("image/*") },
                                Triple(Icons.Default.PhotoCamera, "Camera") {
                                    val uri = runCatching {
                                        val values = android.content.ContentValues().apply {
                                            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "message_${System.currentTimeMillis()}.jpg")
                                            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                        }
                                        ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                    }.getOrNull()
                                    if (uri != null) { cameraUri = uri; runCatching { cameraLauncher.launch(uri) } }
                                    else Toast.makeText(ctx, "Camera unavailable", Toast.LENGTH_SHORT).show()
                                },
                                Triple(Icons.Default.Videocam, "Video") { videoPicker.launch("video/*") },
                                Triple(Icons.Default.Mic, "Voice") { showAttach = false; audioPerm.launch(android.Manifest.permission.RECORD_AUDIO) },
                                Triple(Icons.Default.InsertDriveFile, "File") { filePicker.launch("*/*") },
                                Triple(Icons.Default.LocationOn, "Location") { locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) },
                                Triple(Icons.Default.Person, "Contact") { contactPicker.launch(null) },
                                Triple(Icons.Default.Schedule, "Schedule") { showAttach = false; pickScheduleTime() }
                            )) { (icon, label, action) -> AttachBtn(icon, label, action) }
                        }
                    }
                    AnimatedVisibility(showEmoji, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        EmojiPicker { draft += it }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(Color(ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getInt("chat_bg", 0))).padding(pad).padding(horizontal = 10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 10.dp)
        ) {
            val displayMsgs = if (showSearch && (searchQuery.isNotBlank() || searchFilter != 0)) msgs.filter { (searchQuery.isBlank() || it.body.contains(searchQuery, true)) && (searchFilter == 0 || (searchFilter == 1 && it.imageUri != null) || (searchFilter == 2 && isLinkMsg(it))) } else msgs
            val grouped = displayMsgs.groupByDate()
            val pins = if (showSearch) emptyList() else msgs.filter { MsgPinStore.has(ctx, msgKey(it)) }
            if (pins.isNotEmpty()) item(key = "pinned_banner") {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Tonal).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PushPin, null, tint = OnTonal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(pins.last().body.ifBlank { "(media)" }, color = OnTonal, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            grouped.forEach { (day, dayMsgs) ->
                item(key = "day_$day") { DayHeader(day) }
                itemsIndexed(dayMsgs, key = { _, m -> msgKey(m) }) { i, m ->
                    val sameAsPrev = i > 0 && dayMsgs[i - 1].incoming == m.incoming
                    Column {
                        if (i > 0) Spacer(Modifier.height(if (sameAsPrev) 2.dp else 10.dp))
                        SwipeToReplyBubble(m, reactTick, onReply = { replyTo = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctxMsg = m }, showMeta = i == dayMsgs.lastIndex || dayMsgs[i + 1].incoming != m.incoming || dayMsgs[i + 1].date - m.date > 300_000)
                    }
                }
            }
            if (msgs.isNotEmpty() && msgs.last().incoming) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("OK", "👍", "Thanks!", "On my way").forEach { reply ->
                            Surface(
                                onClick = { draft = reply; doSend() },
                                shape = RoundedCornerShape(20.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, DivLt)
                            ) {
                                Text(reply, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp, color = Brand, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Swipe to reply wrapper ───────────────────────────────────────────────────
@Composable
fun SwipeToReplyBubble(m: Message, reactTick: Int, onReply: () -> Unit, onLongClick: () -> Unit, showMeta: Boolean = true) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = 100f
    Box(Modifier.fillMaxWidth().pointerInput(m.id) {
        detectHorizontalDragGestures(
            onDragEnd = { if (abs(offsetX) > threshold) onReply(); offsetX = 0f },
            onHorizontalDrag = { _, dx ->
                offsetX = if (m.incoming) (offsetX + dx).coerceIn(0f, 200f) else (offsetX + dx).coerceIn(-200f, 0f)
            }
        )
    }) {
        if (abs(offsetX) > 20f) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(Icons.Default.Reply, null, tint = Brand.copy(alpha = (abs(offsetX) / threshold).coerceAtMost(1f)), modifier = Modifier.size(24.dp))
            }
        }
        Box(Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }) {
            MessageBubble(m, reactTick, onLongClick, showMeta)
        }
    }
}

// ── Attachment panel button ─────────────────────────────────────────────────
@Composable
fun AttachBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 60.dp, height = 48.dp).clip(RoundedCornerShape(16.dp)).background(AttachSurf), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = TextSecond, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 11.sp, color = TextSecond, maxLines = 1)
    }
}

@Composable
fun MenuBtn(icon: ImageVector, label: String, tint: Color = TextPrimary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (tint == TextPrimary) TextSecond else tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (tint == Red) Red else TextPrimary)
    }
}

// ━━━━━━━━━━━━━━━━ EMOJI PICKER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    val emojis = listOf(
        "😀","😂","🥹","😍","🤔","😎","🙏","❤️","👍","👎",
        "🔥","🎉","😢","😡","🤣","😊","🥰","😘","🤗","😏",
        "😭","😤","🤯","🫡","✨","💪","🙄","😴","🤝","👋",
        "💯","🎁","📱","✅","❌","⭐","🌙","☀️","🍕","🍔",
        "☕","🍎","🌹","🕊️","📖","✍️","😇","🤩","😜","🥺",
        "🙃","😬","🤫","🫠","💔","💕","💖","👏","🤞","✌️",
        "🤟","🫵","👀","😴","🎂","🕯️","🍷","🥖","🧀","🍇"
    )
    Surface(color = SheetBg, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
            gridItems(emojis) { e ->
                Box(Modifier.size(44.dp).clip(CircleShape).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
internal val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+|[\\w.+-]+@[\\w-]+\\.[\\w.-]+[A-Za-z]|\\+?\\d[\\d ().-]{7,}\\d)", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, reactTick: Int, onLongClick: () -> Unit, showMeta: Boolean = true) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val isIn = m.incoming
    val bubbleColor = remember(reactTick) { Color(prefs.getInt("bubble_color", BubbleOut.toArgb())) }
    val outColor = if (isIn) BubbleIn else bubbleColor
    val customOut = prefs.contains("bubble_color")
    val outText = if (isIn) TextPrimary else if (customOut) Color.White else BubbleOutText
    val outSub = if (isIn) TextHint else if (customOut) Color.White.copy(.72f) else BubbleOutSub
    val linkColor = if (isIn) Brand else if (customOut) Color(0xFFCDE1FF) else BubbleOutLink
    val previewBg = if (isIn) (if (D) Color(0xFF5F6368) else Color(0xFFD8DEE9)) else if (customOut) BrandDk else (if (D) Color(0xFF7CACF8) else BrandDk)
    val textSize = when (prefs.getString("text_size", "default")) {
        "small" -> 13.sp; "large" -> 17.sp; "largest" -> 19.sp; else -> 15.sp
    }
    val previewMode = prefs.getString("preview_mode", "links") ?: "links"
    val reaction = remember(reactTick) { ReactionStore.get(ctx, msgKey(m)) }
    val starred = remember(reactTick) { StarStore.isStarred(ctx, msgKey(m)) }
    val failed = m.failed
    val sending = m.sending
    var warnUrl by remember { mutableStateOf<String?>(null) }
    warnUrl?.let { u ->
        AlertDialog(onDismissRequest = { warnUrl = null }, icon = { Icon(Icons.Default.GppMaybe, null, tint = Red) },
            title = { Text("Open this link?") },
            text = { Text("This link came from a number that isn't in your contacts and looks suspicious:\n\n$u") },
            confirmButton = { TextButton({ warnUrl = null; runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }) { Text("Open anyway") } },
            dismissButton = { TextButton({ warnUrl = null }) { Text("Cancel") } })
    }
    var viewing by remember { mutableStateOf(false) }
    if (viewing && m.imageUri != null) PhotoViewer(m.imageUri) { viewing = false }
    val cr = prefs.getInt("bubble_corner", 20).dp
    val tail = if (cr < 5.dp) cr else 5.dp
    val shape = RoundedCornerShape(
        topStart = cr, topEnd = cr,
        bottomEnd = if (isIn) cr else tail,
        bottomStart = if (isIn) tail else cr
    )
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
            horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
        ) {
            if (!isIn) Spacer(Modifier.width(56.dp))
            Column(horizontalAlignment = if (isIn) Alignment.Start else Alignment.End) {
            Box(Modifier.widthIn(max = 300.dp).clip(shape).background(outColor)) {
                Column {
                    if (m.imageUri != null) {
                        val mime = m.mediaMime ?: "image/"
                        when {
                            mime.startsWith("video/") -> Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            mime.startsWith("audio/") -> Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(outText.copy(.18f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, null, tint = outText, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Voice note", color = outText, fontSize = textSize, fontWeight = FontWeight.Medium)
                                    Text("Tap to play", color = outSub, fontSize = 11.sp)
                                }
                            }
                            else -> AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 240.dp).clickable { viewing = true }.clip(
                                if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            ), contentScale = ContentScale.Crop)
                        }
                    }
                    if (m.body.isNotBlank()) {
                        val urls = URL_REGEX.findAll(m.body).toList()
                        if (urls.isEmpty()) {
                            Text(m.body, color = outText, fontSize = textSize, lineHeight = (textSize.value + 6).sp,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 9.dp, bottom = 9.dp))
                        } else {
                            val annotated = buildAnnotatedString {
                                var last = 0
                                urls.forEach { match ->
                                    withStyle(SpanStyle(color = outText)) { append(m.body.substring(last, match.range.first)) }
                                    pushStringAnnotation("URL", match.value)
                                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(match.value) }
                                    pop()
                                    last = match.range.last + 1
                                }
                                if (last < m.body.length) withStyle(SpanStyle(color = outText)) { append(m.body.substring(last)) }
                            }
                            ClickableText(annotated, style = androidx.compose.ui.text.TextStyle(fontSize = textSize, lineHeight = (textSize.value + 6).sp),
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 9.dp, bottom = 9.dp),
                                onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { a -> val v = a.item
                                if (v.startsWith("http", true) && isIn && LinkSafety.shouldWarn(ctx, m, v)) { warnUrl = v; return@let }
                                runCatching { ctx.startActivity(if (v.startsWith("http", true)) Intent(Intent.ACTION_VIEW, Uri.parse(v)) else if (v.contains("@")) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + v)) else Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + v.filter { c -> c.isDigit() || c == '+' }))) } } })
                            if (previewMode != "none" && urls.any { it.value.startsWith("http", true) }) {
                                val url = urls.first { it.value.startsWith("http", true) }.value
                                val domain = try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url }
                                Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(previewBg)
                                    .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Language, null, tint = if (isIn) TextSecond else outSub, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(domain, fontSize = 12.sp, color = if (isIn) TextSecond else outSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
                if (showMeta || failed || sending || starred) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (failed) {
                            Icon(Icons.Default.ErrorOutline, "Failed", tint = Color(0xFFD93025), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Not sent · hold to retry", fontSize = 11.sp, color = Color(0xFFD93025), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (starred) {
                            Icon(Icons.Default.Star, null, tint = Amber, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(msgTime(m.date), fontSize = 11.sp, color = TextHint)
                        if (!isIn && !failed) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                when {
                                    sending -> Icons.Default.Schedule
                                    m.delivered -> Icons.Default.DoneAll
                                    else -> Icons.Default.Check
                                },
                                when { sending -> "Sending…"; m.delivered -> "Delivered"; else -> "Sent" },
                                tint = TextHint, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
            if (isIn) Spacer(Modifier.width(56.dp))
        }
        if (reaction != null) {
            Box(
                Modifier.align(if (isIn) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(start = if (isIn) 6.dp else 0.dp, end = if (isIn) 0.dp else 6.dp)
                    .offset(y = 6.dp)
                    .clip(CircleShape).background(Surf).border(1.dp, DivLt, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            ) { Text(reaction, fontSize = 13.sp) }
        }
    }
}

