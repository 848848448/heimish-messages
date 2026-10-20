package com.heimish.messages

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

// ━━━━━━━━━━━━ THEME (light + dark, Google Messages palette) ━━━━━━━━━━━━━━━━
object ThemeState { var dark by mutableStateOf(false) }
private val D get() = ThemeState.dark

private val Brand: Color       get() = if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
private val BrandDk: Color     get() = if (D) Color(0xFF7CACF8) else Color(0xFF0842A0)
private val BrandSurf: Color   get() = if (D) Color(0xFF004A77) else Color(0xFFD3E3FD)
private val BrandLt: Color     get() = if (D) Color(0xFF004A77) else Color(0xFFD3E3FD)
private val BrandLt2: Color    get() = if (D) Color(0xFF303034) else Color(0xFFEDF2FA)
private val BubbleIn: Color    get() = if (D) Color(0xFF444746) else Color(0xFFE3E3E3)
private val BubbleOut: Color   get() = if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
private val BubbleOutText: Color get() = if (D) Color(0xFF003258) else Color.White
private val BubbleOutSub: Color  get() = if (D) Color(0xFF003258).copy(.72f) else Color.White.copy(.7f)
private val BubbleOutLink: Color get() = if (D) Color(0xFF0842A0) else Color(0xFFBBDEFB)
private val BgSurf: Color      get() = if (D) Color(0xFF1B1B1F) else Color(0xFFF6F8FC)
private val Surf: Color        get() = if (D) Color(0xFF1E1F22) else Color.White
private val TextPrimary: Color get() = if (D) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
private val TextSecond: Color  get() = if (D) Color(0xFFC4C7C5) else Color(0xFF444746)
private val TextHint: Color    get() = if (D) Color(0xFF8E918F) else Color(0xFF747775)
private val DivClr: Color      get() = if (D) Color(0xFF444746) else Color(0xFFC4C7C5)
private val DivLt: Color       get() = if (D) Color(0xFF2F3033) else Color(0xFFE8EAED)
private val AttachSurf: Color  get() = if (D) Color(0xFF2D2F31) else Color(0xFFF0F0F0)
private val AttachIcon: Color  get() = if (D) Color(0xFFC4C7C5) else Color(0xFF444746)
private val Red: Color         get() = Color(0xFFB3261E)
private val Green: Color       get() = Color(0xFF146C2E)

private val GMColors: ColorScheme
    @Composable get() = if (ThemeState.dark) darkColorScheme(
        primary = Brand, onPrimary = Color(0xFF003258),
        secondary = Brand, background = BgSurf,
        surface = Surf, onSurface = TextPrimary, outline = DivClr,
        surfaceVariant = BrandLt2
    ) else lightColorScheme(
        primary = Brand, onPrimary = Color.White,
        secondary = Brand, background = BgSurf,
        surface = Surf, onSurface = TextPrimary, outline = DivClr,
        surfaceVariant = BrandLt2
    )

// ━━━━━━━━━━━━ LOCAL STORES (prefs-backed) ━━━━━━━━━━━━
private fun msgKey(m: Message) = (if (m.isMms) "m" else "s") + m.id

object PinStore {
    private const val KEY = "pinned_threads"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, threadId: Long) = set(ctx).getStringSet(KEY, emptySet())?.contains(threadId.toString()) == true
    fun toggle(ctx: Context, threadId: Long) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val k = threadId.toString()
        if (!s.add(k)) s.remove(k)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object ArchiveStore {
    private const val KEY = "archived_threads"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isArchived(ctx: Context, threadId: Long) = set(ctx).getStringSet(KEY, emptySet())?.contains(threadId.toString()) == true
    fun setArchived(ctx: Context, threadId: Long, archived: Boolean) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val k = threadId.toString()
        if (archived) s.add(k) else s.remove(k)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object StarStore {
    private const val KEY = "starred_messages"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isStarred(ctx: Context, key: String) = set(ctx).getStringSet(KEY, emptySet())?.contains(key) == true
    fun toggle(ctx: Context, key: String) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!s.add(key)) s.remove(key)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object ReactionStore {
    private const val KEY = "reactions"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String): String? = prefs(ctx).getString(KEY + "_" + key, null)
    fun set(ctx: Context, key: String, emoji: String?) {
        val e = prefs(ctx).edit()
        if (emoji == null) e.remove(KEY + "_" + key) else e.putString(KEY + "_" + key, emoji)
        e.apply()
    }
}

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNowIfStale(this)
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
    data object Archived : Screen()
    data object Starred : Screen()
}

