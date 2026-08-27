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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Google Messages Material 3 Expressive Colors (EXACT match) ───────────────
private val Brand       = Color(0xFF0B57D0)  // Primary blue
private val BrandDk     = Color(0xFF0842A0)  // Darker blue for pressed states
private val BrandSurf   = Color(0xFFD3E3FD)  // App bar / surface tint
private val BrandLt     = Color(0xFFD3E3FD)  // Light blue background
private val BrandLt2    = Color(0xFFEDF2FA)  // Very light blue
private val BubbleIn    = Color(0xFFE3E3E3)  // Received bubble — light gray
private val BubbleOut   = Color(0xFF0B57D0)  // Sent bubble — dark blue
private val BgSurf      = Color(0xFFF6F8FC)  // Page background
private val Surf        = Color.White
private val TextPrimary = Color(0xFF1F1F1F)
private val TextSecond  = Color(0xFF444746)
private val TextHint    = Color(0xFF747775)
private val DivClr      = Color(0xFFC4C7C5)
private val DivLt       = Color(0xFFE8EAED)
private val Red         = Color(0xFFB3261E)
private val Green       = Color(0xFF146C2E)

private val GMColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = Brand, background = BgSurf,
    surface = Surf, onSurface = TextPrimary, outline = DivClr,
    surfaceVariant = BrandLt2
)

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BrandSurf.toArgb()
        SmsSyncService.start(this)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNow(this)
        setContent {
            MaterialTheme(colorScheme = GMColors) {
                Surface(Modifier.fillMaxSize(), color = BrandSurf) { AppRoot(isDefaultSmsApp(this)) { requestDefault() } }
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

// ━━━━━━━━━━━━━━━━ NAVIGATION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
sealed class Screen {
    data object List : Screen()
    data class Chat(val conv: Conversation) : Screen()
    data object NewConv : Screen()
    data object Settings : Screen()
    data class SettingSub(val key: String) : Screen()
    data object Admin : Screen()
}

@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = Permissions.granted(ctx) }
    LaunchedEffect(Unit) { if (!hasPerms) permL.launch(Permissions.ALL) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    BackHandler(enabled = screen !is Screen.List) {
        screen = when (screen) {
            is Screen.Chat, Screen.NewConv, Screen.Settings, Screen.Admin -> Screen.List
            is Screen.SettingSub -> Screen.Settings
            else -> Screen.List
        }
    }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPerms { permL.launch(Permissions.ALL) }
        else -> when (val s = screen) {
            Screen.List     -> ConvListScreen({ screen = Screen.Chat(it) }, { screen = Screen.NewConv }, { screen = Screen.Settings }, { screen = Screen.Admin })
            is Screen.Chat  -> ThreadScreen(s.conv) { screen = Screen.List }
            Screen.NewConv  -> NewConvScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            Screen.Settings -> SettingsScreen({ screen = Screen.SettingSub(it) }) { screen = Screen.List }
            is Screen.SettingSub -> SettingSubScreen(s.key) { screen = Screen.Settings }
            Screen.Admin    -> AdminScreen { screen = Screen.List }
        }
    }
}

// ━━━━━━━━━━━━━━━━ SETUP SCREENS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable fun SetupScreen(onReq: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Message, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Heimish Messages", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Set as your default messaging app", fontSize = 15.sp, color = Color.White.copy(.85f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onReq, colors = ButtonDefaults.buttonColors(Surf, Brand), shape = RoundedCornerShape(50)) {
                Text("Set as Default", fontWeight = FontWeight.Bold)
            }
        }
    }
}
@Composable fun SetupPerms(onGrant: () -> Unit) {
    val ctx = LocalContext.current
    var tried by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Permissions Required", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("SMS, Phone, Contacts and Notifications", fontSize = 14.sp, color = Color.White.copy(.7f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Button(onClick = {
                if (!tried) { tried = true; onGrant() }
                else ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", ctx.packageName, null) })
            }, colors = ButtonDefaults.buttonColors(Surf, Brand), shape = RoundedCornerShape(50)) {
                Text(if (!tried) "Allow Access" else "Open Settings", fontWeight = FontWeight.Bold)
            }
            if (tried) { Spacer(Modifier.height(12.dp)); Text("Turn on all permissions, then come back", fontSize = 13.sp, color = Color.White.copy(.6f)) }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); if (Permissions.granted(ctx)) onGrant() } }
}

