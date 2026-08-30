package com.heimish.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
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
import org.json.JSONArray
import org.json.JSONObject

// ── Google Messages Material 3 Expressive Colors (EXACT match) ───────────────
private val Brand       = Color(0xFF0B57D0)
private val BrandDk     = Color(0xFF0842A0)
private val BrandSurf   = Color(0xFFD3E3FD)
private val BrandLt     = Color(0xFFD3E3FD)
private val BrandLt2    = Color(0xFFEDF2FA)
private val BubbleIn    = Color(0xFFE3E3E3)
private val BubbleOut   = Color(0xFFD3E3FD)
private val BgSurf      = Color(0xFFF6F8FC)
private val Surf        = Color.White
private val TextPrimary = Color(0xFF1F1F1F)
private val TextSecond  = Color(0xFF444746)
private val TextHint    = Color(0xFF747775)
private val DivClr      = Color(0xFFC4C7C5)
private val DivLt       = Color(0xFFE8EAED)
private val Red         = Color(0xFFB3261E)
private val Green       = Color(0xFF146C2E)

// Dark theme colors
private val DkBrand     = Color(0xFFA8C7FA)
private val DkBrandSurf = Color(0xFF1A2744)
private val DkBrandLt   = Color(0xFF1A2744)
private val DkBrandLt2  = Color(0xFF1F2937)
private val DkBubbleIn  = Color(0xFF303030)
private val DkBubbleOut = Color(0xFF004A77)
private val DkBgSurf    = Color(0xFF1C1B1F)
private val DkSurf      = Color(0xFF1C1B1F)
private val DkTextPri   = Color(0xFFE3E3E3)
private val DkTextSec   = Color(0xFFC4C7C5)
private val DkTextHint  = Color(0xFF8E918F)
private val DkDivClr    = Color(0xFF444746)
private val DkDivLt     = Color(0xFF303030)

private val GMColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = Brand, background = BgSurf,
    surface = Surf, onSurface = TextPrimary, outline = DivClr,
    surfaceVariant = BrandLt2
)

private val GMDarkColors = darkColorScheme(
    primary = DkBrand, onPrimary = Color.Black,
    secondary = DkBrand, background = DkBgSurf,
    surface = DkSurf, onSurface = DkTextPri, outline = DkDivClr,
    surfaceVariant = DkBrandLt2
)

object ThemeState {
    var isDark by mutableStateOf(false)
    val brand get() = if (isDark) DkBrand else Brand
    val brandDk get() = if (isDark) Color(0xFF7EAAEE) else BrandDk
    val brandSurf get() = if (isDark) DkBrandSurf else BrandSurf
    val brandLt get() = if (isDark) DkBrandLt else BrandLt
    val brandLt2 get() = if (isDark) DkBrandLt2 else BrandLt2
    val bubbleIn get() = if (isDark) DkBubbleIn else BubbleIn
    val bubbleOut get() = if (isDark) DkBubbleOut else BubbleOut
    val bgSurf get() = if (isDark) DkBgSurf else BgSurf
    val surf get() = if (isDark) DkSurf else Surf
    val textPrimary get() = if (isDark) DkTextPri else TextPrimary
    val textSecond get() = if (isDark) DkTextSec else TextSecond
    val textHint get() = if (isDark) DkTextHint else TextHint
    val divClr get() = if (isDark) DkDivClr else DivClr
    val divLt get() = if (isDark) DkDivLt else DivLt
}

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val theme = prefs.getString("theme", "system") ?: "system"
        ThemeState.isDark = when (theme) {
            "dark" -> true
            "light" -> false
            else -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        window.statusBarColor = ThemeState.brandSurf.toArgb()
        SmsSyncService.start(this)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNow(this)
        setContent {
            MaterialTheme(colorScheme = if (ThemeState.isDark) GMDarkColors else GMColors) {
                Surface(Modifier.fillMaxSize(), color = ThemeState.brandSurf) { AppRoot(isDefaultSmsApp(this)) { requestDefault() } }
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
    data class ContactDetail(val conv: Conversation) : Screen()
    data object NewConv : Screen()
    data object Settings : Screen()
    data class SettingSub(val key: String) : Screen()
    data object Admin : Screen()
    data class ImagePreview(val conv: Conversation, val uri: Uri, val mediaType: String = "image") : Screen()
    data class ForwardMsg(val body: String) : Screen()
    data object Archived : Screen()
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
            is Screen.Chat, Screen.NewConv, Screen.Settings, Screen.Admin, Screen.Archived -> Screen.List
            is Screen.ContactDetail -> Screen.Chat((screen as Screen.ContactDetail).conv)
            is Screen.ImagePreview -> Screen.Chat((screen as Screen.ImagePreview).conv)
            is Screen.ForwardMsg -> Screen.List
            is Screen.SettingSub -> Screen.Settings
            else -> Screen.List
        }
    }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPerms { permL.launch(Permissions.ALL) }
        else -> {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    val goingDeeper = when (targetState) {
                        Screen.List -> false
                        is Screen.Chat, Screen.NewConv, Screen.Settings, Screen.Admin, Screen.Archived ->
                            initialState is Screen.List
                        else -> true
                    }
                    if (goingDeeper) {
                        (slideInHorizontally(tween(150)) { it / 4 } + fadeIn(tween(120)))
                            .togetherWith(slideOutHorizontally(tween(150)) { -it / 6 } + fadeOut(tween(100)))
                    } else {
                        (slideInHorizontally(tween(150)) { -it / 6 } + fadeIn(tween(120)))
                            .togetherWith(slideOutHorizontally(tween(150)) { it / 4 } + fadeOut(tween(100)))
                    }
                },
                label = "nav"
            ) { s ->
                when (s) {
                    Screen.List     -> ConvListScreen({ screen = Screen.Chat(it) }, { screen = Screen.NewConv }, { screen = Screen.Settings }, { screen = Screen.Admin }, { screen = Screen.Archived })
                    is Screen.Chat  -> ThreadScreen(s.conv,
                        onDetails = { screen = Screen.ContactDetail(s.conv) },
                        onImagePreview = { uri, type -> screen = Screen.ImagePreview(s.conv, uri, type) },
                        onForward = { body -> screen = Screen.ForwardMsg(body) }) { screen = Screen.List }
                    is Screen.ContactDetail -> ContactDetailScreen(s.conv) { screen = Screen.Chat(s.conv) }
                    Screen.NewConv  -> NewConvScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
                    Screen.Settings -> SettingsScreen({ screen = Screen.SettingSub(it) }) { screen = Screen.List }
                    is Screen.SettingSub -> SettingSubScreen(s.key) { screen = Screen.Settings }
                    Screen.Admin    -> AdminScreen { screen = Screen.List }
                    is Screen.ImagePreview -> ImagePreviewScreen(s.conv, s.uri, s.mediaType) { screen = Screen.Chat(s.conv) }
                    is Screen.ForwardMsg -> ForwardScreen(s.body) { screen = Screen.List }
                    Screen.Archived -> ArchivedScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
                }
            }
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

// ── Archive helpers ──────────────────────────────────────────────────────────
object ArchiveStore {
    private const val KEY = "archived_threads"
    fun get(ctx: Context): Set<Long> {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }
    fun add(ctx: Context, threadId: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(threadId.toString())
        prefs.edit().putStringSet(KEY, set).apply()
    }
    fun remove(ctx: Context, threadId: Long) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.remove(threadId.toString())
        prefs.edit().putStringSet(KEY, set).apply()
    }
}

// ── Starred messages store ──────────────────────────────────────────────────
object StarredStore {
    private const val KEY = "starred_messages"
    fun get(ctx: Context): Set<Long> {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
    }
    fun toggle(ctx: Context, msgId: Long): Boolean {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val added = if (set.contains(msgId.toString())) { set.remove(msgId.toString()); false } else { set.add(msgId.toString()); true }
        prefs.edit().putStringSet(KEY, set).apply()
        return added
    }
    fun isStarred(ctx: Context, msgId: Long): Boolean = msgId.toString() in (ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet())
}

// ── Reaction store (local-only emoji reactions) ────────────────────────────
object ReactionStore {
    private const val PREFIX = "reaction_"
    fun get(ctx: Context, msgId: Long): String? {
        return ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("$PREFIX$msgId", null)
    }
    fun set(ctx: Context, msgId: Long, emoji: String) {
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putString("$PREFIX$msgId", emoji).apply()
    }
    fun remove(ctx: Context, msgId: Long) {
        ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().remove("$PREFIX$msgId").apply()
    }
}

// ━━━━━━━━━━━━━━━━ ADMIN EMAIL GATE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
object AdminEmails {
    private const val KEY = "admin_emails"
    private const val USER_EMAIL_KEY = "user_email"
    private val DEFAULT_ADMIN = "avrumy5872877@gmail.com"

