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

// ━━━━━━━━━━━━ THEME — Google Messages / WhatsApp / Telegram grade ━━━━━━━━━
object ThemeState { var dark by mutableStateOf(false) }
private val D get() = ThemeState.dark

private val Brand: Color        get() = if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
private val BrandDk: Color      get() = if (D) Color(0xFF7CACF8) else Color(0xFF0842A0)
private val OnBrand: Color      get() = if (D) Color(0xFF003258) else Color.White
private val Tonal: Color        get() = if (D) Color(0xFF004A77) else Color(0xFFD3E3FD)
private val OnTonal: Color      get() = if (D) Color(0xFFC2E7FF) else Color(0xFF001D35)
private val Bg: Color           get() = if (D) Color(0xFF131314) else Color.White
private val Surf: Color         get() = if (D) Color(0xFF1E1F20) else Color.White
private val SearchBg: Color     get() = if (D) Color(0xFF2F3033) else Color(0xFFF0F4F9)
private val SheetBg: Color      get() = if (D) Color(0xFF1E1F20) else Color(0xFFF8FAFD)
private val BubbleIn: Color     get() = if (D) Color(0xFF444746) else Color(0xFFE9EEF6)
private val BubbleOut: Color    get() = if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
private val BubbleOutText: Color  get() = if (D) Color(0xFF003258) else Color.White
private val BubbleOutSub: Color   get() = if (D) Color(0xFF003258).copy(.70f) else Color.White.copy(.72f)
private val BubbleOutLink: Color  get() = if (D) Color(0xFF0842A0) else Color(0xFFCDE1FF)
private val TextPrimary: Color  get() = if (D) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
private val TextSecond: Color   get() = if (D) Color(0xFFC4C7C5) else Color(0xFF444746)
private val TextHint: Color     get() = if (D) Color(0xFF8E918F) else Color(0xFF747775)
private val DivLt: Color        get() = if (D) Color(0xFF2F3033) else Color(0xFFE8EAED)
private val AttachSurf: Color   get() = if (D) Color(0xFF2D2F31) else Color(0xFFE9EEF6)
private val Red: Color          get() = if (D) Color(0xFFF2B8B5) else Color(0xFFB3261E)
private val Amber: Color        get() = Color(0xFFFFC107)

private val GMColors: ColorScheme
    @Composable get() = if (ThemeState.dark) darkColorScheme(
        primary = Brand, onPrimary = OnBrand, secondary = Brand,
        background = Bg, surface = Bg, onSurface = TextPrimary,
        surfaceVariant = SearchBg, onSurfaceVariant = TextSecond,
        outline = TextHint, outlineVariant = DivLt
    ) else lightColorScheme(
        primary = Brand, onPrimary = OnBrand, secondary = Brand,
        background = Bg, surface = Bg, onSurface = TextPrimary,
        surfaceVariant = SearchBg, onSurfaceVariant = TextSecond,
        outline = TextHint, outlineVariant = DivLt
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
        Thread { OtpCleaner.clean(applicationContext) }.start()
        setContent {
            val fsc = getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getFloat("font_scale", 0f)
            val dens = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides (if (fsc > 0f) androidx.compose.ui.unit.Density(dens.density, fsc) else dens)) {
            MaterialTheme(colorScheme = GMColors) {
                Surface(Modifier.fillMaxSize(), color = Bg) { AppRoot(isDefaultSmsApp(this)) { requestDefault() } }
            }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }
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
    data object Scheduled : Screen()
    data object Spam : Screen()
    data class WhatsNew(val info: UpdateInfo?) : Screen()
    data class Extra(val key: String) : Screen()
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
        (ctx as? android.app.Activity)?.window?.let { w ->
            w.statusBarColor = Bg.toArgb()
            w.navigationBarColor = Bg.toArgb()
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = !darkBar
            c.isAppearanceLightNavigationBars = !darkBar
        }
    }
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }
    val permL = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasPerms = Permissions.granted(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    LaunchedEffect(Unit) {
        val seen = prefs.getInt("seen_build", 0)
        if (seen != BuildConfig.BUILD_NUMBER) { prefs.edit().putInt("seen_build", BuildConfig.BUILD_NUMBER).apply(); if (seen != 0) screen = Screen.WhatsNew(null) }
        val i = (ctx as? android.app.Activity)?.intent
        val tid = i?.getLongExtra("thread_id", -1L) ?: -1L
        val addr = i?.getStringExtra("address")
        if (tid >= 0 && addr != null) {
            screen = Screen.Chat(Conversation(tid, addr, i.getStringExtra("name") ?: addr, "", System.currentTimeMillis(), false))
            i.removeExtra("thread_id")
        }
    }

    BackHandler(enabled = screen !is Screen.List) {
        screen = when (screen) {
            is Screen.SettingSub -> Screen.Settings
            is Screen.Extra -> Screen.Settings
            else -> Screen.List
        }
    }

    var locked by remember { mutableStateOf(prefs.getBoolean("app_lock", false)) }
    DisposableEffect(Unit) {
        var stopAt = 0L
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopAt = System.currentTimeMillis()
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START && prefs.getBoolean("app_lock", false) && stopAt > 0 && System.currentTimeMillis() - stopAt > 30_000) locked = true
        }
        lc?.addObserver(lo)
        onDispose { lc?.removeObserver(lo) }
    }
    when {
        locked && prefs.getBoolean("app_lock", false) -> LockScreen { locked = false }
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPerms(
            onRequest = { permL.launch(Permissions.ALL) },
            onGranted = { hasPerms = true }
        )
        else -> when (val s = screen) {
            Screen.List     -> ConvListScreen({ screen = Screen.Chat(it) }, { screen = Screen.NewConv }, { screen = Screen.Settings }, { screen = Screen.Admin }, { screen = Screen.Archived }, { screen = Screen.Starred }, { screen = Screen.Scheduled }, { screen = Screen.Spam }, { screen = Screen.WhatsNew(it) })
            is Screen.WhatsNew -> WhatsNewScreen(s.info) { screen = Screen.List }
            Screen.Spam     -> SpamScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            is Screen.Extra -> ExtraScreen(s.key) { screen = Screen.Settings }
            is Screen.Chat  -> ThreadScreen(s.conv) { screen = Screen.List }
            Screen.NewConv  -> NewConvScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            Screen.Settings -> SettingsScreen({ screen = if (it.startsWith("x_")) Screen.Extra(it.removePrefix("x_")) else Screen.SettingSub(it) }) { screen = Screen.List }
            is Screen.SettingSub -> SettingSubScreen(s.key, { themeChoice = it }) { screen = Screen.Settings }
            Screen.Admin    -> AdminScreen { screen = Screen.List }
            Screen.Archived -> ArchivedScreen({ screen = Screen.Chat(it) }) { screen = Screen.List }
            Screen.Starred  -> StarredScreen { screen = Screen.List }
            Screen.Scheduled -> ScheduledScreen { screen = Screen.List }
        }
    }
}