@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    var themeChoice by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    ThemeState.dark = when (themeChoice) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val darkBar = ThemeState.dark
    LaunchedEffect(darkBar) {
        (ctx as? android.app.Activity)?.window?.statusBarColor = BrandSurf.toArgb()
    }
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = Permissions.granted(ctx) }
    LaunchedEffect(Unit) { if (!hasPerms) permL.launch(Permissions.ALL) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    BackHandler(enabled = screen !is Screen.List) {
        screen = when (screen) {
            is Screen.SettingSub -> Screen.Settings
            else -> Screen.List
        }
    }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPerms { permL.launch(Permissions.ALL) }
        else -> when (val s = screen) {
            Screen.List     -> ConvListScreen({ screen = Screen.Chat(it) }, { screen = Screen.NewConv }, { screen = Screen.Settings }, { screen = Screen.Admin }, { screen = Screen.Archived }, { screen = Screen.Starred })
            is Screen.Chat  -> ThreadScreen(s.conv) { screen = Screen.List }
            Screen.NewConv  -> NewConvScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            Screen.Settings -> SettingsScreen({ screen = Screen.SettingSub(it) }) { screen = Screen.List }
            is Screen.SettingSub -> SettingSubScreen(s.key, { themeChoice = it }) { screen = Screen.Settings }
            Screen.Admin    -> AdminScreen { screen = Screen.List }
            Screen.Archived -> ArchivedScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            Screen.Starred  -> StarredScreen { screen = Screen.List }
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
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit, onArchived: () -> Unit, onStarred: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var ctxConv by remember { mutableStateOf<Conversation?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() { scope.launch { isRefreshing = true; list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; isRefreshing = false } }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(Unit) { while (true) { delay(5000); list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; tick++ } }

    val visible = remember(list, tick) { list.filter { !ArchiveStore.isArchived(ctx, it.threadId) && !BlockedStore.isBlocked(ctx, it.address) } }
    val searched = if (search.isBlank()) visible else visible.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) || it.address.contains(search, true) }
    val filtered = remember(searched, tick) { searched.sortedByDescending { if (PinStore.isPinned(ctx, it.threadId)) 1 else 0 } }

    if (ctxConv != null) {
        val c = ctxConv!!
        val pinned = PinStore.isPinned(ctx, c.threadId)
        AlertDialog(
            onDismissRequest = { ctxConv = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(c.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary) },
            text = {
                Column {
                    MenuBtn(if (pinned) Icons.Default.PushPin else Icons.Default.PushPin, if (pinned) "Unpin" else "Pin") {
                        PinStore.toggle(ctx, c.threadId); tick++; ctxConv = null
                    }
                    MenuBtn(Icons.Default.Archive, "Archive") {
                        ArchiveStore.setArchived(ctx, c.threadId, true)
                        Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show(); tick++; ctxConv = null
                    }
                    MenuBtn(if (c.unread) Icons.Default.DoneAll else Icons.Default.MarkEmailUnread,
                        if (c.unread) "Mark as read" else "Mark as unread") {
                        if (c.unread) scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId) }
                        refresh(); ctxConv = null
                    }
                    MenuBtn(Icons.Default.Block, "Block", Red) {
                        BlockedStore.block(ctx, c.address)
                        Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); tick++; ctxConv = null
                    }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }
                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); refresh(); ctxConv = null
                    }
                }
            },
            confirmButton = { TextButton({ ctxConv = null }) { Text("Cancel", color = Brand) } }
        )
    }

    Scaffold(containerColor = BrandSurf,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BrandSurf, titleContentColor = TextPrimary, actionIconContentColor = TextPrimary),
                title = {
                    if (showSearch) TextField(search, { search = it }, placeholder = { Text("Search conversations…", color = TextHint) }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), modifier = Modifier.fillMaxWidth())
                    else Text("Messages", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                },
                actions = {
                    IconButton({ showSearch = !showSearch; if (!showSearch) search = "" }) { Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, null) }
                    if (!showSearch) {
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, null) }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf)) {
                                DropdownMenuItem({ Text("Starred messages", color = TextPrimary) }, { showMenu = false; onStarred() }, leadingIcon = { Icon(Icons.Default.Star, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Archived", color = TextPrimary) }, { showMenu = false; onArchived() }, leadingIcon = { Icon(Icons.Default.Archive, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Mark all read", color = TextPrimary) }, {
                                    showMenu = false
                                    scope.launch(Dispatchers.IO) { list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) } }
                                    refresh()
                                }, leadingIcon = { Icon(Icons.Default.DoneAll, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Settings", color = TextPrimary) }, { showMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Admin Panel", color = TextPrimary) }, { showMenu = false; onAdmin() }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null, tint = TextSecond) })
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = Brand, contentColor = if (D) Color(0xFF003258) else Color.White, shape = RoundedCornerShape(16.dp),
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
                            SwipeableConvRow(conv, PinStore.isPinned(ctx, conv.threadId), onOpen, onRefresh = { refresh(); tick++ }, onLongPress = { ctxConv = it })
                            if (filtered.last() != conv) HorizontalDivider(Modifier.padding(start = 76.dp), thickness = 0.5.dp, color = DivLt)
                        }
                    }
                }
            }
        }
    }
}