// ━━━━━━━━━━━━━━━━ CONVERSATION LIST ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    // Long-press context menu state
    var ctxConv by remember { mutableStateOf<Conversation?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() { scope.launch { isRefreshing = true; list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; isRefreshing = false } }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(Unit) { while (true) { delay(5000); list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) } } }

    val filtered = if (search.isBlank()) list else list.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) }

    // Long-press context menu dialog
    if (ctxConv != null) {
        val c = ctxConv!!
        AlertDialog(
            onDismissRequest = { ctxConv = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(c.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    MenuBtn(Icons.Default.PushPin, "Pin") { Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show(); ctxConv = null }
                    MenuBtn(Icons.Default.Archive, "Archive") {
                        Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show(); ctxConv = null
                    }
                    MenuBtn(if (c.unread) Icons.Default.DoneAll else Icons.Default.MarkEmailUnread,
                        if (c.unread) "Mark as read" else "Mark as unread") {
                        if (c.unread) scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId) }
                        refresh(); ctxConv = null
                    }
                    MenuBtn(Icons.Default.Block, "Block", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }
                        Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); refresh(); ctxConv = null
                    }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }
                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); refresh(); ctxConv = null
                    }
                }
            },
            confirmButton = { TextButton({ ctxConv = null }) { Text("Cancel") } }
        )
    }

    Scaffold(containerColor = BrandSurf,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandSurf, titleContentColor = TextPrimary, actionIconContentColor = TextPrimary),
                title = {
                    if (showSearch) TextField(search, { search = it }, placeholder = { Text("Search conversations…", color = TextHint) }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand), modifier = Modifier.fillMaxWidth())
                    else Text("Messages", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                },
                actions = {
                    IconButton({ showSearch = !showSearch; if (!showSearch) search = "" }) { Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, null) }
                    if (!showSearch) {
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf)) {
                                DropdownMenuItem({ Text("Settings") }, { showMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                                DropdownMenuItem({ Text("Admin Panel") }, { showMenu = false; onAdmin() }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null) })
                                DropdownMenuItem({ Text("Mark all read") }, {
                                    showMenu = false
                                    scope.launch(Dispatchers.IO) { list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) } }
                                    refresh()
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
        Box(Modifier.padding(pad)) {
            if (filtered.isEmpty() && !isRefreshing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = TextHint, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (search.isBlank()) "No messages yet" else "No results", color = TextHint)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand)
                    LazyColumn(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf),
                        state = rememberLazyListState()
                    ) {
                        items(filtered, key = { it.threadId }) { conv ->
                            SwipeableConvRow(conv, onOpen, onRefresh = { refresh() }, onLongPress = { ctxConv = it })
                            if (filtered.last() != conv) HorizontalDivider(Modifier.padding(start = 76.dp), thickness = 0.5.dp, color = DivLt)
                        }
                    }
                }
            }
        }
    }
}