// ━━━━━━━━━━━━━━━━ SETUP SCREENS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@Composable fun SetupScreen(onReq: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Chat, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Message", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Set as your default messaging app to get started", fontSize = 15.sp, color = Color.White.copy(.85f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            Button(onClick = onReq, colors = ButtonDefaults.buttonColors(Color.White, Brand), shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
                Text("Set as default", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
    }
}

@Composable fun SetupPerms(onRequest: () -> Unit, onGranted: () -> Unit) {
    val ctx = LocalContext.current
    var tried by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(Color.White.copy(.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Permissions needed", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("SMS, phone, contacts and notifications", fontSize = 14.sp, color = Color.White.copy(.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = {
                if (!tried) { tried = true; onRequest() }
                else ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", ctx.packageName, null) })
            }, colors = ButtonDefaults.buttonColors(Color.White, Brand), shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
                Text(if (!tried) "Allow access" else "Open system settings", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            if (tried) { Spacer(Modifier.height(14.dp)); Text("Turn on all permissions, then come back", fontSize = 13.sp, color = Color.White.copy(.65f)) }
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(800); if (Permissions.granted(ctx)) { onGranted(); break } } }
}

// ━━━━━━━━━━━━━━━━ SHARED AVATAR (photo → initial fallback) ━━━━━━━━━━━━━━━━
@Composable
fun Avatar(name: String, address: String, size: Dp, fontSize: TextUnit) {
    val ctx = LocalContext.current
    val photo by produceState<Uri?>(initialValue = null, key1 = address) {
        value = withContext(Dispatchers.IO) { SmsRepository.contactPhotoUri(ctx, address) }
    }
    Box(Modifier.size(size).clip(CircleShape).background(avatarColor(address)), contentAlignment = Alignment.Center) {
        if (photo != null) {
            AsyncImage(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val ch = name.firstOrNull()
            if (ch != null && ch.isLetter()) Text(initial(name), color = Color.White, fontWeight = FontWeight.Medium, fontSize = fontSize)
            else Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
        }
    }
}

// Re-fires whenever the SMS/MMS provider changes (new message, status update, read flag) or the app resumes.
@Composable
fun rememberProviderTick(): Int {
    val ctx = LocalContext.current
    var t by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { t++ } }
        ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, obs)
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) t++ }
        lc?.addObserver(lo)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs); lc?.removeObserver(lo) }
    }
    return t
}

// One-tap in-app update card: downloads the newest GitHub release APK and opens the installer.
@Composable
fun UpdateCard(info: UpdateInfo, onDismiss: (() -> Unit)?, onDetails: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var waitingPerm by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    fun doInstall(file: File) {
        waitingPerm = false
        installing = true
        err = null
        if (!Updater.install(ctx, file)) { installing = false; err = "Could not start install. Tap to retry." }
    }
    DisposableEffect(Unit) {
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val f = ready
                if (waitingPerm && f != null && Updater.canInstall(ctx)) doInstall(f)
            }
        }
        lc?.addObserver(lo)
        onDispose { lc?.removeObserver(lo) }
    }
    fun go() {
        val f = ready
        if (f != null) { if (Updater.canInstall(ctx)) doInstall(f) else { waitingPerm = true; Updater.openInstallPermission(ctx) }; return }
        if (progress != null) return
        err = null; progress = 0f
        scope.launch {
            val file = withContext(Dispatchers.IO) { Updater.download(ctx, info) { progress = it } }
            progress = null
            if (file == null) { err = "Download failed. Tap to retry."; return@launch }
            ready = file
            if (Updater.canInstall(ctx)) doInstall(file)
            else { waitingPerm = true; Toast.makeText(ctx, "Turn on \"Allow from this source\" once, then come back", Toast.LENGTH_LONG).show(); Updater.openInstallPermission(ctx) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Tonal).clickable { if (onDetails != null && ready == null && progress == null && !installing) onDetails() else go() }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SystemUpdate, null, tint = OnTonal)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(if (installing) "Updating" else if (ready != null) "Ready to install" else "Update available", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = OnTonal)
            val sub = err ?: when {
                progress != null -> "Downloading… ${((progress ?: 0f) * 100).toInt()}%"
                installing -> "Installing… the app will restart"
                waitingPerm -> "Allow installs from this app, then come back"
                ready != null -> "Tap to install build ${info.build}"
                else -> "Build ${info.build} · tap to download and install"
            }
            Text(sub, fontSize = 13.sp, color = OnTonal.copy(alpha = .8f))
            if (info.notes.isNotBlank() && progress == null && !installing) Text("What's new: " + info.notes.lines().filter { it.isNotBlank() }.take(4).joinToString(" · "), fontSize = 12.sp, color = OnTonal.copy(alpha = .75f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (progress != null) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth(), color = Brand, trackColor = Bg.copy(alpha = .4f)) }
        }
        if (onDetails != null && progress == null && !installing) { Button({ go() }, colors = ButtonDefaults.buttonColors(Brand, OnBrand), contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (ready != null) "Install" else "Update") }; Spacer(Modifier.width(4.dp)) }
        if (onDismiss != null && progress == null) IconButton(onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = OnTonal) }
    }
}