// ── Swipeable conversation row (swipe action from Settings) ─────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConvRow(c: Conversation, pinned: Boolean, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit, onLongPress: (Conversation) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = 150f
    val swipeAction = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("swipe_action", "archive") ?: "archive" }
    val actionLabel = when (swipeAction) { "delete" -> "Delete"; "read" -> "Read"; "none" -> ""; else -> "Archive" }
    val actionColor = when (swipeAction) { "delete" -> Red; else -> Color(0xFF5F6368) }

    Box(
        Modifier.fillMaxWidth().background(
            if (offsetX < -20f && swipeAction != "none") actionColor else Color.Transparent
        )
    ) {
        if (offsetX < -20f && swipeAction != "none") {
            Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(when (swipeAction) { "delete" -> Icons.Default.Delete; "read" -> Icons.Default.DoneAll; else -> Icons.Default.Archive }, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(actionLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
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
                            if (offsetX < -threshold && swipeAction != "none") {
                                when (swipeAction) {
                                    "delete" -> scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }
                                    "read" -> scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId) }
                                    else -> ArchiveStore.setArchived(ctx, c.threadId, true)
                                }
                                Toast.makeText(ctx, when (swipeAction) { "delete" -> "Deleted"; "read" -> "Marked as read"; else -> "Archived" }, Toast.LENGTH_SHORT).show()
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
            Box(Modifier.size(52.dp).clip(CircleShape).background(avatarColor(c.address)), contentAlignment = Alignment.Center) {
                Text(initial(c.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) { Icon(Icons.Default.PushPin, null, tint = TextHint, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = TextPrimary)
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

// ━━━━━━━━━━━━━━━━ ARCHIVED SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }.filter { ArchiveStore.isArchived(ctx, it.threadId) } }

    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Archived") }) }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("No archived conversations", color = TextHint) }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
            items(list, key = { it.threadId }) { conv ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(conv) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(avatarColor(conv.address)), contentAlignment = Alignment.Center) {
                        Text(initial(conv.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conv.displayName, fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(conv.snippet, fontSize = 13.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { ArchiveStore.setArchived(ctx, conv.threadId, false); tick++ }) { Text("Unarchive", color = Brand) }
                }
                HorizontalDivider(Modifier.padding(start = 78.dp), thickness = .5.dp, color = DivLt)
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ STARRED MESSAGES SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarredScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var starred by remember { mutableStateOf(emptyList<Pair<Conversation, Message>>()) }
    LaunchedEffect(Unit) {
        starred = withContext(Dispatchers.IO) {
            val convs = SmsRepository.loadConversations(ctx)
            val out = mutableListOf<Pair<Conversation, Message>>()
            for (c in convs) {
                for (m in SmsRepository.loadMessages(ctx, c.threadId)) {
                    if (StarStore.isStarred(ctx, msgKey(m))) out.add(c to m)
                }
            }
            out.sortedByDescending { it.second.date }
        }
    }

    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Starred messages") }) }
    ) { pad ->
        if (starred.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Star, null, tint = TextHint, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No starred messages", color = TextHint)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf)) {
            items(starred) { (conv, m) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(conv.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextSecond)
                        Spacer(Modifier.weight(1f))
                        Text(shortTime(m.date), fontSize = 11.sp, color = TextHint)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(m.body.ifBlank { if (m.imageUri != null) "📷 Media" else " " }, fontSize = 15.sp, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(thickness = .5.dp, color = DivLt)
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
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (isGroup) BrandSurf else BrandLt2, contentColor = TextPrimary)) {
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
                            colors = AssistChipDefaults.assistChipColors(containerColor = BrandSurf, labelColor = TextPrimary))
                    }
                    if (selected.size >= 2) {
                        Button(onClick = {
                            val addr = selected.joinToString(";") { it.second }
                            val names = selected.joinToString(", ") { it.first }
                            onOpen(Conversation(addr.hashCode().toLong(), addr, names, "", System.currentTimeMillis(), false))
                        }, colors = ButtonDefaults.buttonColors(Brand, contentColor = if (D) Color(0xFF003258) else Color.White), shape = RoundedCornerShape(20.dp)) { Text("Start", fontSize = 13.sp) }
                    }
                }
            }
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("To: name or number") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand),
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
                        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary); Text(number, fontSize = 13.sp, color = TextHint) }
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
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
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
    var isRecording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var reactTick by remember { mutableIntStateOf(0) }

    fun reload() {
        val raw = SmsRepository.loadMessages(ctx, conversation.threadId)
        msgs = raw.filter { it.body.isNotBlank() || it.imageUri != null }
        scope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    }

    fun doSend() {
        val body = draft.trim()
        if (pendingMediaUri != null) {
            val uri = pendingMediaUri!!
            val mime = when (pendingMediaType) { "video" -> "video/mp4"; "audio" -> "audio/mp4"; else -> null }
            scope.launch(Dispatchers.IO) {
                val ok = SmsRepository.sendMms(ctx, conversation.address, uri, body, mime)
                withContext(Dispatchers.Main) {
                    if (ok) { draft = ""; pendingMediaUri = null; replyTo = null; keyboard?.hide(); reload() }
                    else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (body.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendSms(ctx, conversation.address, body)
            withContext(Dispatchers.Main) { if (ok) { draft = ""; replyTo = null; keyboard?.hide(); reload() } else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
        }
    }

    fun pickScheduleTime() {
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
                        ScheduledSendReceiver.schedule(ctx, conversation.address, body, t.timeInMillis)
                        draft = ""
                        Toast.makeText(ctx, "Scheduled for " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(t.time), Toast.LENGTH_LONG).show()
                    }
                }
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
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
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { pendingMediaUri = it; pendingMediaType = "image"; showAttach = false }
    }
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let {
            runCatching {
                ctx.contentResolver.query(it, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val name = c.getString(0) ?: ""
                        var number = ""
                        ctx.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " = ?", arrayOf(name), null
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
    LaunchedEffect(Unit) { reload(); SmsRepository.markThreadRead(ctx, conversation.threadId) }
    LaunchedEffect(Unit) { while (true) { delay(3000); reload() } }
    DisposableEffect(Unit) { onDispose { if (isRecording) stopRecording(false) } }

    // Context menu dialog (long-press on message)
    if (ctxMsg != null) {
        val m = ctxMsg!!
        val key = msgKey(m)
        val starred = StarStore.isStarred(ctx, key)
        AlertDialog(
            onDismissRequest = { ctxMsg = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(m.body.take(60).ifBlank { "Message" }, fontSize = 14.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("😂", "👍", "❤️", "😮", "😢", "😡").forEach { emoji ->
                            Box(Modifier.size(44.dp).clip(CircleShape).background(BrandLt2).clickable {
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
                    MenuBtn(Icons.Default.Reply, "Reply") { replyTo = m; ctxMsg = null }
                    MenuBtn(Icons.Default.ContentCopy, "Copy") {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.body))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Forward, "Forward") {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, m.body) }, "Forward"))
                        ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Star, if (starred) "Unstar" else "Star", Color(0xFFFFC107)) {
                        StarStore.toggle(ctx, key); reactTick++
                        Toast.makeText(ctx, if (starred) "Unstarred" else "Starred", Toast.LENGTH_SHORT).show(); ctxMsg = null
                    }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m.id) }
                        ctxMsg = null; reload()
                    }
                }
            },
            confirmButton = { TextButton({ ctxMsg = null }) { Text("Cancel", color = Brand) } }
        )
    }

    Scaffold(containerColor = BrandSurf,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextPrimary),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat…", color = TextHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), modifier = Modifier.fillMaxWidth())
                    } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conversation.address}")))
                    }) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(conversation.address)), contentAlignment = Alignment.Center) {
                            Text(initial(conversation.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, color = TextPrimary)
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
                                DropdownMenuItem({ Text("Search", color = TextPrimary) }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Schedule send", color = TextPrimary) }, { showMenu = false; pickScheduleTime() }, leadingIcon = { Icon(Icons.Default.Schedule, null, tint = TextSecond) })
                                DropdownMenuItem({ Text(if (prefs.getBoolean("muted_${conversation.threadId}", false)) "Unmute" else "Mute", color = TextPrimary) }, {
                                    showMenu = false
                                    val k = "muted_${conversation.threadId}"
                                    prefs.edit().putBoolean(k, !prefs.getBoolean(k, false)).apply()
                                }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Block & report", color = TextPrimary) }, {
                                    showMenu = false; BlockedStore.block(ctx, conversation.address)
                                    Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Block, null, tint = Red) })
                                DropdownMenuItem({ Text("Delete conversation", color = TextPrimary) }, {
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
                    if (isRecording) {
                        Row(Modifier.fillMaxWidth().background(BrandLt2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Red))
                            Spacer(Modifier.width(10.dp))
                            Text("Recording voice note…", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                            TextButton(onClick = { stopRecording(false) }) { Text("Discard", color = Red) }
                            TextButton(onClick = { stopRecording(true) }) { Text("Done", color = Brand) }
                        }
                    }
                    if (pendingMediaUri != null) {
                        Row(Modifier.fillMaxWidth().background(BrandLt2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (pendingMediaType) {
                                "image" -> AsyncImage(pendingMediaUri, "preview", Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                "video" -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(TextPrimary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                                else -> Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Brand), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(when (pendingMediaType) { "image" -> "Photo ready"; "video" -> "Video ready"; else -> "Voice note ready" }, fontSize = 14.sp, color = TextSecond, modifier = Modifier.weight(1f))
                            IconButton({ pendingMediaUri = null }) { Icon(Icons.Default.Close, null, tint = Red) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { showAttach = !showAttach; showEmoji = false }, Modifier.size(44.dp).clip(CircleShape).background(Brand)) {
                            Icon(if (showAttach) Icons.Default.Close else Icons.Default.Add, null, tint = if (D) Color(0xFF003258) else Color.White, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            draft, { draft = it }, Modifier.weight(1f),
                            placeholder = { Text("Message", color = TextHint) },
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand),
                            leadingIcon = { IconButton({ showEmoji = !showEmoji; showAttach = false }) { Icon(Icons.Default.EmojiEmotions, null, tint = TextHint) } },
                            trailingIcon = { IconButton({ imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, null, tint = TextHint) } },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            maxLines = 4
                        )
                        Spacer(Modifier.width(6.dp))
                        val hasCont = draft.isNotBlank() || pendingMediaUri != null
                        FloatingActionButton(
                            onClick = {
                                if (hasCont) doSend()
                                else audioPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.size(46.dp),
                            containerColor = if (hasCont) Brand else BrandLt2,
                            contentColor = if (hasCont) (if (D) Color(0xFF003258) else Color.White) else Brand,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(0.dp)
                        ) { Icon(if (hasCont) Icons.Default.Send else Icons.Default.Mic, null, Modifier.size(22.dp)) }
                    }
                    AnimatedVisibility(showAttach, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        LazyVerticalGrid(
                            GridCells.Fixed(4),
                            Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(BrandSurf).padding(horizontal = 12.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item { AttachBtn(Icons.Default.Image, "Gallery") { imagePicker.launch("image/*") } }
                            item { AttachBtn(Icons.Default.CameraAlt, "Camera") {
                                val uri = runCatching {
                                    val values = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "heimish_${System.currentTimeMillis()}.jpg")
                                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                                    }
                                    ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                }.getOrNull()
                                if (uri != null) { cameraUri = uri; runCatching { cameraLauncher.launch(uri) } }
                                else Toast.makeText(ctx, "Camera unavailable", Toast.LENGTH_SHORT).show()
                            } }
                            item { AttachBtn(Icons.Default.Videocam, "Video") { videoPicker.launch("video/*") } }
                            item { AttachBtn(Icons.Default.Mic, "Voice note") { showAttach = false; audioPerm.launch(android.Manifest.permission.RECORD_AUDIO) } }
                            item { AttachBtn(Icons.Default.InsertDriveFile, "Files") { filePicker.launch("*/*") } }
                            item { AttachBtn(Icons.Default.LocationOn, "Location") { locPerm.launch(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) } }
                            item { AttachBtn(Icons.Default.Contacts, "Contact") { contactPicker.launch(null) } }
                            item { AttachBtn(Icons.Default.Schedule, "Schedule") { showAttach = false; pickScheduleTime() } }
                        }
                    }
                    AnimatedVisibility(showEmoji, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        EmojiPicker { draft += it; showEmoji = false }
                    }
                }
            }
        },
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
                items(dayMsgs, key = { msgKey(it) }) { m ->
                    SwipeToReplyBubble(m, reactTick, onReply = { replyTo = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctxMsg = m })
                }
            }
            if (msgs.isNotEmpty() && msgs.last().incoming) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("OK!", "👍", "Yes!", "Sounds good").forEach { reply ->
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
fun SwipeToReplyBubble(m: Message, reactTick: Int, onReply: () -> Unit, onLongClick: () -> Unit) {
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
            MessageBubble(m, reactTick, onLongClick)
        }
    }
}

// ── Attachment button (pill-shaped, monochrome — M3 Expressive style) ────────
@Composable
fun AttachBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AttachSurf,
            modifier = Modifier.size(width = 56.dp, height = 48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = AttachIcon, modifier = Modifier.size(22.dp))
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
        "😀","😂","🥹","😍","🤔","😎","🙏","❤️","👍","👎",
        "🔥","🎉","😢","😡","🤣","😊","🥰","😘","🤗","😏",
        "😭","😤","🤯","🫡","💀","✨","💪","🙄","😴","🤝",
        "👋","🫦","💯","🎁","📱","✅","❌","⭐","🌙","☀️",
        "🍕","🍔","☕","🍎","🌹","🕊️","🇺🇸","🇮🇱","📖","✍️",
        "😇","🤩","😜","🫣","🥺","😤","🙃","😬","🤫","🫠",
        "💔","💕","💖","🫠","👏","🤞","✌️","🤟","🫵","👀"
    )
    Surface(color = BrandSurf, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
            items(emojis) { e ->
                Box(Modifier.size(44.dp).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
private val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, reactTick: Int, onLongClick: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val isIn = m.incoming
    val bubbleColor = remember(reactTick) {
        Color(prefs.getInt("bubble_color", BubbleOut.toArgb()))
    }
    val outColor = if (isIn) BubbleIn else bubbleColor
    val customOut = prefs.contains("bubble_color")
    val outText = if (isIn) TextPrimary else if (customOut) Color.White else BubbleOutText
    val outSub = if (isIn) TextHint else if (customOut) Color.White.copy(.7f) else BubbleOutSub
    val linkColor = if (isIn) Brand else if (customOut) Color(0xFFBBDEFB) else BubbleOutLink
    val previewBg = if (isIn) (if (D) Color(0xFF5F6368) else Color(0xFFD5D5D5)) else if (customOut) BrandDk else (if (D) Color(0xFF7CACF8) else BrandDk)
    val textSize = when (prefs.getString("text_size", "default")) {
        "small" -> 13.sp; "large" -> 17.sp; "largest" -> 19.sp; else -> 15.sp
    }
    val previewMode = prefs.getString("preview_mode", "links") ?: "links"
    val reaction = remember(reactTick) { ReactionStore.get(ctx, msgKey(m)) }
    val starred = remember(reactTick) { StarStore.isStarred(ctx, msgKey(m)) }
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomEnd = if (isIn) 20.dp else 4.dp,
        bottomStart = if (isIn) 4.dp else 20.dp
    )
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick, indication = null, interactionSource = remember { MutableInteractionSource() }),
            horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
        ) {
            if (!isIn) Spacer(Modifier.width(56.dp))
            Box(Modifier.widthIn(max = 300.dp).clip(shape).background(outColor)) {
                Column {
                    if (m.imageUri != null) {
                        val mime = m.mediaMime ?: "image/"
                        when {
                            mime.startsWith("video/") -> Box(Modifier.fillMaxWidth().height(180.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            mime.startsWith("audio/") -> Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mic, null, tint = outText, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Voice note", color = outText, fontSize = textSize)
                            }
                            else -> AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(
                                if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            ), contentScale = ContentScale.Crop)
                        }
                    }
                    if (m.body.isNotBlank()) {
                        val urls = URL_REGEX.findAll(m.body).toList()
                        if (urls.isEmpty()) {
                            Text(m.body, color = outText, fontSize = textSize, lineHeight = 21.sp,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp))
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
                            ClickableText(annotated, style = androidx.compose.ui.text.TextStyle(fontSize = textSize, lineHeight = 21.sp),
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp),
                                onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item))) } })
                            if (previewMode != "none") {
                                val url = urls.first().value
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
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 2.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (starred) {
                            Icon(Icons.Default.Star, null, tint = if (isIn) Color(0xFFFFC107) else outSub, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(msgTime(m.date), fontSize = 11.sp, color = outSub)
                        if (!isIn) {
                            Text(if (m.isMms) " · MMS " else " · SMS ", fontSize = 11.sp, color = outSub)
                            Icon(Icons.Default.DoneAll, null, tint = outSub, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            if (isIn) Spacer(Modifier.width(56.dp))
        }
        if (reaction != null) {
            Box(
                Modifier.align(if (isIn) Alignment.BottomStart else Alignment.BottomEnd)
                    .padding(start = if (isIn) 8.dp else 0.dp, end = if (isIn) 0.dp else 8.dp)
                    .offset(y = 4.dp)
                    .clip(CircleShape).background(Surf).border(0.5.dp, DivLt, CircleShape)
                    .padding(3.dp)
            ) { Text(reaction, fontSize = 13.sp) }
        }
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
            item { SettRow(Icons.Default.Palette, "Theme", "System, light or dark") { onSub("theme") } }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Default") { onSub("textsize") } }
            item { HorizontalDivider(color = DivLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Web link previews") { onSub("previews") } }
            item { SettRow(Icons.Default.Shield, "Protection & Safety", "Spam, blocked contacts") { onSub("safety") } }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "Archive on swipe") { onSub("swipe") } }
            item { HorizontalDivider(color = DivLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Tune, "Advanced", "Delivery reports, MMS") { onSub("advanced") } }
            item { SettRow(Icons.Default.Info, "About", "Version 3.4") { onSub("about") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSubScreen(key: String, onThemeChange: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val title = when (key) { "notifications" -> "Notifications"; "bubbles" -> "Bubbles"; "theme" -> "Theme"; "textsize" -> "Text size"
        "previews" -> "Previews"; "safety" -> "Safety"; "swipe" -> "Swipe actions"; "advanced" -> "Advanced"; "about" -> "About"; else -> "Settings" }
    var blockedTick by remember { mutableIntStateOf(0) }
    val blocked = remember(blockedTick) { BlockedStore.getAll(ctx).toList() }
    var themeNow by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }

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
                        }, colors = ButtonDefaults.buttonColors(Brand, contentColor = if (D) Color(0xFF003258) else Color.White), shape = RoundedCornerShape(20.dp)) {
                            Text("Test notification")
                        }
                    }
                }
                "bubbles" -> {
                    item { Text("Chat bubble color", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = TextPrimary); Spacer(Modifier.height(12.dp)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(Color(0xFF0B57D0), Color(0xFF1E8E3E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFD93025), Color(0xFF185ABC), Color(0xFFE65100), Color(0xFF00897B)).forEach { clr ->
                                Box(Modifier.size(44.dp).clip(CircleShape).background(clr).clickable {
                                    prefs.edit().putInt("bubble_color", clr.toArgb()).apply()
                                    Toast.makeText(ctx, "Color updated — open a chat to see it", Toast.LENGTH_SHORT).show()
                                })
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = {
                            prefs.edit().remove("bubble_color").apply()
                            Toast.makeText(ctx, "Back to theme default", Toast.LENGTH_SHORT).show()
                        }) { Text("Reset to theme default", color = Brand) }
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("Preview", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextHint)
                        Spacer(Modifier.height(8.dp))
                        val pvColor = if (prefs.contains("bubble_color")) Color(prefs.getInt("bubble_color", BubbleOut.toArgb())) else BubbleOut
                        val pvText = if (prefs.contains("bubble_color")) Color.White else BubbleOutText
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(pvColor).padding(12.dp)) {
                            Column {
                                Text("Hello!", color = pvText, fontSize = 15.sp)
                                Text("3:25 PM · SMS ✓✓", color = pvText.copy(.7f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
                            }
                        }
                    }
                }
                "theme" -> {
                    item { RadioRow("System default", themeNow == "system") { prefs.edit().putString("theme", "system").apply(); themeNow = "system"; onThemeChange("system") } }
                    item { RadioRow("Light", themeNow == "light") { prefs.edit().putString("theme", "light").apply(); themeNow = "light"; onThemeChange("light") } }
                    item { RadioRow("Dark", themeNow == "dark") { prefs.edit().putString("theme", "dark").apply(); themeNow = "dark"; onThemeChange("dark") } }
                }
                "textsize" -> {
                    item { RadioRow("Small", prefs.getString("text_size", "default") == "small") { prefs.edit().putString("text_size", "small").apply() } }
                    item { RadioRow("Default", prefs.getString("text_size", "default") == "default") { prefs.edit().putString("text_size", "default").apply() } }
                    item { RadioRow("Large", prefs.getString("text_size", "default") == "large") { prefs.edit().putString("text_size", "large").apply() } }
                    item { RadioRow("Largest", prefs.getString("text_size", "default") == "largest") { prefs.edit().putString("text_size", "largest").apply() } }
                    item { Spacer(Modifier.height(8.dp)); Text("Applies to message bubbles.", fontSize = 13.sp, color = TextHint) }
                }
                "previews" -> {
                    item { RadioRow("All previews", prefs.getString("preview_mode", "links") == "all") { prefs.edit().putString("preview_mode", "all").apply() } }
                    item { RadioRow("Links only", prefs.getString("preview_mode", "links") == "links") { prefs.edit().putString("preview_mode", "links").apply() } }
                    item { RadioRow("None", prefs.getString("preview_mode", "links") == "none") { prefs.edit().putString("preview_mode", "none").apply() } }
                }
                "safety" -> {
                    item { ToggleRow("Spam protection", "spam_protect", prefs) }
                    item { ToggleRow("Warn about suspicious links", "warn_links", prefs) }
                    item { ToggleRow("Blur sensitive images", "blur_sensitive", prefs) }
                    item {
                        Spacer(Modifier.height(16.dp))
                        Text("Blocked numbers", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (blocked.isEmpty()) {
                        item { Text("No blocked numbers", color = TextHint, fontSize = 13.sp) }
                    } else {
                        items(blocked, key = { it }) { num ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(num, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                TextButton(onClick = { BlockedStore.unblock(ctx, num); blockedTick++ }) { Text("Unblock", color = Brand) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(4.dp)); Text("Blocked numbers are hidden and won't notify you.", fontSize = 12.sp, color = TextHint) }
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
                        val scope = rememberCoroutineScope()
                        var showConfirm by remember { mutableStateOf(false) }
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showConfirm = true }, colors = ButtonDefaults.buttonColors(Red), shape = RoundedCornerShape(20.dp)) {
                            Text("Delete all conversations")
                        }
                        if (showConfirm) {
                            AlertDialog(
                                onDismissRequest = { showConfirm = false },
                                containerColor = Surf,
                                shape = RoundedCornerShape(28.dp),
                                title = { Text("Delete all conversations?", color = TextPrimary) },
                                text = { Text("This permanently deletes every SMS and MMS on this device. This can't be undone.", color = TextSecond) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showConfirm = false
                                        scope.launch(Dispatchers.IO) { SmsRepository.deleteAllConversations(ctx) }
                                        Toast.makeText(ctx, "All conversations deleted", Toast.LENGTH_SHORT).show()
                                    }) { Text("Delete", color = Red) }
                                },
                                dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel", color = Brand) } }
                            )
                        }
                    }
                }
                "about" -> {
                    item { Text("Heimish Messages", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary) }
                    item { Text("Version 3.4", fontSize = 14.sp, color = TextHint); Spacer(Modifier.height(16.dp)) }
                    item { Text("A heimishe messaging app for the community.", fontSize = 14.sp, color = TextSecond) }
                    item { Spacer(Modifier.height(16.dp)); Text("© 2024-2026 Heimish Messages", fontSize = 13.sp, color = TextHint) }
                }
            }
        }
    }
}