    fun getAdminEmails(ctx: Context): Set<String> {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, null) ?: setOf(DEFAULT_ADMIN)
    }

    fun addAdmin(ctx: Context, email: String) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = getAdminEmails(ctx).toMutableSet()
        set.add(email.trim().lowercase())
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun removeAdmin(ctx: Context, email: String) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val set = getAdminEmails(ctx).toMutableSet()
        set.remove(email.trim().lowercase())
        if (set.isEmpty()) set.add(DEFAULT_ADMIN)
        prefs.edit().putStringSet(KEY, set).apply()
    }

    fun isAdmin(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        val userEmail = prefs.getString(USER_EMAIL_KEY, "") ?: ""
        if (userEmail.isBlank()) return false
        return userEmail.trim().lowercase() in getAdminEmails(ctx).map { it.trim().lowercase() }
    }

    fun getUserEmail(ctx: Context): String {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        return prefs.getString(USER_EMAIL_KEY, "") ?: ""
    }

    fun setUserEmail(ctx: Context, email: String) {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(USER_EMAIL_KEY, email.trim()).apply()
    }
}

// ━━━━━━━━━━━━━━━━ CONVERSATION LIST ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit, onArchived: () -> Unit = {}) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var ctxConv by remember { mutableStateOf<Conversation?>(null) }
    val scope = rememberCoroutineScope()
    val archived = remember { mutableStateOf(ArchiveStore.get(ctx)) }

    fun refresh() { scope.launch { list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) } } }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(Unit) { while (true) { delay(30_000); val fresh = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; if (fresh != list) list = fresh } }

    val visible = list.filter { it.threadId !in archived.value }
    val filtered = if (search.isBlank()) visible else visible.filter { it.displayName.contains(search, true) || it.address.contains(search, true) || it.snippet.contains(search, true) }

    // Long-press context menu dialog
    if (ctxConv != null) {
        val c = ctxConv!!
        AlertDialog(
            onDismissRequest = { ctxConv = null },
            containerColor = ThemeState.surf,
            shape = RoundedCornerShape(28.dp),
            title = { Text(c.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ThemeState.textPrimary) },
            text = {
                Column {
                    MenuBtn(Icons.Default.PushPin, "Pin") { Toast.makeText(ctx, "Pinned", Toast.LENGTH_SHORT).show(); ctxConv = null }
                    MenuBtn(Icons.Default.Archive, "Archive") {
                        ArchiveStore.add(ctx, c.threadId); archived.value = ArchiveStore.get(ctx)
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

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = {
            Column(Modifier.background(ThemeState.brandSurf).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (showSearch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ showSearch = false; search = "" }) { Icon(Icons.Default.ArrowBack, null, tint = ThemeState.textPrimary) }
                        TextField(search, { search = it }, placeholder = { Text("Search conversations…", color = ThemeState.textHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = ThemeState.brand),
                            modifier = Modifier.weight(1f))
                    }
                } else {
                    Surface(onClick = { showSearch = true }, shape = RoundedCornerShape(28.dp), color = ThemeState.brandLt2, tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Search, null, tint = ThemeState.textHint, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Search messages", color = ThemeState.textHint, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Box {
                                IconButton({ showMenu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, null, tint = ThemeState.textPrimary) }
                                DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(ThemeState.surf)) {
                                    DropdownMenuItem({ Text("Settings") }, { showMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null) })
                                    if (AdminEmails.isAdmin(ctx)) {
                                        DropdownMenuItem({ Text("Admin Panel") }, { showMenu = false; onAdmin() }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null) })
                                    }
                                    DropdownMenuItem({ Text("Archived") }, { showMenu = false; onArchived() }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                                    DropdownMenuItem({ Text("Mark all read") }, {
                                        showMenu = false
                                        scope.launch(Dispatchers.IO) { list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) } }
                                        refresh()
                                    }, leadingIcon = { Icon(Icons.Default.DoneAll, null) })
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = ThemeState.brand, contentColor = Color.White, shape = RoundedCornerShape(16.dp),
                elevation = FloatingActionButtonDefaults.elevation(6.dp, 8.dp),
                icon = { Icon(Icons.Filled.Chat, null) }, text = { Text("Start chat", fontWeight = FontWeight.SemiBold) })
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ChatBubbleOutline, null, tint = ThemeState.textHint, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (search.isBlank()) "No messages yet" else "No results", color = ThemeState.textHint)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf),
                        state = rememberLazyListState()
                    ) {
                        items(filtered, key = { it.threadId }) { conv ->
                            SwipeableConvRow(conv, onOpen, onRefresh = { refresh() }, onLongPress = { ctxConv = it },
                                onArchive = { tid -> ArchiveStore.add(ctx, tid); archived.value = ArchiveStore.get(ctx) })
                            if (filtered.last() != conv) HorizontalDivider(Modifier.padding(start = 76.dp), thickness = 0.5.dp, color = ThemeState.divLt)
                        }
                    }
                }
            }
        }
    }
}

// ── Archived conversations screen ───────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var allConvs by remember { mutableStateOf(emptyList<Conversation>()) }
    val archived = remember { mutableStateOf(ArchiveStore.get(ctx)) }
    LaunchedEffect(Unit) { allConvs = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) } }
    val visible = allConvs.filter { it.threadId in archived.value }

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Archived") }) }
    ) { pad ->
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Archive, null, tint = ThemeState.textHint, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No archived conversations", color = ThemeState.textHint, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf)) {
                items(visible, key = { it.threadId }) { conv ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(conv) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(avatarColor(conv.address)), contentAlignment = Alignment.Center) {
                            Text(initial(conv.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(conv.displayName, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = ThemeState.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(conv.snippet, fontSize = 13.sp, color = ThemeState.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = {
                            ArchiveStore.remove(ctx, conv.threadId); archived.value = ArchiveStore.get(ctx)
                            Toast.makeText(ctx, "Unarchived", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.Unarchive, null, tint = ThemeState.textSecond) }
                    }
                    HorizontalDivider(Modifier.padding(start = 78.dp), thickness = .5.dp, color = ThemeState.divLt)
                }
            }
        }
    }
}