// ━━━━━━━━━━━━━━━━ CONVERSATION LIST ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
object ConvCache { @Volatile var list: List<Conversation>? = null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit, onArchived: () -> Unit, onStarred: () -> Unit, onScheduled: () -> Unit, onSpam: () -> Unit = {}, onWhatsNew: (UpdateInfo) -> Unit = {}) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(ConvCache.list ?: emptyList()) }
    var loaded by remember { mutableStateOf(ConvCache.list != null) }
    var search by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var ctxConv by remember { mutableStateOf<Conversation?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val fabExtended by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    fun refresh() { scope.launch { list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; ConvCache.list = list; loaded = true } }
    val providerTick = rememberProviderTick()
    LaunchedEffect(providerTick) {
        if (providerTick > 1) delay(150)
        val fresh = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }
        if (fresh != list) list = fresh
        ConvCache.list = fresh; loaded = true
        tick++
    }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }
    var latestKnown by remember { mutableIntStateOf(ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getInt("upd_latest", 0)) }
    LaunchedEffect(Unit) {
        val p = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("upd_last", 0L) > 20 * 60_000L) {
            val i = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (i != null) { p.edit().putLong("upd_last", System.currentTimeMillis()).putInt("upd_latest", i.build).apply(); latestKnown = i.build }
            if (i != null && Updater.isNewer(i) && p.getInt("upd_dismissed", 0) < i.build) update = i
        }
    }

    val snackbar = remember { SnackbarHostState() }
    var hidden by remember { mutableStateOf(setOf<Long>()) }
    fun act(kind: String, c: Conversation) {
        scope.launch {
            if (kind == "delete") {
                hidden = hidden + c.threadId
                val r = snackbar.showSnackbar("Conversation deleted", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) hidden = hidden - c.threadId
                else { withContext(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }; refresh(); hidden = hidden - c.threadId }
            } else {
                ArchiveStore.setArchived(ctx, c.threadId, true); tick++
                val r = snackbar.showSnackbar("Conversation archived", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) { ArchiveStore.setArchived(ctx, c.threadId, false); tick++ }
            }
        }
    }
    val spamIds = remember(list, tick) { SpamStore.all(ctx) }
    val hits by produceState(emptyList<MsgHit>(), search) {
        value = if (search.trim().length >= 2) { delay(250); withContext(Dispatchers.IO) { SmsRepository.searchMessages(ctx, search.trim()) } } else emptyList()
    }
    val visible = remember(list, tick, hidden, spamIds) { list.filter { !ArchiveStore.isArchived(ctx, it.threadId) && !BlockedStore.isBlocked(ctx, it.address) && it.threadId !in hidden && it.threadId !in spamIds } }
    var cat by remember { mutableStateOf("All") }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    BackHandler(enabled = sel.isNotEmpty()) { sel = emptySet() }
    val searched0 = if (cat == "All") visible else visible.filter { chatCategory(it) == cat }
    val searched = if (search.isBlank()) searched0 else searched0.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) || it.address.contains(search, true) }
    val filtered = remember(searched, tick) { searched.sortedWith(compareByDescending<Conversation> { if (PinStore.isPinned(ctx, it.threadId)) 1 else 0 }.thenByDescending { it.date }) }

    if (ctxConv != null) {
        val c = ctxConv!!
        val pinned = PinStore.isPinned(ctx, c.threadId)
        AlertDialog(
            onDismissRequest = { ctxConv = null },
            containerColor = Surf,
            shape = RoundedCornerShape(28.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.displayName, c.address, 40.dp, 16.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(c.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                }
            },
            text = {
                Column {
                    MenuBtn(Icons.Default.PushPin, if (pinned) "Unpin" else "Pin to top") {
                        PinStore.toggle(ctx, c.threadId); tick++; ctxConv = null
                    }
                    MenuBtn(Icons.Default.CheckCircle, "Select") { sel = setOf(c.threadId); ctxConv = null }
                    MenuBtn(Icons.Default.Archive, "Archive") {
                        act("archive", c); ctxConv = null
                    }
                    MenuBtn(if (c.unread) Icons.Default.Drafts else Icons.Default.MarkEmailUnread,
                        if (c.unread) "Mark as read" else "Mark as unread") {
                        scope.launch { withContext(Dispatchers.IO) { if (c.unread) SmsRepository.markThreadRead(ctx, c.threadId) else SmsRepository.markThreadUnread(ctx, c.threadId) }; refresh() }
                        ctxConv = null
                    }
                    MenuBtn(Icons.Default.Block, "Block ${c.displayName}", Red) {
                        BlockedStore.block(ctx, c.address)
                        Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); tick++; ctxConv = null
                    }
                    MenuBtn(Icons.Default.Delete, "Delete", Red) {
                        act("delete", c); ctxConv = null
                    }
                }
            },
            confirmButton = { TextButton({ ctxConv = null }) { Text("Cancel", color = Brand) } }
        )
    }

    Scaffold(containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, actionIconContentColor = TextSecond),
                title = { Text("Messages", fontWeight = FontWeight.Bold, fontSize = 24.sp) },
                actions = {
                    Box {
                        IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf), shape = RoundedCornerShape(16.dp)) {
                            DropdownMenuItem({ Text("Starred messages", color = TextPrimary) }, { showMenu = false; onStarred() }, leadingIcon = { Icon(Icons.Default.Star, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Archived", color = TextPrimary) }, { showMenu = false; onArchived() }, leadingIcon = { Icon(Icons.Default.Archive, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Scheduled", color = TextPrimary) }, { showMenu = false; onScheduled() }, leadingIcon = { Icon(Icons.Default.Schedule, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Spam", color = TextPrimary) }, { showMenu = false; onSpam() }, leadingIcon = { Icon(Icons.Default.Report, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Mark all as read", color = TextPrimary) }, {
                                showMenu = false
                                scope.launch(Dispatchers.IO) { list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) } }
                                refresh()
                            }, leadingIcon = { Icon(Icons.Default.DoneAll, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Settings", color = TextPrimary) }, { showMenu = false; onSettings() }, leadingIcon = { Icon(Icons.Default.Settings, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Admin panel", color = TextPrimary) }, { showMenu = false; onAdmin() }, leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null, tint = TextSecond) })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = Tonal, contentColor = OnTonal, shape = RoundedCornerShape(16.dp),
                expanded = fabExtended,
                icon = { Icon(Icons.Filled.Chat, "New chat") }, text = { Text("Start chat", fontWeight = FontWeight.SemiBold) })
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Google-style always-visible search bar
            TextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search conversations", color = TextHint, fontSize = 15.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextHint) },
                trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Icons.Default.Close, null, tint = TextHint) } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Personal", "Transactions", "Promos").forEach { c ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (cat == c) Tonal else SearchBg).clickable { cat = c }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(c, fontSize = 13.sp, fontWeight = if (cat == c) FontWeight.SemiBold else FontWeight.Normal, color = if (cat == c) OnTonal else TextSecond)
                    }
                }
            }
            if (sel.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Tonal).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ sel = emptySet() }) { Icon(Icons.Default.Close, "Cancel", tint = OnTonal) }
                Text("${sel.size} selected", Modifier.weight(1f), color = OnTonal, fontWeight = FontWeight.SemiBold)
                IconButton({ sel.forEach { PinStore.toggle(ctx, it) }; sel = emptySet(); tick++ }) { Icon(Icons.Default.PushPin, "Pin", tint = OnTonal) }
                IconButton({ val ids = sel; scope.launch { withContext(Dispatchers.IO) { ids.forEach { SmsRepository.markThreadRead(ctx, it) } }; refresh() }; sel = emptySet() }) { Icon(Icons.Default.DoneAll, "Mark read", tint = OnTonal) }
                IconButton({ val ids = sel; list.filter { it.threadId in ids }.forEach { act("archive", it) }; sel = emptySet() }) { Icon(Icons.Default.Archive, "Archive", tint = OnTonal) }
                IconButton({ val ids = sel; list.filter { it.threadId in ids }.forEach { act("delete", it) }; sel = emptySet() }) { Icon(Icons.Default.Delete, "Delete", tint = OnTonal) }
            }
            Text("Message ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER} · " + (if (latestKnown > BuildConfig.BUILD_NUMBER) "update available" else if (latestKnown > 0) "up to date" else "checking for updates…"),
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp), fontSize = 11.sp, color = if (latestKnown > BuildConfig.BUILD_NUMBER) Brand else TextHint)
            update?.let { u -> UpdateCard(u, { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putInt("upd_dismissed", u.build).apply(); update = null }, { onWhatsNew(u) }) }
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty() && hits.isEmpty() && loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Forum, null, tint = TextHint.copy(.6f), modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(if (search.isBlank()) "No conversations yet" else "No results for \"$search\"", color = TextHint, fontSize = 15.sp)
                        if (search.isBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Tap Start chat to send your first message", color = TextHint.copy(.8f), fontSize = 13.sp)
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filtered, key = { it.threadId }) { conv ->
                        fun toggleSel(x: Conversation) { sel = if (x.threadId in sel) sel - x.threadId else sel + x.threadId }
                        Box(Modifier.background(if (conv.threadId in sel) Tonal else Color.Transparent)) {
                        SwipeableConvRow(conv, PinStore.isPinned(ctx, conv.threadId), { if (sel.isNotEmpty()) toggleSel(it) else onOpen(it) }, onRefresh = { refresh(); tick++ }, onLongPress = { if (sel.isNotEmpty()) toggleSel(it) else ctxConv = it }, onAction = { k, cc -> act(k, cc) })
                        }
                    }
                    if (hits.isNotEmpty()) {
                        item { Text("Messages", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp)) }
                        items(hits, key = { "hit" + it.id }) { h ->
                            Row(Modifier.fillMaxWidth().clickable { onOpen(Conversation(h.threadId, h.address, h.name, h.body, h.date, false)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(h.name, h.address, 40.dp, 16.sp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Row { Text(h.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = TextPrimary); Text(shortTime(h.date), fontSize = 12.sp, color = TextHint) }
                                    Text(h.body, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextSecond)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Swipeable conversation row ───────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeableConvRow(c: Conversation, pinned: Boolean, onOpen: (Conversation) -> Unit, onRefresh: () -> Unit, onLongPress: (Conversation) -> Unit, onAction: (String, Conversation) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var offsetX by remember { mutableFloatStateOf(0f) }
    val threshold = 150f
    val swipeAction = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("swipe_action", "archive") ?: "archive" }
    val actionLabel = when (swipeAction) { "delete" -> "Delete"; "read" -> "Read"; "none" -> ""; else -> "Archive" }
    val actionColor = when (swipeAction) { "delete" -> Red; "read" -> Brand; else -> Color(0xFF5F6368) }

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
                .background(Bg)
                .combinedClickable(
                    onClick = { onOpen(c) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(c) }
                )
                .pointerInput(c.threadId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold && swipeAction != "none") {
                                when (swipeAction) {
                                    "delete" -> onAction("delete", c)
                                    "read" -> { scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId) }; Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show(); onRefresh() }
                                    else -> onAction("archive", c)
                                }
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dx -> offsetX = (offsetX + dx).coerceIn(-200f, 0f) }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(c.displayName, c.address, 50.dp, 19.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) { Icon(Icons.Default.PushPin, "Pinned", tint = TextHint, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = TextPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text(shortTime(c.date), fontSize = 12.sp,
                        color = if (c.unread) Brand else TextHint,
                        fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal)
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (DraftStore.get(ctx, c.threadId).isNotBlank()) "Draft: " + DraftStore.get(ctx, c.threadId) else (if (c.outgoing) "You: " else "") + c.snippet.ifBlank { " " }),
                        fontSize = 14.sp, color = if (DraftStore.get(ctx, c.threadId).isNotBlank()) Red else if (c.unread) TextPrimary else TextHint,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.weight(1f))
                    if (c.unread && c.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(CircleShape).background(Brand).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                            Text(if (c.unreadCount > 99) "99+" else c.unreadCount.toString(), color = OnBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
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

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("Archived", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Archive, null, tint = TextHint.copy(.6f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No archived conversations", color = TextHint, fontSize = 15.sp)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(list, key = { it.threadId }) { conv ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(conv) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conv.displayName, conv.address, 48.dp, 18.sp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conv.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(conv.snippet, fontSize = 14.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { ArchiveStore.setArchived(ctx, conv.threadId, false); tick++ }) { Text("Unarchive", color = Brand) }
                }
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

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("Starred messages", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (starred.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Star, null, tint = TextHint.copy(.6f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No starred messages", color = TextHint, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Long-press a message and tap Star", color = TextHint.copy(.8f), fontSize = 13.sp)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(starred) { (conv, m) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conv.displayName, conv.address, 44.dp, 16.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(conv.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Star, null, tint = Amber, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.weight(1f))
                            Text(shortTime(m.date), fontSize = 11.sp, color = TextHint)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(m.body.ifBlank { if (m.imageUri != null) "📷 Media" else " " }, fontSize = 14.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(thickness = .5.dp, color = DivLt)
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ SCHEDULED MESSAGES SCREEN ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items2 by remember { mutableStateOf(emptyList<ScheduledMessage>()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { items2 = ScheduledStore.getAll(ctx) }
    val fmt = remember { SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault()) }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("Scheduled messages", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (items2.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Schedule, null, tint = TextHint.copy(.6f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing scheduled", color = TextHint, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("In a chat: menu → Schedule send", color = TextHint.copy(.8f), fontSize = 13.sp)
                }
            }
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items2, key = { it.id }) { sm ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Schedule, null, tint = OnTonal, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SmsRepository.getContactName(ctx, sm.address) ?: sm.address, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sm.body, fontSize = 13.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(fmt.format(Date(sm.sendAt)), fontSize = 12.sp, color = Brand, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = { ScheduledSendReceiver.cancel(ctx, sm.id); tick++ }) { Text("Cancel", color = Red) }
                }
            }
        }
    }
}

// ━━━━━━━━━━━━━━━━ NEW CONVERSATION ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
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

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("New conversation", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TextField(to, { to = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Type a name or number", color = TextHint) },
                leadingIcon = { Text("To:", color = TextHint, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp)) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Phone),
                keyboardActions = KeyboardActions(onDone = { if (to.isNotBlank() && !isGroup) openConv(contacts.find { it.second == to.trim() }?.first ?: to.trim(), to.trim()) }))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { isGroup = !isGroup }, shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (isGroup) Tonal else SearchBg, contentColor = if (isGroup) OnTonal else TextPrimary)) {
                    Icon(if (isGroup) Icons.Default.GroupRemove else Icons.Default.GroupAdd, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isGroup) "Cancel group" else "Create group")
                }
            }
            if (isGroup && selected.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selected.forEach { (name, num) ->
                        InputChip(selected = true, onClick = { selected = selected.filter { it.second != num } }, label = { Text(name, fontSize = 13.sp) },
                            trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                            colors = InputChipDefaults.inputChipColors(selectedContainerColor = Tonal, selectedLabelColor = OnTonal, selectedTrailingIconColor = OnTonal))
                    }
                    if (selected.size >= 2) {
                        Button(onClick = {
                            val addr = selected.joinToString(";") { it.second }
                            val names = selected.joinToString(", ") { it.first }
                            onOpen(Conversation(addr.hashCode().toLong(), addr, names, "", System.currentTimeMillis(), false))
                        }, colors = ButtonDefaults.buttonColors(Brand, contentColor = OnBrand), shape = RoundedCornerShape(20.dp)) { Text("Start group", fontSize = 13.sp) }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.second }) { (name, number) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (isGroup) { if (selected.none { it.second == number }) selected = selected + (name to number); to = "" }
                        else openConv(name, number)
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name, number, 44.dp, 17.sp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(number, fontSize = 13.sp, color = TextHint, maxLines = 1) }
                        if (isGroup && selected.any { it.second == number }) Icon(Icons.Default.CheckCircle, null, tint = Brand)
                    }
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
    var draft by remember { mutableStateOf(DraftStore.get(ctx, conversation.threadId)) }
    val draftNow by rememberUpdatedState(draft)
    DisposableEffect(Unit) { onDispose { DraftStore.set(ctx, conversation.threadId, draftNow) } }
    var showDetails by remember { mutableStateOf(false) }
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
    var recordSeconds by remember { mutableIntStateOf(0) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var reactTick by remember { mutableIntStateOf(0) }
    var lastCount by remember { mutableIntStateOf(-1) }

    fun reload() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { SmsRepository.loadMessages(ctx, conversation.threadId) }
            val filteredMsgs = raw.filter { it.body.isNotBlank() || it.imageUri != null }
            val grew = filteredMsgs.size != msgs.size
            msgs = filteredMsgs
            if (grew || lastCount == -1) {
                lastCount = filteredMsgs.size
                if (filteredMsgs.isNotEmpty()) listState.animateScrollToItem(filteredMsgs.size - 1)
            }
        }
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
        ScheduledSendReceiver.schedule(ctx, conversation.address, body, at)
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
                        ScheduledSendReceiver.schedule(ctx, conversation.address, body, t.timeInMillis)
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

    // ── Message context menu (long-press) ──
    if (ctxMsg != null) {
        val m = ctxMsg!!
        val key = msgKey(m)
        val starred = StarStore.isStarred(ctx, key)
        val failed = m.status == Telephony.Sms.STATUS_FAILED
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
                                if (SmsRepository.sendSms(ctx, conversation.address, m.body)) SmsRepository.deleteMessage(ctx, m.id)
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
                    MenuBtn(Icons.Default.Star, if (starred) "Unstar" else "Star", Amber) {
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

    Scaffold(containerColor = Bg,
        topBar = {
            TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextSecond),
                navigationIcon = { IconButton(if (showSearch) { { showSearch = false; searchQuery = "" } } else onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = {
                    if (showSearch) {
                        TextField(searchQuery, { searchQuery = it }, placeholder = { Text("Search in chat…", color = TextHint) }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary), modifier = Modifier.fillMaxWidth())
                    } else Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(conversation.displayName, conversation.address, 40.dp, 16.sp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = TextPrimary)
                            Text(if (conversation.address.contains(";")) "Group · ${conversation.address.split(";").size} people" else conversation.address, fontSize = 12.sp, color = TextHint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    if (showSearch) {
                        IconButton({ showSearch = false; searchQuery = "" }) { Icon(Icons.Default.Close, "Close search") }
                    } else {
                        if (!conversation.address.contains(";")) {
                            IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}"))) }) { Icon(Icons.Default.Phone, "Call") }
                        }
                        Box {
                            IconButton({ showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                            DropdownMenu(showMenu, { showMenu = false }, modifier = Modifier.background(Surf), shape = RoundedCornerShape(16.dp)) {
                                DropdownMenuItem({ Text("Search", color = TextPrimary) }, { showMenu = false; showSearch = true }, leadingIcon = { Icon(Icons.Default.Search, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Details", color = TextPrimary) }, { showMenu = false; showDetails = true }, leadingIcon = { Icon(Icons.Default.Info, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Quick replies", color = TextPrimary) }, { showMenu = false; showQuick = true }, leadingIcon = { Icon(Icons.Default.Reply, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Media and links", color = TextPrimary) }, { showMenu = false; showMedia = true }, leadingIcon = { Icon(Icons.Default.PhotoLibrary, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Add to home screen", color = TextPrimary) }, { showMenu = false; pinChatShortcut(ctx, conversation) }, leadingIcon = { Icon(Icons.Default.AddToHomeScreen, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Schedule send", color = TextPrimary) }, { showMenu = false; pickScheduleTime() }, leadingIcon = { Icon(Icons.Default.Schedule, null, tint = TextSecond) })
                                DropdownMenuItem({ Text(if (prefs.getBoolean("muted_${conversation.threadId}", false)) "Unmute" else "Mute notifications", color = TextPrimary) }, {
                                    showMenu = false
                                    val k = "muted_${conversation.threadId}"
                                    prefs.edit().putBoolean(k, !prefs.getBoolean(k, false)).apply()
                                    Toast.makeText(ctx, if (prefs.getBoolean(k, false)) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
                                }, leadingIcon = { Icon(Icons.Default.NotificationsOff, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Notification sound", color = TextPrimary) }, {
                                    showMenu = false
                                    toneL.launch(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION).putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true))
                                }, leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = TextSecond) })
                                DropdownMenuItem({ Text("Block & report spam", color = TextPrimary) }, {
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
                                decorationBox = { inner -> Box { if (draft.isEmpty()) Text(if (conversation.address.contains(";")) "Group message" else "Text message", color = TextHint, fontSize = 16.sp); inner() } }
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
            val displayMsgs = if (showSearch && searchQuery.isNotBlank()) msgs.filter { it.body.contains(searchQuery, true) } else msgs
            val grouped = displayMsgs.groupByDate()
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
private val URL_REGEX = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!\$&'()*+,;=%]+)", RegexOption.IGNORE_CASE)

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
    val failed = m.status == Telephony.Sms.STATUS_FAILED
    val sending = m.status == Telephony.Sms.STATUS_PENDING
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
                }
            }
                if (showMeta || failed || sending || starred) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (failed) {
                            Icon(Icons.Default.ErrorOutline, "Failed", tint = Color(0xFFD93025), modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Not sent", fontSize = 11.sp, color = Color(0xFFD93025), fontWeight = FontWeight.Medium)
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
                                    m.status == Telephony.Sms.STATUS_COMPLETE -> Icons.Default.DoneAll
                                    else -> Icons.Default.Check
                                },
                                when { sending -> "Sending"; m.status == Telephony.Sms.STATUS_COMPLETE -> "Delivered"; else -> "Sent" },
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

// ━━━━━━━━━━━━━━━━ SETTINGS ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onSub: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
            title = { Text("Settings", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item { SettingsHeader("General") }
            item { SettRow(Icons.Default.Notifications, "Notifications", "Sounds, vibration, previews") { onSub("notifications") } }
            item { SettRow(Icons.Default.Shield, "Protection & safety", "Spam and blocked numbers") { onSub("safety") } }
            item { SettRow(Icons.Default.SwipeRight, "Swipe actions", "What swiping a chat does") { onSub("swipe") } }
            item { SettRow(Icons.Default.Tune, "Advanced", "Delivery reports, MMS") { onSub("advanced") } }
            item { SettingsHeader("Appearance") }
            item { SettRow(Icons.Default.Palette, "Theme", "System, light or dark") { onSub("theme") } }
            item { SettRow(Icons.Default.ChatBubble, "Chat bubbles", "Bubble color") { onSub("bubbles") } }
            item { SettRow(Icons.Default.FormatSize, "Text size", "Message text size") { onSub("textsize") } }
            item { SettRow(Icons.Default.Link, "Automatic previews", "Web link previews") { onSub("previews") } }
            item { SettingsHeader("Privacy & storage") }
            item { SettRow(Icons.Default.Report, "Spam protection", "Filter scam texts") { onSub("x_spam") } }
            item { SettRow(Icons.Default.Lock, "App lock", "Fingerprint, face or PIN") { onSub("x_lock") } }
            item { SettRow(Icons.Default.DeleteSweep, "Verification codes", "Auto-delete old codes") { onSub("x_otp") } }
            item { SettRow(Icons.Default.Reply, "Quick replies", "Edit your canned replies") { onSub("x_quick") } }
            item { SettRow(Icons.Default.TextFields, "Large text & display", "Bigger text everywhere") { onSub("x_access") } }
            item { SettRow(Icons.Default.Palette, "Chat style", "Bubble shape and backgrounds") { onSub("x_style") } }
            item { SettRow(Icons.Default.Backup, "Backup & restore", "Save or restore your texts") { onSub("x_backup") } }
            item { SettingsHeader("About") }
            item { SettRow(Icons.Default.Info, "About Message", "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})") { onSub("about") } }
        }
    }
}

@Composable fun SettingsHeader(label: String) {
    Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 18.dp, top = 18.dp, bottom = 6.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingSubScreen(key: String, onThemeChange: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    val title = when (key) { "notifications" -> "Notifications"; "bubbles" -> "Chat bubbles"; "theme" -> "Theme"; "textsize" -> "Text size"
        "previews" -> "Automatic previews"; "safety" -> "Protection & safety"; "swipe" -> "Swipe actions"; "advanced" -> "Advanced"; "about" -> "About"; else -> "Settings" }
    var blockedTick by remember { mutableIntStateOf(0) }
    val blocked = remember(blockedTick) { BlockedStore.getAll(ctx).toList() }
    var themeNow by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, title = { Text(title, fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 18.dp)) {
            when (key) {
                "notifications" -> {
                    item { ToggleRow("Allow notifications", "notif_allow", prefs) }
                    item { ToggleRow("Sound", "notif_sound", prefs) }
                    item { ToggleRow("Vibrate", "notif_vibrate", prefs) }
                    item { ToggleRow("Show sender name", "notif_sender", prefs) }
                    item { ToggleRow("Show message preview", "notif_preview", prefs) }
                    item {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            Notifications.showTestNotification(ctx)
                            Toast.makeText(ctx, "Test notification sent", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(Tonal, OnTonal), shape = RoundedCornerShape(50)) {
                            Text("Send test notification")
                        }
                    }
                }
                "bubbles" -> {
                    item { Spacer(Modifier.height(10.dp)); Text("Your bubble color", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary); Spacer(Modifier.height(12.dp)) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            listOf(Color(0xFF0B57D0), Color(0xFF1E8E3E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFD93025), Color(0xFF185ABC), Color(0xFFE65100), Color(0xFF00897B)).forEach { clr ->
                                val sel = prefs.contains("bubble_color") && prefs.getInt("bubble_color", 0) == clr.toArgb()
                                Box(Modifier.size(44.dp).clip(CircleShape).background(clr).border(if (sel) 3.dp else 0.dp, if (sel) TextPrimary else Color.Transparent, CircleShape).clickable {
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
                        Spacer(Modifier.height(14.dp))
                        Text("Preview", fontSize = 13.sp, color = TextHint)
                        Spacer(Modifier.height(8.dp))
                        val pvColor = if (prefs.contains("bubble_color")) Color(prefs.getInt("bubble_color", BubbleOut.toArgb())) else BubbleOut
                        val pvText = if (prefs.contains("bubble_color")) Color.White else BubbleOutText
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp, 20.dp, 5.dp, 20.dp)).background(pvColor).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Column {
                                Text("Hello! This is how your messages look.", color = pvText, fontSize = 15.sp)
                                Text("3:25 PM ✓", color = pvText.copy(.72f), fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
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
                    item { RadioRow("No previews", prefs.getString("preview_mode", "links") == "none") { prefs.edit().putString("preview_mode", "none").apply() } }
                }
                "safety" -> {
                    item { ToggleRow("Spam protection", "spam_protect", prefs) }
                    item { ToggleRow("Warn about suspicious links", "warn_links", prefs) }
                    item { ToggleRow("Blur sensitive images", "blur_sensitive", prefs) }
                    item {
                        Spacer(Modifier.height(18.dp))
                        Text("Blocked numbers", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (blocked.isEmpty()) {
                        item { Text("No blocked numbers", color = TextHint, fontSize = 14.sp) }
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
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = { showConfirm = true }, shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                            border = BorderStroke(1.dp, Red.copy(.5f))) {
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
                    item {
                        Spacer(Modifier.height(20.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Chat, null, tint = OnBrand, modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("Message", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                            Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.BUILD_NUMBER})", fontSize = 14.sp, color = TextHint)
                            Spacer(Modifier.height(14.dp))
                            Text("Simple, fast text messaging.", fontSize = 14.sp, color = TextSecond, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            var checking by remember { mutableStateOf(false) }
                            var found by remember { mutableStateOf<UpdateInfo?>(null) }
                            var msg by remember { mutableStateOf("") }
                            val sc = rememberCoroutineScope()
                            found?.let { UpdateCard(it, null) }
                            if (found == null) {
                                Button(onClick = {
                                    if (!checking) { checking = true; msg = ""; sc.launch {
                                        val i = withContext(Dispatchers.IO) { Updater.fetchLatest() }
                                        checking = false
                                        if (i == null) msg = "Could not check. Are you online?"
                                        else if (Updater.isNewer(i)) found = i else msg = "You're up to date"
                                    } }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Brand, contentColor = OnBrand)) { Text(if (checking) "Checking…" else "Check for updates") }
                                if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg, fontSize = 13.sp, color = TextHint) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun ToggleRow(label: String, key: String, prefs: android.content.SharedPreferences) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, true)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, color = TextPrimary); Switch(on, { on = it; prefs.edit().putBoolean(key, it).apply() }, colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = OnBrand))
    }
}
@Composable fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, colors = RadioButtonDefaults.colors(selectedColor = Brand)); Spacer(Modifier.width(12.dp)); Text(label, fontSize = 15.sp, color = TextPrimary)
    }
}
@Composable fun SettRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = TextSecond, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 16.sp, color = TextPrimary); Spacer(Modifier.height(1.dp)); Text(sub, fontSize = 13.sp, color = TextHint) }
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

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }, title = { Text("Admin panel", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp)) {
            item {
                SettingsHeader("Contact search")
                if (!configured) {
                    Text("Contact search is not configured in this build.", fontSize = 14.sp, color = TextSecond)
                } else {
                    TextField(q, {
                        q = it; if (it.isNotBlank()) search(it) else { resLines = emptyList(); status = "" }
                    }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or number…", color = TextHint) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = TextHint) },
                        colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand))
                }
                Spacer(Modifier.height(12.dp))
            }
            items(resLines) { line -> Text(line, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 4.dp)) }
            if (status.isNotBlank()) item { Text(status, fontSize = 12.sp, color = TextHint, modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                SettingsHeader("Device info")
                Text("Model: ${Build.MODEL}", fontSize = 14.sp, color = TextPrimary)
                Text("Android: ${Build.VERSION.RELEASE}", fontSize = 14.sp, color = TextSecond)
                Text("Default SMS app: ${if (isDefaultSmsApp(ctx)) "yes" else "no"}", fontSize = 14.sp, color = TextSecond); Spacer(Modifier.height(12.dp))
                if (configured) {
                    Button({ ContactsSyncService.syncNow(ctx); Toast.makeText(ctx, "Syncing…", Toast.LENGTH_SHORT).show() }, colors = ButtonDefaults.buttonColors(Tonal, OnTonal), shape = RoundedCornerShape(50)) { Text("Sync contacts now") }
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
    forEach { m -> val l = when (val d = fmt.format(Date(m.date))) { today -> "Today"; yest -> "Yesterday"; else -> if (Calendar.getInstance().apply { timeInMillis = m.date }.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(m.date)) else d }; g.getOrPut(l) { mutableListOf() }.add(m) }
    return g.map { it.key to it.value }
}

@Composable fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(50)).background(SearchBg).padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(label, fontSize = 12.sp, color = TextSecond, fontWeight = FontWeight.Medium)
        }
    }
}
private val palette = listOf(Color(0xFF0B57D0), Color(0xFF146C2E), Color(0xFF8C4A2E), Color(0xFF6A2E8C), Color(0xFFB3261E), Color(0xFF185ABC), Color(0xFF0D652D), Color(0xFF9C27B0))
fun avatarColor(seed: String): Color = palette[seed.hashCode().mod(palette.size)]
fun initial(name: String): String = (name.firstOrNull()?.uppercaseChar() ?: '#').toString()
fun msgTime(ms: Long): String { if (ms <= 0) return ""; return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)) }
fun shortTime(ms: Long): String {
    if (ms <= 0) return ""
    val now = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ms }
    val dayDiff = ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) - Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis) / 86_400_000L
    return when {
        dayDiff <= 0 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
        dayDiff == 1L -> "Yesterday"
        dayDiff < 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        t.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR) -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("M/d/yy", Locale.getDefault()).format(Date(ms))
    }
}