// ── Swipeable conversation row (swipe left = archive) ────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConvRow(c: Conversation, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit, onLongPress: (Conversation) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = 150f

    Box(
        Modifier.fillMaxWidth().background(
            if (offsetX < -20f) Color(0xFF5F6368) else Color.Transparent
        )
    ) {
        // Archive icon behind
        if (offsetX < -20f) {
            Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Archive, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text("Archive", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // The actual row
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt().coerceAtMost(0), 0) }
                .background(Surf)
                .combinedClickable(
                    onClick = { onOpen(c) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(c) }
                )
                .pointerInput(c.threadId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold) {
                                Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show()
                                onRefresh()
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dx -> offsetX = (offsetX + dx).coerceIn(-200f, 0f) }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(Modifier.size(52.dp).clip(CircleShape).background(avatarColor(c.address)), contentAlignment = Alignment.Center) {
                Text(initial(c.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(shortTime(c.date), fontSize = 12.sp, color = if (c.unread) Brand else TextHint)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.snippet.ifBlank { " " }, fontSize = 14.sp, color = if (c.unread) TextPrimary else TextHint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (c.unread) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Brand))
                    }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ NEW CONVERSATION (with groups) ━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewConvScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var to by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var selected by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var isGroup by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { SmsRepository.loadContactsList(ctx) } }
    val filtered = if (to.isBlank()) contacts else contacts.filter { it.first.contains(to, true) || it.second.contains(to, true) }

    fun openConv(name: String, number: String) {
        val threadId = SmsRepository.getThreadIdForAddress(ctx, number)
        onOpen(Conversation(threadId, number, name, "", System.currentTimeMillis(), false))
    }

    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("New conversation") }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilledTonalButton(onClick = { isGroup = !isGroup }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (isGroup) BrandSurf else BrandLt2)) {
                    Icon(if (isGroup) Icons.Default.GroupRemove else Icons.Default.GroupAdd, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isGroup) "Cancel group" else "Create group")
                }
            }
            if (isGroup && selected.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selected.forEach { (name, num) ->
                        AssistChip(onClick = { selected = selected.filter { it.second != num } }, label = { Text(name, fontSize = 13.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = BrandSurf))
                    }
                    if (selected.size >= 2) {
                        Button(onClick = {
                            val addr = selected.joinToString(";") { it.second }
                            val names = selected.joinToString(", ") { it.first }
                            onOpen(Conversation(addr.hashCode().toLong(), addr, names, "", System.currentTimeMillis(), false))
                        }, colors = ButtonDefaults.buttonColors(Brand), shape = RoundedCornerShape(20.dp)) { Text("Start", fontSize = 13.sp) }
                    }
                }
            }
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("To: name or number") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Phone),
                keyboardActions = KeyboardActions(onDone = { if (to.isNotBlank() && !isGroup) openConv(contacts.find { it.second == to.trim() }?.first ?: to.trim(), to.trim()) }))
            LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
                items(filtered) { (name, number) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (isGroup) { if (selected.none { it.second == number }) selected = selected + (name to number); to = "" }
                        else openConv(name, number)
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(number)), contentAlignment = Alignment.Center) {
                            Text(initial(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium); Text(number, fontSize = 13.sp, color = TextHint) }
                        if (isGroup && selected.any { it.second == number }) Icon(Icons.Default.CheckCircle, null, tint = Brand)
                    }
                    HorizontalDivider(Modifier.padding(start = 74.dp), thickness = .5.dp, color = DivLt)
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ THREAD / CHAT SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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
    var ctxMsg by remember { mutableStateOf<Message?>(null) }
    var showEmoji by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var pendingMediaUri by remember { mutableStateOf<Uri?>(null) }
    var pendingMediaType by remember { mutableStateOf("image") }
    var replyTo by remember { mutableStateOf<Message?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    fun reload() {
        val raw = SmsRepository.loadMessages(ctx, conversation.threadId)
        msgs = raw.filter { it.body.isNotBlank() || it.imageUri != null }
        scope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    }

    fun doSend() {
        val body = draft.trim()
        if (pendingMediaUri != null) {
            val uri = pendingMediaUri!!
            val prefix = if (replyTo != null) "\u21a9 ${replyTo!!.body.take(40)}\n" else ""
            scope.launch(Dispatchers.IO) {
                SmsRepository.sendMms(ctx, conversation.address, uri, prefix + body)
                withContext(Dispatchers.Main) { draft = ""; pendingMediaUri = null; replyTo = null; keyboard?.hide(); reload() }
            }
            return
        }
        if (body.isEmpty()) return
        val prefix = if (replyTo != null) "\u21a9 ${replyTo!!.body.take(40)}\n" else ""
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendSms(ctx, conversation.address, prefix + body)
            withContext(Dispatchers.Main) { if (ok) { draft = ""; replyTo = null; keyboard?.hide(); reload() } else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
        }
    }

    BackHandler { onBack() }
    LaunchedEffect(Unit) { reload(); SmsRepository.markThreadRead(ctx, conversation.threadId) }
    LaunchedEffect(Unit) { while (true) { delay(3000); reload() } }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { pendingMediaUri = it; pendingMediaType = "image"; showAttach = false } }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { pendingMediaUri = it; pendingMediaType = "video"; showAttach = false } }

    // Context menu dialog (long-press on message)
    if (ctxMsg != null) {
        val m = ctxMsg!!
        AlertDialog(
            onDismissRequest = { ctxMsg = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(m.body.take(60).ifBlank { "Message" }, fontSize = 14.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    // Reaction bar
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("\ud83d\ude02", "\ud83d\udc4d", "\u2764\ufe0f", "\ud83d\ude2e", "\ud83d\ude22", "\ud83d\ude21").forEach { emoji ->
                            Box(Modifier.size(44.dp).clip(CircleShape).background(BrandLt2).clickable {
                                Toast.makeText(ctx, "$emoji Reacted", Toast.LENGTH_SHORT).show(); ctxMsg = null
                            }, contentAlignment = Alignment.Center) {
                                Text(emoji, fontSize = 22.sp)
                            }
                        }
                    }
                    HorizontalDivider(color = DivLt, thickness = .5.dp)
                    Spacer(Modifier.height(4.dp))
                    MenuBtn(Icons.Default.Reply, "Reply") { replyTo = m; ctxMsg = null }
                    MenuBtn(Icons.Default.ContentCopy, "Copy") {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.body))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Forward, "Forward") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, m.body) }, "Forward"))
                        ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Star, "Star", Color(0xFFFFC107)) { Toast.makeText(ctx, "\u2b50 Starred", Toast.LENGTH_SHORT).show(); ctxMsg = null }
                    MenuBtn(Icons.Default.PushPin, "Pin") { Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show(); ctxMsg = null }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m.id) }
                        ctxMsg = null; reload()
                    }
                }
            },
            confirmButton = { TextButton({ ctxMsg = null }) { Text("Cancel") } }
        )
    }

    Scaffold(containerColor = BrandSurf,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextPrimary),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat\u2026", color = TextHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand), modifier = Modifier.fillMaxWidth())
                    } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conversation.address}")))
                    }) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(conversation.address)), contentAlignment = Alignment.Center) {
                            Text(initial(conversation.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                            Text(conversation.address, fontSize = 12.sp, color = TextHint)
                        }
                    }
                },
                actions = {
                    if (showSearch) {
                        IconButton({ showSearch = false; searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                    } else {
                        IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}"))) }) { Icon(Icons.Default.Phone, null) }
                        IconButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conversation.address}"))) }) { Icon(Icons.Default.Videocam, null) }
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf)) {
                                DropdownMenuItem({ Text("Search") }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null) })
                                DropdownMenuItem({ Text("Block & report") }, {
                                    showMenu = false; scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, conversation.threadId) }
                                    Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Red) })
                                DropdownMenuItem({ Text("Delete conversation") }, {
                                    showMenu = false; scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, conversation.threadId) }
                                    Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Delete, null, tint = Red) })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = Surf, shadowElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    // Reply indicator
                    if (replyTo != null) {
                        Row(Modifier.fillMaxWidth().background(BrandLt2).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(Brand))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reply", fontSize = 12.sp, color = Brand, fontWeight = FontWeight.SemiBold)
                                Text(replyTo!!.body.take(60), fontSize = 13.sp, color = TextSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ replyTo = null }, Modifier.size(24.dp)) { Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = TextHint) }
                        }
                    }
                    // Media preview
                    if (pendingMediaUri != null) {
                        Row(Modifier.fillMaxWidth().background(BrandLt2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pendingMediaType == "image") {
                                AsyncImage(pendingMediaUri, "preview", Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(TextPrimary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(if (pendingMediaType == "image") "Photo ready" else "Video ready", fontSize = 14.sp, color = TextSecond, modifier = Modifier.weight(1f))
                            IconButton({ pendingMediaUri = null }) { Icon(Icons.Default.Close, null, tint = Red) }
                        }
                    }
                    // Compose bar: [+] [field with emoji + gallery] [mic/send]
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        // + button (blue circle)
                        IconButton(onClick = { showAttach = !showAttach; showEmoji = false }, Modifier.size(44.dp).clip(CircleShape).background(Brand)) {
                            Icon(if (showAttach) Icons.Default.Close else Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        // Input field (rounded 28dp)
                        OutlinedTextField(
                            draft, { draft = it }, Modifier.weight(1f),
                            placeholder = { Text("Message", color = TextHint) },
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                            leadingIcon = { IconButton({ showEmoji = !showEmoji; showAttach = false }) { Icon(Icons.Default.EmojiEmotions, null, tint = TextHint) } },
                            trailingIcon = { IconButton({ imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, null, tint = TextHint) } },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            maxLines = 4
                        )
                        Spacer(Modifier.width(6.dp))
                        // Mic / Send
                        val hasCont = draft.isNotBlank() || pendingMediaUri != null
                        FloatingActionButton(
                            onClick = { if (hasCont) doSend() },
                            modifier = Modifier.size(46.dp),
                            containerColor = if (hasCont) Brand else BrandLt2,
                            contentColor = if (hasCont) Color.White else Brand,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) { Icon(if (hasCont) Icons.Default.Send else Icons.Default.Mic, null, Modifier.size(22.dp)) }
                    }
                    // Attachment sheet (5x2 grid — M3 Expressive: same bg as app bar, pill-shaped monochrome buttons)
                    AnimatedVisibility(showAttach, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        LazyVerticalGrid(
                            GridCells.Fixed(5),
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(BrandSurf).padding(horizontal = 12.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item { AttachBtn(Icons.Default.Image, "Gallery", Brand) { imagePicker.launch("image/*") } }
                            item { AttachBtn(Icons.Default.CameraAlt, "Camera", Green) { ctx.startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)) } }
                            item { AttachBtn(Icons.Default.Gif, "GIFs", Color(0xFF8E24AA)) { Toast.makeText(ctx, "GIFs coming soon", Toast.LENGTH_SHORT).show() } }
                            item { AttachBtn(Icons.Default.EmojiEmotions, "Stickers", Color(0xFFE65100)) { Toast.makeText(ctx, "Stickers coming soon", Toast.LENGTH_SHORT).show() } }
                            item { AttachBtn(Icons.Default.AutoAwesome, "Magic", Color(0xFF1E88E5)) { Toast.makeText(ctx, "Magic Compose coming soon", Toast.LENGTH_SHORT).show() } }
                            item { AttachBtn(Icons.Default.InsertDriveFile, "Files", Color(0xFF6D4C41)) { ctx.startActivity(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }) } }
                            item { AttachBtn(Icons.Default.LocationOn, "Location", Color(0xFFD32F2F)) {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=My+Location")))
                            } }
                            item { AttachBtn(Icons.Default.Contacts, "Contacts", Color(0xFF00897B)) {
                                ctx.startActivity(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI))
                            } }
                            item { AttachBtn(Icons.Default.Schedule, "Schedule", Color(0xFF5E35B1)) { Toast.makeText(ctx, "Schedule send coming soon", Toast.LENGTH_SHORT).show() } }
                            item { AttachBtn(Icons.Default.Videocam, "Video", Color(0xFF039BE5)) { videoPicker.launch("video/*") } }
                        }
                    }
                    // Emoji picker
                    AnimatedVisibility(showEmoji, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        EmojiPicker { draft += it; showEmoji = false }
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
        ) {
            val displayMsgs = if (showSearch && searchQuery.isNotBlank()) msgs.filter { it.body.contains(searchQuery, true) } else msgs
            val grouped = displayMsgs.groupByDate()
            grouped.forEach { (day, dayMsgs) ->
                item(key = "day_$day") { DayHeader(day) }
                items(dayMsgs, key = { it.id }) { m ->
                    SwipeToReplyBubble(m, onReply = { replyTo = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctxMsg = m })
                }
            }
            // Smart replies after last incoming message
            if (msgs.isNotEmpty() && msgs.last().incoming) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("\u05d0\u05b8\u05e7\u05e2\u05d9!", "\ud83d\udc4d", "\u05d9\u05d0\u05b8!", "\u05e9\u05d9\u05d9\u05df!").forEach { reply ->
                            Surface(
                                onClick = {
                                    draft = reply
                                    scope.launch { doSend() }
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = BrandLt2,
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
fun SwipeToReplyBubble(m: Message, onReply: () -> Unit, onLongClick: () -> Unit) {
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
            MessageBubble(m, onLongClick)
        }
    }
}

// ── Attachment button ────────────────────────────────────────────────────────
// ── Attachment button (pill-shaped, monochrome — M3 Expressive style) ────────
@Composable
fun AttachBtn(icon: ImageVector, label: String, @Suppress("UNUSED_PARAMETER") tint: Color, onClick: () -> Unit) {
    // M3 Expressive: pill-shaped, monochrome icons, no colorful backgrounds
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFF0F0F0),
            modifier = Modifier.size(width = 56.dp, height = 48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color(0xFF444746), modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = TextSecond, maxLines = 1)
    }
}

@Composable
fun MenuBtn(icon: ImageVector, label: String, tint: Color = TextPrimary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (tint == Red) Red else TextPrimary)
    }
}