// ── Swipeable conversation row (swipe left = archive) ────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConvRow(c: Conversation, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit, onLongPress: (Conversation) -> Unit, onArchive: (Long) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val swipeAnim = remember { Animatable(0f) }
    val threshold = 150f
    val swipeAction = remember {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        prefs.getString("swipe_action", "archive") ?: "archive"
    }

    val swipeIcon = when (swipeAction) {
        "delete" -> Icons.Default.Delete
        "read" -> Icons.Default.DoneAll
        "none" -> Icons.Default.Archive
        else -> Icons.Default.Archive
    }
    val swipeLabel = when (swipeAction) {
        "delete" -> "Delete"
        "read" -> "Read"
        "none" -> ""
        else -> "Archive"
    }
    val swipeEnabled = swipeAction != "none"
    val offsetX = swipeAnim.value

    Box(
        Modifier.fillMaxWidth().background(
            if (offsetX < -20f) when (swipeAction) {
                "delete" -> Color(0xFFB3261E)
                else -> Color(0xFF5F6368)
            } else Color.Transparent
        )
    ) {
        if (offsetX < -20f && swipeEnabled) {
            Box(Modifier.fillMaxSize().padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(swipeIcon, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(swipeLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(offsetX.roundToInt().coerceAtMost(0), 0) }
                .background(ThemeState.surf)
                .combinedClickable(
                    onClick = { onOpen(c) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(c) }
                )
                .pointerInput(c.threadId) {
                    if (swipeEnabled) detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeAnim.value < -threshold) {
                                when (swipeAction) {
                                    "archive" -> {
                                        onArchive(c.threadId)
                                        Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show()
                                    }
                                    "delete" -> {
                                        scope.launch(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }
                                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                                    }
                                    "read" -> {
                                        scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId) }
                                        Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                onRefresh()
                            }
                            scope.launch { swipeAnim.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                        },
                        onHorizontalDrag = { _, dx ->
                            val target = (swipeAnim.value + dx).coerceIn(-200f, 0f)
                            scope.launch { swipeAnim.snapTo(target) }
                        }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal, fontSize = 16.sp,
                        color = ThemeState.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(shortTime(c.date), fontSize = 12.sp, color = if (c.unread) ThemeState.brand else ThemeState.textHint)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.snippet.ifBlank { " " }, fontSize = 14.sp, color = if (c.unread) ThemeState.textPrimary else ThemeState.textHint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (c.unread) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(10.dp).clip(CircleShape).background(ThemeState.brand))
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

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("New conversation") }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                FilledTonalButton(onClick = { isGroup = !isGroup }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (isGroup) ThemeState.brandSurf else ThemeState.brandLt2)) {
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
                            colors = AssistChipDefaults.assistChipColors(containerColor = ThemeState.brandSurf))
                    }
                    if (selected.size >= 2) {
                        Button(onClick = {
                            val addr = selected.joinToString(";") { it.second }
                            val names = selected.joinToString(", ") { it.first }
                            onOpen(Conversation(addr.hashCode().toLong(), addr, names, "", System.currentTimeMillis(), false))
                        }, colors = ButtonDefaults.buttonColors(ThemeState.brand), shape = RoundedCornerShape(20.dp)) { Text("Start", fontSize = 13.sp) }
                    }
                }
            }
            OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("To: name or number") }, singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ThemeState.brandLt2, unfocusedContainerColor = ThemeState.brandLt2, focusedBorderColor = ThemeState.brand, unfocusedBorderColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Phone),
                keyboardActions = KeyboardActions(onDone = { if (to.isNotBlank() && !isGroup) openConv(contacts.find { it.second == to.trim() }?.first ?: to.trim(), to.trim()) }))
            LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf)) {
                items(filtered) { (name, number) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (isGroup) { if (selected.none { it.second == number }) selected = selected + (name to number); to = "" }
                        else openConv(name, number)
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(number)), contentAlignment = Alignment.Center) {
                            Text(initial(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ThemeState.textPrimary); Text(number, fontSize = 13.sp, color = ThemeState.textHint) }
                        if (isGroup && selected.any { it.second == number }) Icon(Icons.Default.CheckCircle, null, tint = ThemeState.brand)
                    }
                    HorizontalDivider(Modifier.padding(start = 74.dp), thickness = .5.dp, color = ThemeState.divLt)
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ THREAD / CHAT SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(conversation: Conversation, onDetails: () -> Unit = {}, onImagePreview: (Uri, String) -> Unit = { _, _ -> }, onForward: (String) -> Unit = {}, onBack: () -> Unit) {
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
    var viewerIdx by remember { mutableIntStateOf(-1) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val inSelectMode = selectedIds.isNotEmpty()
    var showMsgDetail by remember { mutableStateOf<Message?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<java.io.File?>(null) }
    var showScheduleDialog by remember { mutableStateOf(false) }

    // Camera capture
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraUri != null) onImagePreview(cameraUri!!, "image")
    }

    // Pull-down-to-dismiss
    var pullY by remember { mutableFloatStateOf(0f) }
    val pullThreshold = 200f
    var searchQuery by remember { mutableStateOf("") }

    fun reload(scrollToEnd: Boolean = false) {
        val raw = SmsRepository.loadMessages(ctx, conversation.threadId)
        val filtered = raw.filter { it.body.isNotBlank() || it.imageUri != null }
        val prevSize = msgs.size
        val prevFirst = listState.firstVisibleItemIndex
        val prevOffset = listState.firstVisibleItemScrollOffset
        msgs = filtered
        if (scrollToEnd) {
            scope.launch { if (msgs.isNotEmpty()) listState.scrollToItem(msgs.size - 1) }
        } else if (filtered.size > prevSize && prevSize > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= prevSize - 2) {
                scope.launch { listState.animateScrollToItem(msgs.size - 1) }
            } else {
                scope.launch { listState.scrollToItem(prevFirst, prevOffset) }
            }
        } else if (prevSize > 0) {
            scope.launch { listState.scrollToItem(prevFirst, prevOffset) }
        }
    }

    fun doSend() {
        val body = draft.trim()
        if (pendingMediaUri != null) {
            val uri = pendingMediaUri!!
            val prefix = if (replyTo != null) "\u21a9 ${replyTo!!.body.take(40)}\n" else ""
            scope.launch(Dispatchers.IO) {
                SmsRepository.sendMms(ctx, conversation.address, uri, prefix + body)
                withContext(Dispatchers.Main) { draft = ""; pendingMediaUri = null; replyTo = null; keyboard?.hide(); reload(scrollToEnd = true) }
            }
            return
        }
        if (body.isEmpty()) return
        val prefix = if (replyTo != null) "\u21a9 ${replyTo!!.body.take(40)}\n" else ""
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendSms(ctx, conversation.address, prefix + body)
            withContext(Dispatchers.Main) { if (ok) { draft = ""; replyTo = null; keyboard?.hide(); reload(scrollToEnd = true) } else Toast.makeText(ctx, "Send failed", Toast.LENGTH_SHORT).show() }
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(ctx, "Audio recording permission required", Toast.LENGTH_SHORT).show()
            return
        }
        val file = java.io.File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.3gp")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
        recorder.setOutputFile(file.absolutePath)
        try {
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            recordingFile = file
            isRecording = true
        } catch (e: Exception) {
            Toast.makeText(ctx, "Recording failed", Toast.LENGTH_SHORT).show()
            file.delete()
        }
    }

    fun stopAndSendRecording() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        isRecording = false
        val file = recordingFile ?: return
        recordingFile = null
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        scope.launch(Dispatchers.IO) {
            SmsRepository.sendMms(ctx, conversation.address, uri, "🎤 Voice message (${recordingSeconds}s)")
            withContext(Dispatchers.Main) { reload(scrollToEnd = true) }
        }
    }

    fun cancelRecording() {
        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
        recordingFile?.delete()
        recordingFile = null
        isRecording = false
    }

    BackHandler { if (inSelectMode) selectedIds = emptySet() else onBack() }
    LaunchedEffect(Unit) { reload(scrollToEnd = true); SmsRepository.markThreadRead(ctx, conversation.threadId) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); reload() } }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { onImagePreview(it, "image") } }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let { onImagePreview(it, "video") } }

    // Floating context menu (long-press on message)
    if (ctxMsg != null) {
        val m = ctxMsg!!
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { ctxMsg = null }) {
            Column(
                Modifier.align(if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 16.dp).widthIn(max = 300.dp)
            ) {
                // Floating reaction bar
                Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 8.dp, color = ThemeState.surf) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("\ud83d\ude02", "\ud83d\udc4d", "\u2764\ufe0f", "\ud83d\ude2e", "\ud83d\ude22", "\ud83d\ude21").forEach { emoji ->
                            val current = ReactionStore.get(ctx, m.id)
                            Box(Modifier.size(42.dp).clip(CircleShape).background(if (current == emoji) ThemeState.brandLt2 else Color.Transparent).clickable {
                                if (current == emoji) ReactionStore.remove(ctx, m.id)
                                else ReactionStore.set(ctx, m.id, emoji)
                                ctxMsg = null
                            }, contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Action menu
                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 8.dp, color = ThemeState.surf) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        MenuBtn(Icons.Default.Reply, "Reply") { replyTo = m; ctxMsg = null }
                        MenuBtn(Icons.Default.ContentCopy, "Copy") {
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msg", m.body))
                            Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); ctxMsg = null
                        }
                        MenuBtn(Icons.Default.Forward, "Forward") { onForward(m.body); ctxMsg = null }
                        MenuBtn(Icons.Default.CheckBox, "Select") { selectedIds = setOf(m.id); ctxMsg = null }
                        MenuBtn(if (StarredStore.isStarred(ctx, m.id)) Icons.Default.StarBorder else Icons.Default.Star,
                            if (StarredStore.isStarred(ctx, m.id)) "Unstar" else "Star", Color(0xFFFFC107)) {
                            val added = StarredStore.toggle(ctx, m.id)
                            Toast.makeText(ctx, if (added) "Starred" else "Unstarred", Toast.LENGTH_SHORT).show(); ctxMsg = null
                        }
                        MenuBtn(Icons.Default.Info, "Details") { showMsgDetail = m; ctxMsg = null }
                        MenuBtn(Icons.Default.Delete, "Delete", Red) {
                            scope.launch(Dispatchers.IO) { SmsRepository.deleteMessage(ctx, m.id) }
                            ctxMsg = null; reload()
                        }
                    }
                }
            }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, pullY.roundToInt()) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (pullY > pullThreshold) onBack()
                        pullY = 0f
                    },
                    onVerticalDrag = { change, dy ->
                        if (pullY > 0f || (dy > 0f && !listState.canScrollBackward)) {
                            pullY = (pullY + dy).coerceIn(0f, 400f)
                            change.consume()
                        }
                    }
                )
            }
            .graphicsLayer { alpha = 1f - (pullY / 600f).coerceAtMost(0.4f) }
    ) {
    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = {
            if (inSelectMode) {
                TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brand, titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
                    navigationIcon = { IconButton({ selectedIds = emptySet() }) { Icon(Icons.Default.Close, null) } },
                    title = { Text("${selectedIds.size}", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton({
                            val selMsgs = msgs.filter { it.id in selectedIds }
                            val text = selMsgs.joinToString("\n") { it.body }
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("msgs", text))
                            Toast.makeText(ctx, "Copied ${selMsgs.size} messages", Toast.LENGTH_SHORT).show(); selectedIds = emptySet()
                        }) { Icon(Icons.Default.ContentCopy, null) }
                        IconButton({
                            val selMsgs = msgs.filter { it.id in selectedIds }
                            val text = selMsgs.joinToString("\n") { it.body }
                            onForward(text); selectedIds = emptySet()
                        }) { Icon(Icons.Default.Forward, null) }
                        IconButton({
                            selectedIds.forEach { id -> StarredStore.toggle(ctx, id) }
                            Toast.makeText(ctx, "Starred ${selectedIds.size} messages", Toast.LENGTH_SHORT).show(); selectedIds = emptySet()
                        }) { Icon(Icons.Default.Star, null) }
                        IconButton({
                            scope.launch(Dispatchers.IO) { selectedIds.forEach { id -> SmsRepository.deleteMessage(ctx, id) } }
                            Toast.makeText(ctx, "Deleted ${selectedIds.size} messages", Toast.LENGTH_SHORT).show(); selectedIds = emptySet(); reload()
                        }) { Icon(Icons.Default.Delete, null) }
                    }
                )
            } else {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary, actionIconContentColor = ThemeState.textPrimary),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat\u2026", color = ThemeState.textHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = ThemeState.brand), modifier = Modifier.fillMaxWidth())
                    } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onDetails() }) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(conversation.address)), contentAlignment = Alignment.Center) {
                            Text(initial(conversation.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, color = ThemeState.textPrimary)
                            Text(conversation.address, fontSize = 12.sp, color = ThemeState.textHint)
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
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(ThemeState.surf)) {
                                DropdownMenuItem({ Text("Details") }, { showMenu = false; onDetails() }, leadingIcon = { Icon(Icons.Default.Info, null) })
                                DropdownMenuItem({ Text("Search") }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null) })
                                DropdownMenuItem({ Text("Starred") }, {
                                    showMenu = false
                                    val starredIds = StarredStore.get(ctx)
                                    val starredMsgs = msgs.filter { it.id in starredIds }
                                    if (starredMsgs.isEmpty()) Toast.makeText(ctx, "No starred messages", Toast.LENGTH_SHORT).show()
                                    else {
                                        val summary = starredMsgs.joinToString("\n\n") { "${msgTime(it.date)}: ${it.body.take(80)}" }
                                        Toast.makeText(ctx, "${starredMsgs.size} starred messages", Toast.LENGTH_LONG).show()
                                    }
                                }, leadingIcon = { Icon(Icons.Default.Star, null) })
                                DropdownMenuItem({ Text("Archive") }, {
                                    showMenu = false; ArchiveStore.add(ctx, conversation.threadId)
                                    Toast.makeText(ctx, "Archived", Toast.LENGTH_SHORT).show(); onBack()
                                }, leadingIcon = { Icon(Icons.Default.Archive, null) })
                                DropdownMenuItem({ Text("Block & report spam") }, {
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
            }
        },
        bottomBar = {
            Surface(color = ThemeState.surf, shadowElevation = 2.dp) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (replyTo != null) {
                        Row(Modifier.fillMaxWidth().background(ThemeState.brandLt2).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(ThemeState.brand))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reply", fontSize = 12.sp, color = ThemeState.brand, fontWeight = FontWeight.SemiBold)
                                Text(replyTo!!.body.take(60), fontSize = 13.sp, color = ThemeState.textSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton({ replyTo = null }, Modifier.size(24.dp)) { Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = ThemeState.textHint) }
                        }
                    }
                    if (pendingMediaUri != null) {
                        Row(Modifier.fillMaxWidth().background(ThemeState.brandLt2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (pendingMediaType == "image") {
                                AsyncImage(pendingMediaUri, "preview", Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                            } else {
                                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(ThemeState.textPrimary), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(32.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(if (pendingMediaType == "image") "Photo ready" else "Video ready", fontSize = 14.sp, color = ThemeState.textSecond, modifier = Modifier.weight(1f))
                            IconButton({ pendingMediaUri = null }) { Icon(Icons.Default.Close, null, tint = Red) }
                        }
                    }
                    if (isRecording) {
                        LaunchedEffect(isRecording) { recordingSeconds = 0; while (true) { delay(1000); recordingSeconds++ } }
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ cancelRecording() }) { Icon(Icons.Default.Delete, null, tint = Red) }
                            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(28.dp)).background(ThemeState.brandLt2), contentAlignment = Alignment.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Red))
                                    Spacer(Modifier.width(8.dp))
                                    val mins = recordingSeconds / 60; val secs = recordingSeconds % 60
                                    Text("${mins}:${secs.toString().padStart(2, '0')}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                                    Spacer(Modifier.width(12.dp))
                                    // Waveform visualization
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        repeat(16) { i ->
                                            val h = ((i * 7 + recordingSeconds * 3) % 13 + 4).dp
                                            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp)).background(ThemeState.brand))
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            FloatingActionButton(
                                onClick = { stopAndSendRecording() },
                                modifier = Modifier.size(46.dp), containerColor = ThemeState.brand, contentColor = Color.White, shape = CircleShape,
                                elevation = FloatingActionButtonDefaults.elevation(0.dp)
                            ) { Icon(Icons.Default.Send, null, Modifier.size(22.dp)) }
                        }
                    } else {
                    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { showAttach = !showAttach; showEmoji = false }, Modifier.size(44.dp).clip(CircleShape).background(ThemeState.brandLt2)) {
                            Icon(if (showAttach) Icons.Default.Close else Icons.Default.Add, null, tint = ThemeState.textSecond, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        val hasCont = draft.isNotBlank() || pendingMediaUri != null
                        OutlinedTextField(
                            draft, { draft = it }, Modifier.weight(1f),
                            placeholder = { Text("Text message", color = ThemeState.textHint) },
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ThemeState.brandLt2, unfocusedContainerColor = ThemeState.brandLt2, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton({ showEmoji = !showEmoji; showAttach = false }) { Icon(Icons.Default.EmojiEmotions, null, tint = ThemeState.textHint) }
                                    if (!hasCont) IconButton({ imagePicker.launch("image/*") }) { Icon(Icons.Default.Image, null, tint = ThemeState.textHint) }
                                }
                            },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { doSend() }),
                            maxLines = 4
                        )
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(if (hasCont) ThemeState.brand else Color(0xFF146C2E))
                            .combinedClickable(
                                onClick = { if (hasCont) doSend() else startRecording() },
                                onLongClick = { if (hasCont) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); showScheduleDialog = true } }
                            ), contentAlignment = Alignment.Center) {
                            Icon(if (hasCont) Icons.Default.Send else Icons.Default.Mic, null, Modifier.size(22.dp), tint = Color.White)
                        }
                    }
                    }
                    AnimatedVisibility(showAttach, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        LazyVerticalGrid(
                            GridCells.Fixed(4),
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.brandSurf).padding(horizontal = 12.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item { AttachBtn(Icons.Default.Image, "Gallery", Brand) { imagePicker.launch("image/*") } }
                            item { AttachBtn(Icons.Default.Videocam, "Video", Brand) { videoPicker.launch("video/*") } }
                            item { AttachBtn(Icons.Default.CameraAlt, "Camera", Brand) {
                                val file = java.io.File(ctx.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
                                cameraUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                                cameraLauncher.launch(cameraUri!!)
                                showAttach = false
                            } }
                            item { AttachBtn(Icons.Default.Gif, "GIFs", Brand) {
                                showEmoji = true; showAttach = false
                            } }
                            item { AttachBtn(Icons.Default.EmojiEmotions, "Stickers", Brand) { showEmoji = true; showAttach = false } }
                            item { AttachBtn(Icons.Default.InsertDriveFile, "Files", Brand) { ctx.startActivity(Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }) } }
                            item { AttachBtn(Icons.Default.LocationOn, "Location", Brand) {
                                val locIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))
                                if (locIntent.resolveActivity(ctx.packageManager) != null) ctx.startActivity(locIntent)
                                else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")))
                                showAttach = false
                            } }
                            item { AttachBtn(Icons.Default.Contacts, "Contacts", Brand) {
                                ctx.startActivity(Intent(Intent.ACTION_PICK, android.provider.ContactsContract.Contacts.CONTENT_URI))
                                showAttach = false
                            } }
                            item { AttachBtn(Icons.Default.Schedule, "Schedule", Brand) {
                                if (draft.isNotBlank()) { showScheduleDialog = true }
                                else Toast.makeText(ctx, "Type your message first, then schedule", Toast.LENGTH_SHORT).show()
                                showAttach = false
                            } }
                        }
                    }
                    AnimatedVisibility(showEmoji, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        EmojiPicker { draft += it; showEmoji = false }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf).padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
            ) {
                val displayMsgs = if (showSearch && searchQuery.isNotBlank()) msgs.filter { it.body.contains(searchQuery, true) } else msgs
                val grouped = displayMsgs.groupByDate()
                grouped.forEach { (day, dayMsgs) ->
                    item(key = "day_$day") { DayHeader(day) }
                    val isGroupChat = conversation.address.contains(";")
                    items(dayMsgs, key = { it.id }) { m ->
                        if (inSelectMode) {
                            Row(Modifier.fillMaxWidth().clickable {
                                selectedIds = if (m.id in selectedIds) selectedIds - m.id else selectedIds + m.id
                            }.background(if (m.id in selectedIds) ThemeState.brandLt2 else Color.Transparent).padding(start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(m.id in selectedIds, { selectedIds = if (m.id in selectedIds) selectedIds - m.id else selectedIds + m.id },
                                    colors = CheckboxDefaults.colors(checkedColor = ThemeState.brand))
                                Box(Modifier.weight(1f)) { MessageBubble(m, {}, null, isGroupChat) }
                            }
                        } else {
                            SwipeToReplyBubble(m, onReply = { replyTo = m }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); ctxMsg = m },
                                onImageClick = { uri ->
                                    val imageUris = msgs.filter { it.imageUri != null && (it.mediaMime == null || it.mediaMime.startsWith("image/")) }.map { it.imageUri!! }
                                    viewerIdx = imageUris.indexOf(uri).coerceAtLeast(0)
                                }, isGroup = isGroupChat)
                        }
                    }
                }
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
                                    color = ThemeState.brandLt2,
                                    border = BorderStroke(1.dp, ThemeState.divLt)
                                ) {
                                    Text(reply, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp, color = ThemeState.brand, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
            val showScrollDown by remember { derivedStateOf { listState.firstVisibleItemIndex < (msgs.size - 5).coerceAtLeast(0) && msgs.size > 5 } }
            AnimatedVisibility(showScrollDown, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(msgs.size - 1) } },
                    containerColor = ThemeState.surf,
                    contentColor = ThemeState.textSecond,
                    shape = CircleShape
                ) { Icon(Icons.Default.KeyboardArrowDown, null) }
            }
        }
    }
    } // end pull-down Box
    val imageUris = remember(msgs) { msgs.filter { it.imageUri != null && (it.mediaMime == null || it.mediaMime.startsWith("image/")) }.map { it.imageUri!! } }
    if (viewerIdx >= 0 && imageUris.isNotEmpty()) MediaViewerOverlay(imageUris, viewerIdx, onChangeIdx = { viewerIdx = it }) { viewerIdx = -1 }

    // Schedule send dialog
    if (showScheduleDialog) {
        val schedOptions = listOf(
            "In 15 minutes" to 15L,
            "In 30 minutes" to 30L,
            "In 1 hour" to 60L,
            "In 2 hours" to 120L,
            "Tomorrow morning (8:00 AM)" to -1L
        )
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            containerColor = ThemeState.surf, shape = RoundedCornerShape(28.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Schedule, null, tint = ThemeState.brand, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Schedule message", fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                }
            },
            text = {
                Column {
                    Text("\"${draft.take(40)}${if (draft.length > 40) "…" else ""}\"", fontSize = 13.sp, color = ThemeState.textHint,
                        modifier = Modifier.padding(bottom = 12.dp))
                    schedOptions.forEach { (label, mins) ->
                        val sendAt = if (mins == -1L) {
                            val cal = Calendar.getInstance()
                            cal.add(Calendar.DAY_OF_YEAR, 1)
                            cal.set(Calendar.HOUR_OF_DAY, 8)
                            cal.set(Calendar.MINUTE, 0)
                            cal.set(Calendar.SECOND, 0)
                            cal.timeInMillis
                        } else System.currentTimeMillis() + mins * 60_000
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                ScheduledSendReceiver.schedule(ctx, conversation.address, body, sendAt)
                                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                                Toast.makeText(ctx, "Scheduled for ${timeFmt.format(Date(sendAt))}", Toast.LENGTH_LONG).show()
                                draft = ""; showScheduleDialog = false
                            }
                        }.padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, null, tint = ThemeState.textSecond, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(label, fontSize = 15.sp, color = ThemeState.textPrimary)
                        }
                    }
                }
            },
            confirmButton = { TextButton({ showScheduleDialog = false }) { Text("Cancel", color = ThemeState.brand) } }
        )
    }

    // Message details dialog
    if (showMsgDetail != null) {
        val md = showMsgDetail!!
        AlertDialog(
            onDismissRequest = { showMsgDetail = null },
            containerColor = ThemeState.surf, shape = RoundedCornerShape(28.dp),
            title = { Text("Message details", fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailInfoRow("Type", if (md.isMms) "MMS" else "SMS")
                    DetailInfoRow("Status", if (md.incoming) "Received" else "Sent")
                    DetailInfoRow("Time", SimpleDateFormat("EEEE, MMM d, yyyy h:mm:ss a", Locale.getDefault()).format(Date(md.date)))
                    if (md.address != null) DetailInfoRow("Address", md.address)
                    DetailInfoRow("Size", "${md.body.length} chars")
                    if (md.imageUri != null) DetailInfoRow("Attachment", "Image")
                    DetailInfoRow("Starred", if (StarredStore.isStarred(ctx, md.id)) "Yes" else "No")
                    val reaction = ReactionStore.get(ctx, md.id)
                    if (reaction != null) DetailInfoRow("Reaction", reaction)
                }
            },
            confirmButton = { TextButton({ showMsgDetail = null }) { Text("Close", color = ThemeState.brand) } }
        )
    }
}

