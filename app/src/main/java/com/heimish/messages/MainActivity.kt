package com.heimish.messages

import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// ── Google Messages M3E colours ─────────────────────────────────────────────
private val Brand       = Color(0xFF0B57D0)
private val BrandDk     = Color(0xFF0842A0)
private val BrandLt     = Color(0xFFD3E3FD)
private val BrandLt2    = Color(0xFFEDF2FA)
private val BubbleIn    = Color(0xFFE3E3E3)
private val BubbleOut   = Color(0xFF0B57D0)
private val BgSurf      = Color(0xFFF6F8FC)
private val Surf        = Color.White
private val TextDk      = Color(0xFF1F1F1F)
private val TextMeta    = Color(0xFF444746)
private val TextMetaLt  = Color(0xFF747775)
private val DivClr      = Color(0xFFE8EAED)
private val Red         = Color(0xFFB3261E)

private val GMColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = Brand, background = BgSurf,
    surface = Surf, onSurface = TextDk, outline = DivClr,
    surfaceVariant = BrandLt2
)

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BrandLt.toArgb()
        SmsSyncService.start(this)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNow(this)
        setContent {
            MaterialTheme(colorScheme = GMColors) {
                Surface(Modifier.fillMaxSize(), color = BrandLt) {
                    AppRoot(isDefaultSmsApp(this)) { requestDefault() }
                }
            }
        }
    }
    private fun requestDefault() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS))
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        } else {
            @Suppress("DEPRECATION")
            roleLauncher.launch(Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName))
        }
    }
}
fun isDefaultSmsApp(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

// ━━━━━━━━━━━━ NAVIGATION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
sealed class Screen {
    data object List : Screen()
    data class Chat(val conv: Conversation) : Screen()
    data object NewConv : Screen()
    data object Settings : Screen()
    data object Admin : Screen()
}

@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = Permissions.granted(ctx) }
    LaunchedEffect(Unit) { if (!hasPerms) permL.launch(Permissions.ALL) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPerms { permL.launch(Permissions.ALL) }
        else -> when (val s = screen) {
            Screen.List    -> ConvListScreen({ screen = Screen.Chat(it) }, { screen = Screen.NewConv }, { screen = Screen.Settings }, { screen = Screen.Admin })
            is Screen.Chat -> ThreadScreen(s.conv, { screen = Screen.List })
            Screen.NewConv -> NewConvScreen({ screen = Screen.Chat(it) }, { screen = Screen.List })
            Screen.Settings-> SettingsScreen { screen = Screen.List }
            Screen.Admin   -> AdminScreen { screen = Screen.List }
        }
    }
}

// ━━━━━━━━━━━━ SETUP ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable fun SetupScreen(onReq: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Message, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Heimish Messages", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Set as your default messaging app.", fontSize = 15.sp, color = Color.White.copy(.85f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onReq, colors = ButtonDefaults.buttonColors(Surf, Brand), shape = RoundedCornerShape(50)) { Text("Set as Default", fontWeight = FontWeight.Bold) }
        }
    }
}
@Composable fun SetupPerms(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Permissions Required", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(28.dp))
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(Surf, Brand), shape = RoundedCornerShape(50)) { Text("Allow Access", fontWeight = FontWeight.Bold) }
        }
    }
}