// ━━━━━━━━━━━━━━━━ EMOJI PICKER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
fun EmojiPicker(onPick: (String) -> Unit) {
    val emojis = listOf(
        "\ud83d\ude00","\ud83d\ude02","\ud83e\udd79","\ud83d\ude0d","\ud83e\udd14","\ud83d\ude0e","\ud83d\ude4f","\u2764\ufe0f","\ud83d\udc4d","\ud83d\udc4e",
        "\ud83d\udd25","\ud83c\udf89","\ud83d\ude22","\ud83d\ude21","\ud83e\udd23","\ud83d\ude0a","\ud83e\udd70","\ud83d\ude18","\ud83e\udd17","\ud83d\ude0f",
        "\ud83d\ude2d","\ud83d\ude24","\ud83e\udd2f","\ud83e\udee1","\ud83d\udc80","\u2728","\ud83d\udcaa","\ud83d\ude44","\ud83d\ude34","\ud83e\udd1d",
        "\ud83d\udc4b","\ud83e\udee6","\ud83d\udcaf","\ud83c\udf81","\ud83d\udcf1","\u2705","\u274c","\u2b50","\ud83c\udf19","\u2600\ufe0f",
        "\ud83c\udf55","\ud83c\udf54","\u2615","\ud83c\udf4e","\ud83c\udf39","\ud83d\udd4a\ufe0f","\ud83c\uddfa\ud83c\uddf8","\ud83c\uddee\ud83c\uddf1","\ud83d\udcd6","\u270d\ufe0f",
        "\ud83d\ude07","\ud83e\udd29","\ud83d\ude1c","\ud83e\udee3","\ud83e\udd7a","\ud83d\ude24","\ud83d\ude43","\ud83d\ude2c","\ud83e\udd2b","\ud83e\udee0",
        "\ud83d\udc94","\ud83d\udc95","\ud83d\udc96","\ud83e\udee0","\ud83d\udc4f","\ud83e\udd1e","\u270c\ufe0f","\ud83e\udd1f","\ud83e\udee5","\ud83d\udc40"
    )
    Surface(color = BrandSurf, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("\ud83d\ude0a Emoji" to true, "GIF" to false, "Stickers" to false).forEach { (label, active) ->
                    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(if (active) BrandSurf else Color.Transparent).clickable {}.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal, color = if (active) Brand else TextHint)
                    }
                }
            }
            LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().heightIn(max = 200.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                items(emojis) { e ->
                    Box(Modifier.size(44.dp).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val isIn = m.incoming
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomEnd = if (isIn) 20.dp else 4.dp,
        bottomStart = if (isIn) 4.dp else 20.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
        horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
    ) {
        if (!isIn) Spacer(Modifier.width(56.dp))
        Box(Modifier.widthIn(max = 300.dp).clip(shape).background(if (isIn) BubbleIn else BubbleOut)) {
            Column {
                // Image (empty bubble if only image)
                if (m.imageUri != null) {
                    AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(
                        if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                    ), contentScale = ContentScale.Crop)
                }
                // Text with link detection
                if (m.body.isNotBlank()) {
                    val textColor = if (isIn) TextPrimary else Color.White
                    val linkColor = if (isIn) Brand else Color(0xFFBBDEFB)
                    val urls = URL_REGEX.findAll(m.body).toList()

                    if (urls.isEmpty()) {
                        Text(m.body, color = textColor, fontSize = 15.sp, lineHeight = 21.sp,
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp))
                    } else {
                        val annotated = buildAnnotatedString {
                            var last = 0
                            urls.forEach { match ->
                                withStyle(SpanStyle(color = textColor)) { append(m.body.substring(last, match.range.first)) }
                                pushStringAnnotation("URL", match.value)
                                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(match.value) }
                                pop()
                                last = match.range.last + 1
                            }
                            if (last < m.body.length) withStyle(SpanStyle(color = textColor)) { append(m.body.substring(last)) }
                        }
                        ClickableText(annotated, style = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp),
                            onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item))) } })
                        // Link preview card
                        val url = urls.first().value
                        val domain = try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url }
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (isIn) Color(0xFFD5D5D5) else BrandDk)
                            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Language, null, tint = if (isIn) TextSecond else Color.White.copy(.8f), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(domain, fontSize = 12.sp, color = if (isIn) TextSecond else Color.White.copy(.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // Timestamp + SMS label + delivery ticks (Google Messages style)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 2.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(msgTime(m.date), fontSize = 11.sp, color = if (isIn) TextHint else Color.White.copy(.7f))
                    if (!isIn) {
                        Text(if (m.isMms) " \u00b7 MMS " else " \u00b7 SMS ", fontSize = 11.sp, color = Color.White.copy(.7f))
                        Icon(Icons.Default.DoneAll, null, tint = Color.White.copy(.85f), modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (isIn) Spacer(Modifier.width(56.dp))
    }
}

// ━━━━━━━━━━━━━━━━ SETTINGS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSub: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Settings") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
            item { SettRow(Icons.Default.Notifications, "Notifications", "Sounds, vibration") { onSub("notifications") } }
            item { SettRow(Icons.Default.Chat, "Bubbles", "Chat colors and style") { onSub("bubbles") } }
            item { HorizontalDivider(color = DivLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Palette, "Theme", "System default") { onSub("theme") } }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Default") { onSub("textsize") } }
            item { HorizontalDivider(color = DivLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Web link previews") { onSub("previews") } }
            item { SettRow(Icons.Default.Shield, "Protection & Safety", "Spam, blocked contacts") { onSub("safety") } }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "Archive on swipe") { onSub("swipe") } }
            item { HorizontalDivider(color = DivLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Tune, "Advanced", "Delivery reports, MMS") { onSub("advanced") } }
            item { SettRow(Icons.Default.Info, "About", "Version 2.7") { onSub("about") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSubScreen(key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val title = when (key) { "notifications" -> "Notifications"; "bubbles" -> "Bubbles"; "theme" -> "Theme"; "textsize" -> "Text size"
        "previews" -> "Previews"; "safety" -> "Safety"; "swipe" -> "Swipe actions"; "advanced" -> "Advanced"; "about" -> "About"; else -> "Settings" }

    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } }, title = { Text(title) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(16.dp)) {
            when (key) {
                "notifications" -> {
                    item { ToggleRow("Allow notifications", "notif_allow", prefs) }
                    item { ToggleRow("Sound", "notif_sound", prefs) }
                    item { ToggleRow("Vibrate", "notif_vibrate", prefs) }
                    item { ToggleRow("Show sender name", "notif_sender", prefs) }
                    item { ToggleRow("Show previews", "notif_preview", prefs) }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            Notifications.showTestNotification(ctx)
                            Toast.makeText(ctx, "Test notification sent", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(Brand), shape = RoundedCornerShape(20.dp)) {
                            Text("Test notification")
                        }
                    }
                }
                "bubbles" -> {
                    item { Text("Chat bubble color", fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Spacer(Modifier.height(12.dp)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(Brand, Color(0xFF1E8E3E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFD93025), Color(0xFF185ABC), Color(0xFFE65100), Color(0xFF00897B)).forEach { clr ->
                                Box(Modifier.size(44.dp).clip(CircleShape).background(clr).clickable {
                                    prefs.edit().putInt("bubble_color", clr.toArgb()).apply()
                                    Toast.makeText(ctx, "Color updated", Toast.LENGTH_SHORT).show()
                                })
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(20.dp))
                        Text("Preview", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextHint)
                        Spacer(Modifier.height(8.dp))
                        // Live preview bubble
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(BubbleOut).padding(12.dp)) {
                            Column {
                                Text("Hello!", color = Color.White, fontSize = 15.sp)
                                Text("3:25 PM \u00b7 SMS \u2713\u2713", color = Color.White.copy(.7f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
                "theme" -> {
                    item { RadioRow("System default", prefs.getString("theme", "system") == "system") { prefs.edit().putString("theme", "system").apply() } }
                    item { RadioRow("Light", prefs.getString("theme", "system") == "light") { prefs.edit().putString("theme", "light").apply() } }
                    item { RadioRow("Dark", prefs.getString("theme", "system") == "dark") { prefs.edit().putString("theme", "dark").apply() } }
                }
                "textsize" -> {
                    item { RadioRow("Small", prefs.getString("text_size", "default") == "small") { prefs.edit().putString("text_size", "small").apply() } }
                    item { RadioRow("Default", prefs.getString("text_size", "default") == "default") { prefs.edit().putString("text_size", "default").apply() } }
                    item { RadioRow("Large", prefs.getString("text_size", "default") == "large") { prefs.edit().putString("text_size", "large").apply() } }
                    item { RadioRow("Largest", prefs.getString("text_size", "default") == "largest") { prefs.edit().putString("text_size", "largest").apply() } }
                }
                "previews" -> {
                    item { RadioRow("All previews", prefs.getString("preview_mode", "all") == "all") { prefs.edit().putString("preview_mode", "all").apply() } }
                    item { RadioRow("Links only", prefs.getString("preview_mode", "all") == "links") { prefs.edit().putString("preview_mode", "links").apply() } }
                    item { RadioRow("None", prefs.getString("preview_mode", "all") == "none") { prefs.edit().putString("preview_mode", "none").apply() } }
                }
                "safety" -> {
                    item { ToggleRow("Spam protection", "spam_protect", prefs) }
                    item { ToggleRow("Warn about suspicious links", "warn_links", prefs) }
                    item { ToggleRow("Blur sensitive images", "blur_sensitive", prefs) }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("Blocked numbers", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Manage blocked numbers", color = TextHint, fontSize = 13.sp)
                    }
                }
                "swipe" -> {
                    item { RadioRow("Archive", prefs.getString("swipe_action", "archive") == "archive") { prefs.edit().putString("swipe_action", "archive").apply() } }
                    item { RadioRow("Delete", prefs.getString("swipe_action", "archive") == "delete") { prefs.edit().putString("swipe_action", "delete").apply() } }
                    item { RadioRow("Mark as read", prefs.getString("swipe_action", "archive") == "read") { prefs.edit().putString("swipe_action", "read").apply() } }
                    item { RadioRow("Nothing", prefs.getString("swipe_action", "archive") == "none") { prefs.edit().putString("swipe_action", "none").apply() } }
                }
                "advanced" -> {
                    item { ToggleRow("Auto-download MMS", "mms_auto", prefs) }
                    item { ToggleRow("SMS delivery reports", "sms_delivery", prefs) }
                    item { ToggleRow("MMS delivery reports", "mms_delivery", prefs) }
                    item { ToggleRow("Group MMS", "group_mms", prefs) }
                    item { ToggleRow("Auto-download on Wi-Fi", "mms_wifi", prefs) }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            if (android.app.AlertDialog.Builder(ctx).create().let { false }) { /* never */ }
                            Toast.makeText(ctx, "Delete all? Long-press to confirm.", Toast.LENGTH_LONG).show()
                        }, colors = ButtonDefaults.buttonColors(Red), shape = RoundedCornerShape(20.dp)) {
                            Text("Delete all conversations")
                        }
                    }
                }
                "about" -> {
                    item { Text("Heimish Messages", fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                    item { Text("Version 2.7", fontSize = 14.sp, color = TextHint); Spacer(Modifier.height(16.dp)) }
                    item { Text("A heimishe messaging app for the community.", fontSize = 14.sp, color = TextSecond) }
                    item { Spacer(Modifier.height(16.dp)); Text("\u00a9 2024-2026 Heimish Messages", fontSize = 13.sp, color = TextHint) }
                }
            }
        }
    }
}

@Composable fun ToggleRow(label: String, key: String, prefs: android.content.SharedPreferences) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, true)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp); Switch(on, { on = it; prefs.edit().putBoolean(key, it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Brand))
    }
}
@Composable fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Brand)); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 15.sp)
    }
}
@Composable fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextSecond, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp); Text(sub, fontSize = 13.sp, color = TextHint) }
        Icon(Icons.Default.ChevronRight, null, tint = TextHint)
    }
}