// ── Swipe to reply wrapper ───────────────────────────────────────────────────
@Composable
fun SwipeToReplyBubble(m: Message, onReply: () -> Unit, onLongClick: () -> Unit, onImageClick: ((Uri) -> Unit)? = null, isGroup: Boolean = false) {
    val offsetAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = 100f
    Box(Modifier.fillMaxWidth().pointerInput(m.id) {
        detectHorizontalDragGestures(
            onDragEnd = {
                val cur = offsetAnim.value
                if (abs(cur) > threshold) onReply()
                scope.launch { offsetAnim.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 400f)) }
            },
            onHorizontalDrag = { _, dx ->
                val target = if (m.incoming) (offsetAnim.value + dx).coerceIn(0f, 200f) else (offsetAnim.value + dx).coerceIn(-200f, 0f)
                scope.launch { offsetAnim.snapTo(target) }
            }
        )
    }) {
        val off = offsetAnim.value
        if (abs(off) > 20f) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = if (m.incoming) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(Icons.Default.Reply, null, tint = ThemeState.brand.copy(alpha = (abs(off) / threshold).coerceAtMost(1f)), modifier = Modifier.size(24.dp))
            }
        }
        Box(Modifier.offset { IntOffset(off.roundToInt(), 0) }) {
            MessageBubble(m, onLongClick, onImageClick, isGroup)
        }
    }
}