// ━━━━━━━━━━━━ CONVERSATION LIST ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { list = SmsRepository.loadConversations(ctx) }
    LaunchedEffect(Unit) { while (true) { delay(5000); list = SmsRepository.loadConversations(ctx) } }

    val filtered = if (search.isBlank()) list else list.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) }

    Scaffold(containerColor = BrandLt,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandLt, titleContentColor = TextDk, actionIconContentColor = TextDk),
                title = {
                    if (showSearch) TextField(search, { search = it }, placeholder = { Text("Search…", color = TextMetaLt) }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand), modifier = Modifier.fillMaxWidth())
                    else Text("Messages", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                },
                actions = {
                    IconButton({ showSearch = !showSearch; if (!showSearch) search = "" }) { Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, null) }
                    if (!showSearch) {
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(showMenu, { showMenu = false }) {
                                DropdownMenuItem({ Text("Settings") }, { showMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                                DropdownMenuItem({ Text("Admin Panel") }, { showMenu = false; onAdmin() }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null) })
                                DropdownMenuItem({ Text("Mark all read") }, {
                                    showMenu = false
                                    list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) }
                                    list = SmsRepository.loadConversations(ctx)
                                    Toast.makeText(ctx, "All marked as read", Toast.LENGTH_SHORT).show()
                                }, leadingIcon = { Icon(Icons.Default.DoneAll, null) })
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = Brand, contentColor = Color.White, shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Filled.Chat, null) }, text = { Text("Start chat", fontWeight = FontWeight.SemiBold) })
        }
    ) { pad ->
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, tint = TextMetaLt, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (search.isBlank()) "No messages yet" else "No results", color = TextMetaLt)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
                items(filtered, key = { it.threadId }) { conv ->
                    SwipeableConvRow(conv, onOpen, ctx) { list = SmsRepository.loadConversations(ctx) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConvRow(c: Conversation, onOpen: (Conversation) -> Unit, ctx: Context, onRefresh: () -> Unit) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxWidth().background(if (offsetX < -80) Red else BrandLt)) {
        // Swipe indicator
        if (offsetX < -20) {
            Box(Modifier.fillMaxHeight().width(80.dp).align(Alignment.CenterEnd).padding(end = 20.dp), contentAlignment = Alignment.Center) {
                Icon(if (offsetX < -80) Icons.Default.Delete else Icons.Default.Archive, null, tint = Color.White)
            }
        }
        Row(
            Modifier.offset { IntOffset(offsetX.roundToInt(), 0) }.fillMaxWidth().background(Surf).clickable { onOpen(c) }
                .pointerInput(c.threadId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -150) {
                                SmsRepository.deleteThread(ctx, c.threadId)
                                onRefresh()
                                android.widget.Toast.makeText(ctx, "Deleted", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (offsetX < -80) {
                                SmsRepository.markThreadRead(ctx, c.threadId)
                                onRefresh()
                                android.widget.Toast.makeText(ctx, "Archived", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dx -> offsetX = (offsetX + dx).coerceIn(-250f, 0f) }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(avatarColor(c.address)), contentAlignment = Alignment.Center) {
                Text((c.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(shortTime(c.date), fontSize = 12.sp, color = if (c.unread) Brand else TextMetaLt)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.snippet, fontSize = 14.sp, color = if (c.unread) TextDk else TextMetaLt, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (c.unread) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(10.dp).clip(CircleShape).background(Brand)) }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━ NEW CONVERSATION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConvScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<Pair<String, String>>()) }

    LaunchedEffect(Unit) { contacts = SmsRepository.loadContactsList(ctx) }

    val filtered = if (to.isBlank()) contacts else contacts.filter { it.first.contains(to, true) || it.second.contains(to, true) }

    Scaffold(containerColor = BrandLt,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandLt, titleContentColor = TextDk, navigationIconContentColor = TextDk),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("New conversation") }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(to, { to = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("To: name or number") }, singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Phone),
                keyboardActions = KeyboardActions(onDone = {
                    if (to.isNotBlank()) {
                        val c = Conversation(threadId = to.hashCode().toLong(), address = to.trim(), displayName = contacts.find { it.second == to.trim() }?.first ?: to.trim(), snippet = "", date = System.currentTimeMillis(), unread = false)
                        onOpen(c)
                    }
                })
            )
            LazyColumn(Modifier.clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
                items(filtered) { (name, number) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val c = Conversation(threadId = number.hashCode().toLong(), address = number, displayName = name, snippet = "", date = System.currentTimeMillis(), unread = false)
                        onOpen(c)
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(number)), contentAlignment = Alignment.Center) {
                            Text((name.firstOrNull()?.uppercaseChar() ?: '#').toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(number, fontSize = 13.sp, color = TextMetaLt) }
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━ THREAD / CHAT ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(conversation: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msgs by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    var searchQ by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var ctxMsg by remember { mutableStateOf<Message?>(null) }

    fun reload() { msgs = SmsRepository.loadMessages(ctx, conversation.threadId); scope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) } }
    fun doSend() {
        val body = draft.trim(); if (body.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendSms(ctx, conversation.address, body)
            withContext(Dispatchers.Main) { if (ok) { draft = ""; keyboard?.hide(); reload() } else Toast.makeText(ctx, "Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    LaunchedEffect(Unit) { reload(); SmsRepository.markThreadRead(ctx, conversation.threadId) }
    LaunchedEffect(Unit) { while (true) { delay(3000); reload() } }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch(Dispatchers.IO) { SmsRepository.sendMms(ctx, conversation.address, it); withContext(Dispatchers.Main) { reload() } } }
    }

    // Context menu dialog
    if (ctxMsg != null) {
        val m = ctxMsg!!
        AlertDialog(onDismissRequest = { ctxMsg = null },
            title = { Text("Message", fontWeight = FontWeight.SemiBold) },
            text = { Text(m.body.take(100) + if (m.body.length > 100) "…" else "", color = TextMeta) },
            confirmButton = {},
            dismissButton = {
                Column {
                    TextButton({ val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("msg", m.body)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); ctxMsg = null }) { Text("Copy") }
                    TextButton({ scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m.id) }; ctxMsg = null; reload() }) { Text("Delete", color = Red) }
                    TextButton({ ctxMsg = null }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(containerColor = BrandLt,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandLt, titleContentColor = TextDk, navigationIconContentColor = TextDk, actionIconContentColor = TextDk),
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = {
                    if (showSearch) {
                        TextField(searchQ, { searchQ = it }, placeholder = { Text("Search…") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand), modifier = Modifier.fillMaxWidth())
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(conversation.address)), contentAlignment = Alignment.Center) {
                                Text((conversation.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column { Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(conversation.address, fontSize = 12.sp, color = TextMetaLt) }
                        }
                    }
                },
                actions = {
                    if (showSearch) { IconButton({ showSearch = false; searchQ = "" }) { Icon(Icons.Default.Close, null) } }
                    else {
                        IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}"))) }) { Icon(Icons.Default.Phone, null) }
                        IconButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conversation.address}"))) }) { Icon(Icons.Default.Videocam, null) }
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(showMenu, { showMenu = false }) {
                                DropdownMenuItem({ Text("Search") }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null) })
                                DropdownMenuItem({ Text("Block & report") }, {
                                    showMenu = false
                                    SmsRepository.deleteThread(ctx, conversation.threadId)
                                    Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Red) })
                                DropdownMenuItem({ Text("Delete conversation") }, {
                                    showMenu = false
                                    SmsRepository.deleteThread(ctx, conversation.threadId)
                                    Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                                    onBack()
                                }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Red) })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = Surf) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp).navigationBarsPadding().imePadding(), verticalAlignment = Alignment.Bottom) {
                    IconButton({ imagePicker.launch("image/*") }, Modifier.size(40.dp).clip(CircleShape).background(Brand)) { Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Message", color = TextMetaLt) }, shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                        trailingIcon = { Row { IconButton({}) { Icon(Icons.Default.EmojiEmotions, null, tint = TextMeta) }; IconButton({ imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, null, tint = TextMeta) } } },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { doSend() }), maxLines = 5)
                    Spacer(Modifier.width(6.dp))
                    val has = draft.isNotBlank()
                    FloatingActionButton({ if (has) doSend() }, Modifier.size(46.dp), containerColor = if (has) Brand else BrandLt, contentColor = if (has) Color.White else Brand, shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp)) { Icon(if (has) Icons.Default.Send else Icons.Default.Mic, null, Modifier.size(22.dp)) }
                }
            }
        }
    ) { pad ->
        val displayMsgs = if (showSearch && searchQ.isNotBlank()) msgs.filter { it.body.contains(searchQ, true) } else msgs
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)) {
            val grouped = displayMsgs.groupByDate()
            grouped.forEach { (day, dayMsgs) ->
                item(key = "day_$day") { DayHeader(day) }
                items(dayMsgs, key = { it.id }) { m ->
                    MessageBubble(m, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctxMsg = m })
                }
            }
        }
    }
}