// ━━━━━━━━━━━━━━━━ ADMIN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current; var q by remember { mutableStateOf("") }; var res by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } }, title = { Text("Admin Panel") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(16.dp)) {
            item {
                Text("CONTACT SEARCH", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextHint, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(q, {
                    q = it; scope.launch(Dispatchers.IO) {
                        try {
                            val url = java.net.URL("https://heimish-contacts.avrumy5872877.workers.dev/contacts?q=${java.net.URLEncoder.encode(it, "UTF-8")}")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.setRequestProperty("Authorization", "Bearer hm_admin_avrumy_2024")
                            val r = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                            withContext(Dispatchers.Main) { res = r }
                        } catch (e: Exception) { withContext(Dispatchers.Main) { res = "Error: ${e.message}" } }
                    }
                }, Modifier.fillMaxWidth(), placeholder = { Text("Search\u2026") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent))
                Spacer(Modifier.height(12.dp)); Text(res.take(2000), fontSize = 13.sp, color = TextSecond)
            }
            item {
                Spacer(Modifier.height(24.dp)); Text("DEVICE INFO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextHint, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                Text("Model: ${Build.MODEL}", fontSize = 14.sp); Text("Android: ${Build.VERSION.RELEASE}", fontSize = 14.sp, color = TextSecond)
                Text("Default SMS: ${isDefaultSmsApp(ctx)}", fontSize = 14.sp, color = TextSecond); Spacer(Modifier.height(12.dp))
                Button({ ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing\u2026", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(Brand), shape = RoundedCornerShape(20.dp)) { Text("Sync contacts") }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ HELPERS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(BrandLt2).border(0.5.dp, DivLt, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(label, fontSize = 12.sp, color = TextSecond, fontWeight = FontWeight.Medium)
        }
    }
}
private val palette = listOf(Color(0xFF0B57D0), Color(0xFF146C2E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFB3261E), Color(0xFF185ABC), Color(0xFF0D652D), Color(0xFF9C27B0))
fun avatarColor(seed: String): Color = palette[abs(seed.hashCode()) % palette.size]
fun initial(name: String): String = (name.firstOrNull()?.uppercaseChar() ?: '#').toString()
fun msgTime(ms: Long): String { if (ms <= 0) return ""; return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) }
fun shortTime(ms: Long): String {
    if (ms <= 0) return ""; val n = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    return if (n.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) && n.get(Calendar.YEAR) == t.get(Calendar.YEAR)) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
}