// ── Attachment button ────────────────────────────────────────────────────────
// ── Attachment button (pill-shaped, monochrome — M3 Expressive style) ────────
@Composable
fun AttachBtn(icon: ImageVector, label: String, @Suppress("UNUSED_PARAMETER") tint: Color, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (ThemeState.isDark) Color(0xFF2D2D2D) else Color(0xFFF0F0F0),
            modifier = Modifier.size(width = 56.dp, height = 48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = ThemeState.textSecond, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 10.sp, color = ThemeState.textSecond, maxLines = 1)
    }
}

@Composable
fun MenuBtn(icon: ImageVector, label: String, tint: Color = ThemeState.textPrimary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = if (tint == Red) Red else ThemeState.textPrimary)
    }
}

// ━━━━━━━━━━━━━━━━ EMOJI / GIF / STICKERS PICKER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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
    var tab by remember { mutableIntStateOf(0) }
    var search by remember { mutableStateOf("") }
    val stickers = listOf("\ud83c\udf89","\ud83e\udd73","\ud83e\udd19","\u2728","\ud83d\udc96","\ud83c\udf08","\ud83e\udd84","\ud83c\udf8a","\ud83c\udf7e","\ud83e\udd42",
        "\ud83c\udf86","\ud83c\udf87","\ud83e\udde8","\ud83e\ude84","\ud83e\udee6","\ud83e\udd0c","\ud83d\udcab","\u26a1","\ud83c\udf1f","\ud83d\udc9d")

    Surface(color = ThemeState.brandSurf, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Emoji", "GIFs", "Stickers").forEachIndexed { i, label ->
                    Surface(onClick = { tab = i }, shape = RoundedCornerShape(20.dp),
                        color = if (tab == i) ThemeState.brand else Color.Transparent) {
                        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp,
                            fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (tab == i) Color.White else ThemeState.textHint)
                    }
                }
            }
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(42.dp),
                placeholder = { Text("Search\u2026", fontSize = 13.sp) }, singleLine = true,
                shape = RoundedCornerShape(20.dp), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ThemeState.brandLt2, unfocusedContainerColor = ThemeState.brandLt2, focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent),
                leadingIcon = { Icon(Icons.Default.Search, null, tint = ThemeState.textHint, modifier = Modifier.size(18.dp)) })
            when (tab) {
                0 -> LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    items(emojis) { e -> Box(Modifier.size(44.dp).clickable { onPick(e) }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) } }
                }
                1 -> {
                    val gifEmojis = listOf("👍","👏","🎉","🔥","❤️","😂","😢","😡","🤔","😎","🙏","💪","👋","🥳","😍","💯","✨","🌟","🎊","🎶")
                    LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        items(gifEmojis) { g ->
                            Box(Modifier.size(60.dp).clip(RoundedCornerShape(8.dp)).background(ThemeState.brandLt2).clickable { onPick(g) }.padding(4.dp),
                                contentAlignment = Alignment.Center) { Text(g, fontSize = 28.sp) }
                        }
                    }
                    if (search.isNotBlank()) {
                        val gifCtx = LocalContext.current
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Surface(onClick = { gifCtx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://tenor.com/search/${search}"))) },
                                shape = RoundedCornerShape(20.dp), color = ThemeState.brand) {
                                Text("Search \"$search\" on Tenor", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                            }
                        }
                    } else Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text("Type to search GIFs", fontSize = 13.sp, color = ThemeState.textHint)
                    }
                }
                2 -> LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(horizontal = 8.dp, vertical = 4.dp)) {
                    items(stickers) { s -> Box(Modifier.size(60.dp).clickable { onPick(s) }, contentAlignment = Alignment.Center) { Text(s, fontSize = 36.sp) } }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ MESSAGE BUBBLE ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
fun textSizeSp(): Float {
    val ctx = LocalContext.current
    return remember {
        val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        when (prefs.getString("text_size", "default")) {
            "small" -> 13f; "large" -> 18f; "largest" -> 21f; else -> 15f
        }
    }
}

private val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)", RegexOption.IGNORE_CASE)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(m: Message, onLongClick: () -> Unit, onImageClick: ((Uri) -> Unit)? = null, isGroup: Boolean = false) {
    val ctx = LocalContext.current
    val isIn = m.incoming
    val fontSize = textSizeSp()
    val senderName = remember(m.address) {
        if (isGroup && isIn) SmsRepository.getContactName(ctx, m.address ?: "") ?: (m.address ?: "?") else ""
    }
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
        if (isIn && isGroup) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(avatarColor(m.address ?: "")), contentAlignment = Alignment.Center) {
                Text(initial(senderName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = shape,
            color = if (isIn) ThemeState.bubbleIn else ThemeState.bubbleOut,
            shadowElevation = if (!isIn) 1.dp else 0.dp,
            tonalElevation = if (!isIn) 1.dp else 0.dp
        ) {
            Column {
                if (isIn && isGroup) {
                    Text(senderName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = avatarColor(m.address ?: ""),
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.imageUri != null) {
                    val mime = m.mediaMime ?: ""
                    val isAudio = mime.startsWith("audio/") || mime == "video/3gpp" || mime == "video/3gp" || m.body.contains("Voice message")
                    when {
                        isAudio -> {
                            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    try {
                                        val playMime = if (mime.startsWith("audio/")) mime else "audio/3gpp"
                                        val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(m.imageUri, playMime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        ctx.startActivity(playIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(ctx, "No audio player found", Toast.LENGTH_SHORT).show()
                                    }
                                }, modifier = Modifier.size(44.dp).clip(CircleShape).background(
                                    if (isIn) ThemeState.brand.copy(alpha = .15f) else Color.White.copy(alpha = .2f))) {
                                    Icon(Icons.Default.PlayArrow, null, tint = if (isIn) ThemeState.brand else Color.White, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Voice message", fontSize = fontSize.sp, fontWeight = FontWeight.Medium,
                                        color = if (isIn) ThemeState.textPrimary else if (ThemeState.isDark) Color.White else ThemeState.textPrimary)
                                    Spacer(Modifier.height(4.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        repeat(24) { i ->
                                            val h = ((i * 7 + 3) % 13 + 4).dp
                                            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(2.dp))
                                                .background(if (isIn) ThemeState.brand.copy(.35f) else Color.White.copy(.45f)))
                                        }
                                    }
                                }
                            }
                        }
                        mime.startsWith("video/") -> {
                            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(
                                if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            ).background(Color.Black).clickable {
                                try {
                                    val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(m.imageUri, mime)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    ctx.startActivity(playIntent)
                                } catch (_: Exception) {
                                    Toast.makeText(ctx, "No video player found", Toast.LENGTH_SHORT).show()
                                }
                            }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White.copy(.9f), modifier = Modifier.size(56.dp))
                                Text("Video", color = Color.White.copy(.7f), fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                            }
                        }
                        else -> {
                            AsyncImage(m.imageUri, "image", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(
                                if (m.body.isBlank()) shape else RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                            ).clickable { onImageClick?.invoke(m.imageUri!!) }, contentScale = ContentScale.Crop)
                        }
                    }
                }
                val showBody = m.body.isNotBlank() && !(m.imageUri != null && (m.mediaMime?.let { it.startsWith("audio/") || it == "video/3gpp" || it == "video/3gp" } == true || m.body.contains("Voice message")))
                if (showBody) {
                    val textColor = if (isIn) ThemeState.textPrimary else if (ThemeState.isDark) Color.White else ThemeState.textPrimary
                    val linkColor = if (isIn) ThemeState.brand else if (ThemeState.isDark) Color(0xFFBBDEFB) else ThemeState.brand
                    val urls = URL_REGEX.findAll(m.body).toList()

                    if (urls.isEmpty()) {
                        Text(m.body, color = textColor, fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp,
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
                        ClickableText(annotated, style = androidx.compose.ui.text.TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize + 6).sp),
                            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp),
                            onClick = { off -> annotated.getStringAnnotations("URL", off, off).firstOrNull()?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.item))) } })
                        val url = urls.first().value
                        val domain = try { java.net.URL(url).host.removePrefix("www.") } catch (_: Exception) { url }
                        var linkTitle by remember { mutableStateOf<String?>(null) }
                        LaunchedEffect(url) {
                            linkTitle = withContext(Dispatchers.IO) {
                                try {
                                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                    conn.connectTimeout = 3000; conn.readTimeout = 3000
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                    val html = conn.inputStream.bufferedReader().readText().take(8000)
                                    conn.disconnect()
                                    val titleMatch = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)
                                    titleMatch?.groupValues?.get(1)?.trim()
                                } catch (_: Exception) { null }
                            }
                        }
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (isIn) ThemeState.bubbleIn.copy(alpha = .7f) else if (ThemeState.isDark) ThemeState.brandDk else Color(0xFFBFD7F6))
                            .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.padding(10.dp)) {
                            Column {
                                if (linkTitle != null) {
                                    Text(linkTitle!!, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        color = if (isIn) ThemeState.textPrimary else if (ThemeState.isDark) Color.White else ThemeState.textPrimary,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(4.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Language, null, tint = ThemeState.textSecond, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(domain, fontSize = 12.sp, color = ThemeState.textSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (StarredStore.isStarred(ctx, m.id)) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(msgTime(m.date), fontSize = 11.sp, color = if (isIn) ThemeState.textHint else if (ThemeState.isDark) Color.White.copy(.7f) else ThemeState.textHint)
                    if (!isIn) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.DoneAll, null, tint = if (ThemeState.isDark) Color.White.copy(.85f) else ThemeState.brand, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        // Reaction badge under bubble
        val reaction = ReactionStore.get(ctx, m.id)
        if (reaction != null) {
            Box(Modifier.offset(y = (-6).dp).then(if (isIn) Modifier.padding(start = 12.dp) else Modifier.padding(end = 12.dp))) {
                Surface(shape = RoundedCornerShape(12.dp), color = ThemeState.surf, shadowElevation = 2.dp, border = BorderStroke(.5.dp, ThemeState.divLt)) {
                    Text(reaction, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        if (isIn) Spacer(Modifier.width(56.dp))
    }
}

// ━━━━━━━━━━━━━━━━ IMAGE PREVIEW (with caption) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagePreviewScreen(conv: Conversation, uri: Uri, mediaType: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var caption by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    Scaffold(containerColor = Color.Black,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(Color.Black.copy(.7f), titleContentColor = Color.White, navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = { Text(conv.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        },
        bottomBar = {
            Surface(color = Color.Black.copy(.85f)) {
                Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Add a caption", color = Color.White.copy(.5f)) }, singleLine = false, maxLines = 3,
                        shape = RoundedCornerShape(28.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
                        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White.copy(.1f), unfocusedContainerColor = Color.White.copy(.1f),
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, cursorColor = Color.White))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        FloatingActionButton(
                            onClick = {
                                if (!sending) {
                                    sending = true
                                    scope.launch(Dispatchers.IO) {
                                        SmsRepository.sendMms(ctx, conv.address, uri, caption)
                                        withContext(Dispatchers.Main) { sending = false; onBack() }
                                    }
                                }
                            },
                            containerColor = ThemeState.brand, contentColor = Color.White, shape = CircleShape,
                            modifier = Modifier.size(52.dp)
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.Send, null, Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
            if (mediaType == "image") {
                AsyncImage(uri, "preview", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Video selected", color = Color.White.copy(.7f), fontSize = 14.sp)
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ FORWARD / SELECT RECIPIENTS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ForwardScreen(body: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var selected by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var search by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { contacts = withContext(Dispatchers.IO) { SmsRepository.loadContactsList(ctx) } }
    val filtered = if (search.isBlank()) contacts else contacts.filter { it.first.contains(search, true) || it.second.contains(search, true) }

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
                navigationIcon = { IconButton(onDone) { Icon(Icons.Default.ArrowBack, null) } },
                title = { Text("Forward to…") })
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Surface(color = ThemeState.surf, shadowElevation = 4.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${selected.size} recipient${if (selected.size > 1) "s" else ""}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                            Text(body.take(60) + if (body.length > 60) "…" else "", fontSize = 12.sp, color = ThemeState.textHint, maxLines = 1)
                        }
                        Spacer(Modifier.width(12.dp))
                        FloatingActionButton(
                            onClick = {
                                if (!sending) {
                                    sending = true
                                    scope.launch(Dispatchers.IO) {
                                        selected.forEach { (_, num) -> SmsRepository.sendSms(ctx, num, body) }
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(ctx, "Forwarded to ${selected.size} contact${if (selected.size > 1) "s" else ""}", Toast.LENGTH_SHORT).show()
                                            sending = false; onDone()
                                        }
                                    }
                                }
                            },
                            containerColor = ThemeState.brand, contentColor = Color.White, shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            if (sending) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                            else Icon(Icons.Default.Send, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (selected.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    selected.forEach { (name, num) ->
                        AssistChip(onClick = { selected = selected.filter { it.second != num } }, label = { Text(name, fontSize = 13.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                            colors = AssistChipDefaults.assistChipColors(containerColor = ThemeState.brandSurf))
                    }
                }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(28.dp), color = ThemeState.brandLt2) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = ThemeState.textHint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    TextField(search, { search = it }, Modifier.weight(1f), placeholder = { Text("Search contacts", color = ThemeState.textHint) }, singleLine = true,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = ThemeState.brand))
                }
            }
            Spacer(Modifier.height(4.dp))
            // Message preview
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = ThemeState.brandLt2) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.FormatQuote, null, tint = ThemeState.brand, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(body.take(120) + if (body.length > 120) "…" else "", fontSize = 13.sp, color = ThemeState.textSecond, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf)) {
                items(filtered) { (name, number) ->
                    val isSelected = selected.any { it.second == number }
                    Row(Modifier.fillMaxWidth().clickable {
                        if (isSelected) selected = selected.filter { it.second != number }
                        else selected = selected + (name to number)
                    }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(number)), contentAlignment = Alignment.Center) {
                            Text(initial(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ThemeState.textPrimary); Text(number, fontSize = 13.sp, color = ThemeState.textHint) }
                        if (isSelected) Icon(Icons.Default.CheckCircle, null, tint = ThemeState.brand)
                    }
                    HorizontalDivider(Modifier.padding(start = 74.dp), thickness = .5.dp, color = ThemeState.divLt)
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ SETTINGS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSub: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text("Settings") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf)) {
            item { SettRow(Icons.Default.Notifications, "Notifications", "Sounds, vibration") { onSub("notifications") } }
            item { SettRow(Icons.Default.Chat, "Bubbles", "Chat colors and style") { onSub("bubbles") } }
            item { HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Palette, "Theme", "System default") { onSub("theme") } }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Default") { onSub("textsize") } }
            item { HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Web link previews") { onSub("previews") } }
            item { SettRow(Icons.Default.Shield, "Protection & Safety", "Spam, blocked contacts") { onSub("safety") } }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "Archive on swipe") { onSub("swipe") } }
            item { HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Tune, "Advanced", "Delivery reports, MMS") { onSub("advanced") } }
            item { HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp, modifier = Modifier.padding(horizontal = 16.dp)) }
            item { SettRow(Icons.Default.Email, "Your email", AdminEmails.getUserEmail(ctx).ifBlank { "Not set" }) { onSub("email") } }
            item { SettRow(Icons.Default.Info, "About", "Version 3.2") { onSub("about") } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSubScreen(key: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val title = when (key) { "notifications" -> "Notifications"; "bubbles" -> "Bubbles"; "theme" -> "Theme"; "textsize" -> "Text size"
        "previews" -> "Previews"; "safety" -> "Safety"; "swipe" -> "Swipe actions"; "advanced" -> "Advanced"; "about" -> "About"; "email" -> "Your email"; else -> "Settings" }

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } }, title = { Text(title) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf).padding(16.dp)) {
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
                        }, colors = ButtonDefaults.buttonColors(ThemeState.brand), shape = RoundedCornerShape(20.dp)) {
                            Text("Test notification")
                        }
                    }
                }
                "bubbles" -> {
                    item { Text("Chat bubble color", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ThemeState.textPrimary); Spacer(Modifier.height(12.dp)) }
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
                        Text("Preview", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = ThemeState.textHint)
                        Spacer(Modifier.height(8.dp))
                        // Live preview bubble
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(ThemeState.bubbleOut).padding(12.dp)) {
                            Column {
                                Text("Hello!", color = if (ThemeState.isDark) Color.White else ThemeState.textPrimary, fontSize = 15.sp)
                                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                    Text("3:25 PM", color = ThemeState.textHint, fontSize = 11.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.DoneAll, null, tint = ThemeState.brand, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }
                "theme" -> {
                    item { RadioRow("System default", prefs.getString("theme", "system") == "system") {
                        prefs.edit().putString("theme", "system").apply()
                        ThemeState.isDark = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    } }
                    item { RadioRow("Light", prefs.getString("theme", "system") == "light") {
                        prefs.edit().putString("theme", "light").apply()
                        ThemeState.isDark = false
                    } }
                    item { RadioRow("Dark", prefs.getString("theme", "system") == "dark") {
                        prefs.edit().putString("theme", "dark").apply()
                        ThemeState.isDark = true
                    } }
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
                        Text("Manage blocked numbers", color = ThemeState.textHint, fontSize = 13.sp)
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
                "email" -> {
                    item {
                        var emailInput by remember { mutableStateOf(AdminEmails.getUserEmail(ctx)) }
                        Text("Enter your email address", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = ThemeState.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("This email determines access to admin features.", fontSize = 13.sp, color = ThemeState.textHint)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(emailInput, { emailInput = it }, Modifier.fillMaxWidth(), placeholder = { Text("your@email.com") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { AdminEmails.setUserEmail(ctx, emailInput); Toast.makeText(ctx, "Email saved", Toast.LENGTH_SHORT).show() }),
                            shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ThemeState.brandLt2, unfocusedContainerColor = ThemeState.brandLt2, focusedBorderColor = ThemeState.brand, unfocusedBorderColor = Color.Transparent))
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { AdminEmails.setUserEmail(ctx, emailInput); Toast.makeText(ctx, "Email saved", Toast.LENGTH_SHORT).show() },
                            colors = ButtonDefaults.buttonColors(ThemeState.brand), shape = RoundedCornerShape(20.dp)) { Text("Save") }
                    }
                }
                "about" -> {
                    item { Text("Heimish Messages", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = ThemeState.textPrimary) }
                    item { Text("Version 3.2", fontSize = 14.sp, color = ThemeState.textHint); Spacer(Modifier.height(16.dp)) }
                    item { Text("A heimishe messaging app for the community.", fontSize = 14.sp, color = ThemeState.textSecond) }
                    item { Spacer(Modifier.height(16.dp)); Text("\u00a9 2024-2026 Heimish Messages", fontSize = 13.sp, color = ThemeState.textHint) }
                }
            }
        }
    }
}