// ━━━━━━━━━━━━ SETTINGS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(containerColor = BrandLt,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandLt, titleContentColor = TextDk, navigationIconContentColor = TextDk),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Settings") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
            item { SettRow(Icons.Default.Notifications, "Notifications", "Sounds, vibration and previews") {} }
            item { SettRow(Icons.Default.Chat, "Bubbles", "Choose your bubble color") {} }
            item { HorizontalDivider(color = DivClr, thickness = .5.dp) }
            item { SettRow(Icons.Default.Palette, "Choose theme", "System default") {} }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Default") {} }
            item { SettRow(Icons.Default.Language, "Your current country", "United States") {} }
            item { HorizontalDivider(color = DivClr, thickness = .5.dp) }
            item { SettRow(Icons.Default.SmartToy, "Suggestions & Actions", "Smart Reply, Magic Compose") {} }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Only web link previews") {} }
            item { SettRow(Icons.Default.Shield, "Protection & Safety", "Spam, links and blocked") {} }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "Archive on swipe") {} }
            item { HorizontalDivider(color = DivClr, thickness = .5.dp) }
            item { SettRow(Icons.Default.Tune, "Advanced", "MMS, delivery reports and more") {} }
            item { SettRow(Icons.Default.Info, "About, terms & privacy", "Version 1.5") {} }
        }
    }
}

@Composable
fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextMeta, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp); if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = TextMetaLt) }
        Icon(Icons.Default.ChevronRight, null, tint = TextMetaLt)
    }
}