@Composable fun ToggleRow(label: String, key: String, prefs: android.content.SharedPreferences) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, true)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = TextPrimary); Switch(on, { on = it; prefs.edit().putBoolean(key, it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Brand))
    }
}
@Composable fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Brand)); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 15.sp, color = TextPrimary)
    }
}
@Composable fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextSecond, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp, color = TextPrimary); Text(sub, fontSize = 13.sp, color = TextHint) }
        Icon(Icons.Default.ChevronRight, null, tint = TextHint)
    }
}

// ━━━━━━━━━━━━━━━━ ADMIN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var resLines by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val configured = BuildConfig.WORKER_URL.isNotBlank() && BuildConfig.ADMIN_TOKEN.isNotBlank()

    fun search(query: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("${BuildConfig.WORKER_URL}/contacts?q=${java.net.URLEncoder.encode(query, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.ADMIN_TOKEN}")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val r = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                val json = org.json.JSONObject(r)
                val arr = json.optJSONArray("contacts")
                val lines = mutableListOf<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    lines.add("${c.optString("name")}  ·  ${c.optString("addr", c.optString("phone"))}" + (if (c.has("_device")) "  (${c.optString("_device")})" else ""))
                }
                withContext(Dispatchers.Main) {
                    resLines = lines
                    status = "${json.optInt("results")} of ${json.optInt("total")} contacts"
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { resLines = emptyList(); status = "Error: ${e.message}" } }
        }
    }

    Scaffold(containerColor = BrandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(BrandSurf, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } }, title = { Text("Admin Panel") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(Surf).padding(16.dp)) {
            item {
                Text("CONTACT SEARCH", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextHint, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                if (!configured) {
                    Text("Contact search is not configured in this build.", fontSize = 14.sp, color = TextSecond)
                } else {
                    OutlinedTextField(q, {
                        q = it; if (it.isNotBlank()) search(it) else { resLines = emptyList(); status = "" }
                    }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or number…", color = TextHint) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = BrandLt2, unfocusedContainerColor = BrandLt2, focusedBorderColor = Brand, unfocusedBorderColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand))
                }
                Spacer(Modifier.height(12.dp))
            }
            items(resLines) { line -> Text(line, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 4.dp)) }
            if (status.isNotBlank()) item { Text(status, fontSize = 12.sp, color = TextHint, modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Spacer(Modifier.height(24.dp)); Text("DEVICE INFO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextHint, letterSpacing = 1.sp); Spacer(Modifier.height(8.dp))
                Text("Model: ${Build.MODEL}", fontSize = 14.sp, color = TextPrimary)
                Text("Android: ${Build.VERSION.RELEASE}", fontSize = 14.sp, color = TextSecond)
                Text("Default SMS: ${isDefaultSmsApp(ctx)}", fontSize = 14.sp, color = TextSecond); Spacer(Modifier.height(12.dp))
                if (configured) {
                    Button({ ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing…", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(Brand, contentColor = if (D) Color(0xFF003258) else Color.White), shape = RoundedCornerShape(20.dp)) { Text("Sync contacts") }
                }
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
fun avatarColor(seed: String): Color = palette[seed.hashCode().mod(palette.size)]
fun initial(name: String): String = (name.firstOrNull()?.uppercaseChar() ?: '#').toString()
fun msgTime(ms: Long): String { if (ms <= 0) return ""; return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) }
fun shortTime(ms: Long): String {
    if (ms <= 0) return ""; val n = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    return if (n.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) && n.get(Calendar.YEAR) == t.get(Calendar.YEAR)) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
    else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
}