@Composable fun ToggleRow(label: String, key: String, prefs: android.content.SharedPreferences) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, true)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = ThemeState.textPrimary); Switch(on, { on = it; prefs.edit().putBoolean(key, it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = ThemeState.brand))
    }
}
@Composable fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = ThemeState.brand)); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 15.sp, color = ThemeState.textPrimary)
    }
}
@Composable fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ThemeState.textSecond, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 15.sp, color = ThemeState.textPrimary); Text(sub, fontSize = 13.sp, color = ThemeState.textHint) }
        Icon(Icons.Default.ChevronRight, null, tint = ThemeState.textHint)
    }
}

// ━━━━━━━━━━━━━━━━ ADMIN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
data class AdminContact(val name: String, val addr: String, val device: String = "")

fun parseContactsResponse(json: String): List<AdminContact> {
    val result = mutableListOf<AdminContact>()
    try {
        val arr = try { JSONArray(json) } catch (_: Exception) {
            val obj = JSONObject(json)
            obj.optJSONArray("contacts") ?: obj.optJSONArray("results") ?: return emptyList()
        }
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            result.add(AdminContact(
                name = c.optString("name", ""),
                addr = c.optString("addr", c.optString("number", "")),
                device = c.optString("device", "")
            ))
        }
    } catch (_: Exception) { }
    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<AdminContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSearch(q: String) {
        if (q.length < 2) { contacts = emptyList(); hasSearched = false; return }
        isLoading = true; errorMsg = null; hasSearched = true
        scope.launch(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://heimish-contacts.avrumy5872877.workers.dev/contacts?q=${java.net.URLEncoder.encode(q, "UTF-8")}")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("Authorization", "Bearer hm_admin_avrumy_2024")
                conn.connectTimeout = 10000; conn.readTimeout = 10000
                val body = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                val parsed = parseContactsResponse(body)
                withContext(Dispatchers.Main) { contacts = parsed; isLoading = false }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMsg = e.message ?: "Connection error"; isLoading = false }
            }
        }
    }

    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
                title = { Text("Admin Panel", fontWeight = FontWeight.Bold) }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Contact Search Card
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = ThemeState.brandLt2, tonalElevation = 0.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Contact Search", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("Search by name or phone number", fontSize = 13.sp, color = ThemeState.textSecond)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            query, { query = it; doSearch(it) },
                            Modifier.fillMaxWidth(),
                            placeholder = { Text("Name or number\u2026") },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = ThemeState.textHint) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton({ query = ""; contacts = emptyList(); hasSearched = false }) {
                                    Icon(Icons.Default.Close, null, tint = ThemeState.textHint, modifier = Modifier.size(20.dp))
                                }
                            },
                            singleLine = true, shape = RoundedCornerShape(28.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = ThemeState.surf, unfocusedContainerColor = ThemeState.surf,
                                focusedBorderColor = ThemeState.brand, unfocusedBorderColor = ThemeState.divLt
                            )
                        )
                    }
                }
            }

            // Loading
            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ThemeState.brand, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }

            // Error
            if (errorMsg != null) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFCE4EC)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = Red, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(errorMsg ?: "", fontSize = 13.sp, color = Red)
                        }
                    }
                }
            }

            // Results
            if (!isLoading && hasSearched && contacts.isEmpty() && errorMsg == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Search, null, tint = ThemeState.textHint, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No contacts found", color = ThemeState.textHint, fontSize = 14.sp)
                        }
                    }
                }
            }

            if (contacts.isNotEmpty()) {
                item {
                    Text("${contacts.size} result${if (contacts.size != 1) "s" else ""}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ThemeState.textHint,
                        modifier = Modifier.padding(start = 4.dp))
                }
                items(contacts.size) { idx ->
                    val c = contacts[idx]
                    Surface(shape = RoundedCornerShape(16.dp), color = ThemeState.brandLt2, tonalElevation = 0.dp) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).background(avatarColor(c.name)), contentAlignment = Alignment.Center) {
                                Text(initial(c.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name.ifBlank { "Unknown" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = ThemeState.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.addr, fontSize = 13.sp, color = ThemeState.textSecond, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (c.device.isNotBlank()) Text(c.device, fontSize = 11.sp, color = ThemeState.textHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = {
                                val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("number", c.addr))
                                Toast.makeText(ctx, "Copied ${c.addr}", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.ContentCopy, null, tint = ThemeState.brand, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Admin Emails Card
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = ThemeState.brandLt2, tonalElevation = 0.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Admin Emails", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text("Devices with these emails can access the admin panel", fontSize = 13.sp, color = ThemeState.textSecond)
                        Spacer(Modifier.height(12.dp))

                        var adminEmails by remember { mutableStateOf(AdminEmails.getAdminEmails(ctx)) }
                        var newEmail by remember { mutableStateOf("") }

                        adminEmails.forEach { email ->
                            Surface(shape = RoundedCornerShape(12.dp), color = ThemeState.surf, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Email, null, tint = ThemeState.brand, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(email, fontSize = 14.sp, color = ThemeState.textPrimary, modifier = Modifier.weight(1f))
                                    if (adminEmails.size > 1) {
                                        IconButton(onClick = { AdminEmails.removeAdmin(ctx, email); adminEmails = AdminEmails.getAdminEmails(ctx) }, modifier = Modifier.size(28.dp)) {
                                            Icon(Icons.Default.Close, null, tint = Red, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newEmail, { newEmail = it }, Modifier.weight(1f),
                                placeholder = { Text("Add email\u2026") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = {
                                    if (newEmail.contains("@")) { AdminEmails.addAdmin(ctx, newEmail); adminEmails = AdminEmails.getAdminEmails(ctx); newEmail = "" }
                                }),
                                shape = RoundedCornerShape(28.dp),
                                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = ThemeState.surf, unfocusedContainerColor = ThemeState.surf,
                                    focusedBorderColor = ThemeState.brand, unfocusedBorderColor = ThemeState.divLt))
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = {
                                if (newEmail.contains("@")) { AdminEmails.addAdmin(ctx, newEmail); adminEmails = AdminEmails.getAdminEmails(ctx); newEmail = "" }
                                else Toast.makeText(ctx, "Enter a valid email", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.size(40.dp).clip(CircleShape).background(ThemeState.brand)) {
                                Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // Device Info Card
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = ThemeState.brandLt2, tonalElevation = 0.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Device Info", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ThemeState.textPrimary)
                        Spacer(Modifier.height(12.dp))
                        AdminInfoRow("Model", Build.MODEL)
                        AdminInfoRow("Android", Build.VERSION.RELEASE)
                        AdminInfoRow("Default SMS", "${isDefaultSmsApp(ctx)}")
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing\u2026", Toast.LENGTH_SHORT).show() },
                            colors = ButtonDefaults.buttonColors(containerColor = ThemeState.brand),
                            shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()
                        ) { Icon(Icons.Default.Sync, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Sync Contacts Now") }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun AdminInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 14.sp, color = ThemeState.textHint, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 14.sp, color = ThemeState.textPrimary, fontWeight = FontWeight.Medium)
    }
}

// ━━━━━━━━━━━━━━━━ CONTACT DETAIL ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(conv: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val isGroup = conv.address.contains(";")
    Scaffold(containerColor = ThemeState.brandSurf,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(ThemeState.brandSurf, titleContentColor = ThemeState.textPrimary, navigationIconContentColor = ThemeState.textPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, null) } },
            title = { Text(if (isGroup) "Group details" else "Contact details") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(ThemeState.surf).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(80.dp).clip(CircleShape).background(avatarColor(conv.address)), contentAlignment = Alignment.Center) {
                    if (isGroup) Icon(Icons.Default.Group, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    else Text(initial(conv.displayName), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 32.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(conv.displayName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ThemeState.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (!isGroup) { Spacer(Modifier.height(4.dp)); Text(conv.address, fontSize = 14.sp, color = ThemeState.textHint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Spacer(Modifier.height(20.dp))
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ContactAction(Icons.Default.Phone, "Call") { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conv.address}"))) }
                    ContactAction(Icons.Default.Videocam, "Video") { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conv.address}"))) }
                    ContactAction(Icons.Default.Search, "Search") {}
                    ContactAction(Icons.Default.Notifications, "Mute") { Toast.makeText(ctx, "Notifications muted", Toast.LENGTH_SHORT).show() }
                }
                Spacer(Modifier.height(20.dp))
            }
            if (isGroup) {
                val members = conv.address.split(";")
                item {
                    Text("MEMBERS (${members.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ThemeState.textHint, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }
                items(members) { num ->
                    val name = SmsRepository.getContactName(ctx, num.trim()) ?: num.trim()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(num)), contentAlignment = Alignment.Center) {
                            Text(initial(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column { Text(name, fontSize = 15.sp, color = ThemeState.textPrimary); Text(num.trim(), fontSize = 13.sp, color = ThemeState.textHint) }
                    }
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp)
                Spacer(Modifier.height(8.dp))
                DetailRow(Icons.Default.Notifications, "Notifications", "On") {}
                DetailRow(Icons.Default.Image, "Media & files", "Photos, videos, files") {}
                DetailRow(Icons.Default.Star, "Starred messages", "None") {}
                HorizontalDivider(color = ThemeState.divLt, thickness = .5.dp)
                Spacer(Modifier.height(8.dp))
                DetailRow(Icons.Default.Block, "Block & report spam", "", Red) {
                    Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
fun ContactAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(8.dp)) {
        Surface(shape = CircleShape, color = ThemeState.brandLt2, modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = ThemeState.brand, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = ThemeState.textSecond)
    }
}

@Composable
fun DetailRow(icon: ImageVector, title: String, sub: String, tint: Color = ThemeState.textSecond, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = if (tint == Red) Red else ThemeState.textPrimary)
            if (sub.isNotBlank()) Text(sub, fontSize = 13.sp, color = ThemeState.textHint)
        }
    }
}

@Composable
fun DetailInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 14.sp, color = ThemeState.textHint, modifier = Modifier.weight(.35f))
        Text(value, fontSize = 14.sp, color = ThemeState.textPrimary, modifier = Modifier.weight(.65f))
    }
}

// ━━━━━━━━━━━━━━━━ FULL-SCREEN MEDIA VIEWER ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable
fun MediaViewerOverlay(uris: List<Uri>, startIdx: Int, onChangeIdx: (Int) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(startIdx.coerceIn(0, uris.size - 1)) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val uri = uris.getOrNull(idx) ?: return onDismiss()

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(offsetY) / 600f).coerceIn(0.3f, 1f)))
        .pointerInput(uris.size) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    when {
                        offsetX < -100f && idx < uris.size - 1 -> { idx++; onChangeIdx(idx) }
                        offsetX > 100f && idx > 0 -> { idx--; onChangeIdx(idx) }
                    }
                    offsetX = 0f
                },
                onHorizontalDrag = { _, dx -> offsetX += dx }
            )
        }
        .pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = { if (abs(offsetY) > 150f) onDismiss() else offsetY = 0f },
                onVerticalDrag = { _, dy -> offsetY = (offsetY + dy).coerceIn(-400f, 400f) }
            )
        }
        .clickable { onDismiss() }
    ) {
        AsyncImage(uri, "full", Modifier.fillMaxSize()
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .graphicsLayer { alpha = (1f - abs(offsetX) / 400f).coerceIn(0.5f, 1f) },
            contentScale = ContentScale.Fit)
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
            Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        if (uris.size > 1) {
            Text("${idx + 1} / ${uris.size}", color = Color.White.copy(.8f), fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            if (idx > 0) IconButton({ idx--; onChangeIdx(idx) }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
            IconButton({
                try {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply { type = "image/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    ctx.startActivity(Intent.createChooser(shareIntent, "Share"))
                } catch (_: Exception) {}
            }) { Icon(Icons.Default.Share, null, tint = Color.White) }
            if (idx < uris.size - 1) IconButton({ idx++; onChangeIdx(idx) }) { Icon(Icons.Default.ArrowForward, null, tint = Color.White) }
        }
    }
}

// ━━━━━━━━━━━━━━━━ HELPERS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
fun List<Message>.groupByDate(): List<Pair<String, List<Message>>> {
    if (isEmpty()) return emptyList()
    val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val dayFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val today = dateFmt.format(Date()); val yest = dateFmt.format(Date(System.currentTimeMillis() - 86_400_000))
    val groups = mutableListOf<Pair<String, MutableList<Message>>>()
    var lastTime = 0L
    forEach { m ->
        val gap = m.date - lastTime
        if (groups.isEmpty() || gap > 15 * 60 * 1000) {
            val d = Date(m.date)
            val ds = dateFmt.format(d)
            val label = when (ds) {
                today -> "Today • ${timeFmt.format(d)}"
                yest -> "Yesterday • ${timeFmt.format(d)}"
                else -> "${dayFmt.format(d)} • ${timeFmt.format(d)}"
            }
            groups.add(label to mutableListOf(m))
        } else {
            groups.last().second.add(m)
        }
        lastTime = m.date
    }
    return groups
}

@Composable fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(ThemeState.brandLt2).border(0.5.dp, ThemeState.divLt, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(label, fontSize = 12.sp, color = ThemeState.textSecond, fontWeight = FontWeight.Medium)
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