// ━━━━━━━━━━━━ ADMIN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var searchQ by remember { mutableStateOf("") }
    var results by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold(containerColor = BrandLt,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandLt, titleContentColor = TextDk, navigationIconContentColor = TextDk),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Admin Panel") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(16.dp)) {
            item {
                Text("CONTACT SEARCH", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMetaLt, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(searchQ, {
                    searchQ = it
                    scope.launch(Dispatchers.IO) {
                        try {
                            val url = java.net.URL("https://heimish-contacts.avrumy5872877.workers.dev/contacts?q=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.setRequestProperty("Authorization", "Bearer hm_admin_avrumy_2024")
                            val resp = conn.inputStream.bufferedReader().readText()
                            withContext(Dispatchers.Main) { results = resp }
                            conn.disconnect()
                        } catch (e: Exception) { withContext(Dispatchers.Main) { results = "Error: ${e.message}" } }
                    }
                }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or number…") }, singleLine = true,
                    shape = RoundedCornerShape(28.dp), colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent))
                Spacer(Modifier.height(12.dp))
                Text(results.take(2000), fontSize = 13.sp, color = TextMeta)
            }
            item {
                Spacer(Modifier.height(24.dp))
                Text("DEVICE INFO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMetaLt, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text("Model: ${Build.MODEL}", fontSize = 14.sp)
                Text("Android: ${Build.VERSION.RELEASE}", fontSize = 14.sp, color = TextMeta)
                Text("Default SMS: ${isDefaultSmsApp(ctx)}", fontSize = 14.sp, color = TextMeta)
                Spacer(Modifier.height(12.dp))
                Button({ ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing contacts...", Toast.LENGTH_SHORT).show() },
                    colors = ButtonDefaults.buttonColors(Brand)) { Text("Sync contacts now") }
            }
        }
    }
}

// ━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, onLongClick: () -> Unit) {
    val isIn = m.incoming
    val shape = RoundedCornerShape(20.dp, 20.dp, if (isIn) 20.dp else 4.dp, if (isIn) 4.dp else 20.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).combinedClickable(onClick = {}, onLongClick = onLongClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
        horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End) {
        if (!isIn) Spacer(Modifier.width(56.dp))
        Box(Modifier.widthIn(max = 300.dp).clip(shape).background(if (isIn) BubbleIn else BubbleOut)) {
            Column {
                if (m.imageUri != null) AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)), contentScale = ContentScale.Crop)
                if (m.body.isNotBlank()) Text(m.body, color = if (isIn) TextDk else Color.White, fontSize = 15.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).fillMaxWidth()) {
                    Text(msgTime(m.date), fontSize = 11.sp, color = if (isIn) TextMetaLt else Color.White.copy(.7f))
                    if (!isIn) { Spacer(Modifier.width(4.dp)); Text("• SMS", fontSize = 11.sp, color = Color.White.copy(.7f)); Spacer(Modifier.width(4.dp)); Icon(Icons.Default.DoneAll, null, tint = Color.White.copy(.85f), modifier = Modifier.size(14.dp)) }
                }
            }
        }
        if (isIn) Spacer(Modifier.width(56.dp))
    }
}

// ━━━━━━━━━━━━ HELPERS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
fun List<Message>.groupByDate(): List<Pair<String, List<Message>>> {
    if (isEmpty()) return emptyList()
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val today = fmt.format(Date()); val yest = fmt.format(Date(System.currentTimeMillis() - 86_400_000))
    val g = LinkedHashMap<String, MutableList<Message>>()
    forEach { m -> val l = when (val d = fmt.format(Date(m.date))) { today -> "Today"; yest -> "Yesterday"; else -> d }; g.getOrPut(l) { mutableListOf() }.add(m) }
    return g.map { it.key to it.value }
}
@Composable fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(BrandLt2).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(label, fontSize = 12.sp, color = TextMeta, fontWeight = FontWeight.Medium)
        }
    }
}
private val palette = listOf(Color(0xFF1F6F5C), Color(0xFF2E5E8C), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFF8C2E5E), Color(0xFF2E8C7A), Color(0xFF8C7A2E), Color(0xFF1A237E))
fun avatarColor(seed: String): Color = palette[Math.abs(seed.hashCode()) % palette.size]
fun msgTime(ms: Long): String { if (ms <= 0) return ""; return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) }
fun shortTime(ms: Long): String { if (ms <= 0) return ""; val n = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }; return if (n.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) && n.get(Calendar.YEAR) == t.get(Calendar.YEAR)) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms)) }
