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


class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { recreate() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNowIfStale(this)
        UpdateCheckReceiver.schedule(this)
        Thread { OtpCleaner.clean(applicationContext) }.start()
        if (savedInstanceState == null) IntentRouter.pending = intent
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
    // Notification taps, sms: links and shares arrive here while the app is open. Route them
    // in place instead of recreating the whole activity (which flashed and lost your place).
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); IntentRouter.pending = intent }
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
    data class WhatsNew(val info: UpdateInfo?, val autoInstall: Boolean = false) : Screen()
    data class Extra(val key: String) : Screen()
}

private const val SEP = "\u0001"
/** Saves the back stack across rotation / theme changes (it used to reset to the list). */
private fun encodeScreen(s: Screen): String = when (s) {
    Screen.List -> "list"
    is Screen.Chat -> listOf("chat", s.conv.threadId.toString(), s.conv.address, s.conv.displayName).joinToString(SEP)
    Screen.NewConv -> "new"
    Screen.Settings -> "settings"
    is Screen.SettingSub -> "sub$SEP${s.key}"
    Screen.Admin -> "admin"
    Screen.Archived -> "archived"
    Screen.Starred -> "starred"
    Screen.Scheduled -> "scheduled"
    Screen.Spam -> "spam"
    is Screen.WhatsNew -> "list"
    is Screen.Extra -> "extra$SEP${s.key}"
}
private fun decodeScreen(v: String): Screen {
    val p = v.split(SEP)
    return when (p[0]) {
        "chat" -> if (p.size >= 4) Screen.Chat(Conversation(p[1].toLongOrNull() ?: 0L, p[2], p[3], "", System.currentTimeMillis(), false)) else Screen.List
        "new" -> Screen.NewConv
        "settings" -> Screen.Settings
        "sub" -> Screen.SettingSub(p.getOrElse(1) { "" })
        "admin" -> Screen.Admin
        "archived" -> Screen.Archived
        "starred" -> Screen.Starred
        "scheduled" -> Screen.Scheduled
        "spam" -> Screen.Spam
        "extra" -> Screen.Extra(p.getOrElse(1) { "" })
        else -> Screen.List
    }
}
private val StackSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<List<Screen>>, ArrayList<String>>(
    save = { st -> ArrayList(st.value.map(::encodeScreen)) },
    restore = { l -> mutableStateOf(l.map(::decodeScreen).ifEmpty { listOf(Screen.List) }) }
)

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
    // Material You: colors from your wallpaper (Android 12+), unless turned off in Settings > Theme
    val useDynamic = remember(ThemeState.version) { prefs.getBoolean("dynamic_color", true) } && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    ThemeState.amoled = remember(ThemeState.version) { prefs.getBoolean("amoled", false) }
    ThemeState.scheme = if (useDynamic) (if (darkBar) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else null
    LaunchedEffect(darkBar, ThemeState.version) {
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

    // Back stack: going back from a chat returns to where you opened it (Archived, Spam, Starred…)
    val stackState = androidx.compose.runtime.saveable.rememberSaveable(saver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.List)) }
    var stack by stackState
    val screen = stack.last()
    fun go(s: Screen) { stack = if (s is Screen.Chat && screen is Screen.Chat) stack.dropLast(1) + s else stack + s }
    fun back() { stack = if (stack.size > 1) stack.dropLast(1) else listOf(Screen.List) }
    fun home() { stack = listOf(Screen.List) }

    LaunchedEffect(Unit) {
        val seen = prefs.getInt("seen_build", 0)
        if (seen != BuildConfig.BUILD_NUMBER) { prefs.edit().putInt("seen_build", BuildConfig.BUILD_NUMBER).apply(); if (seen != 0) go(Screen.WhatsNew(null)) }
    }
    // Notification taps, sms:/smsto: links, "Message" from Contacts, and shares from other apps
    val pendingIntent = IntentRouter.pending
    LaunchedEffect(pendingIntent) {
        val i = pendingIntent ?: return@LaunchedEffect
        IntentRouter.pending = null
        if (i.getBooleanExtra("open_update", false)) {
            stack = listOf(Screen.List, Screen.WhatsNew(UpdateManager.info, i.getBooleanExtra("install_update", false)))
            return@LaunchedEffect
        }
        val target = withContext(Dispatchers.IO) { IntentRouter.resolve(ctx, i) }
        when (target) {
            is IntentRouter.Target.Chat -> { stack = listOf(Screen.List, Screen.Chat(target.conv)) }
            IntentRouter.Target.PickRecipient -> { stack = listOf(Screen.List, Screen.NewConv) }
            null -> {}
        }
    }

    BackHandler(enabled = stack.size > 1) { back() }

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
            Screen.List     -> ConvListScreen({ go(Screen.Chat(it)) }, { go(Screen.NewConv) }, { go(Screen.Settings) }, { go(Screen.Admin) }, { go(Screen.Archived) }, { go(Screen.Starred) }, { go(Screen.Scheduled) }, { go(Screen.Spam) }, { go(Screen.WhatsNew(it)) })
            is Screen.WhatsNew -> UpdateScreen(s.info, s.autoInstall) { back() }
            Screen.Spam     -> SpamScreen({ go(Screen.Chat(it)) }) { back() }
            is Screen.Extra -> ExtraScreen(s.key) { back() }
            is Screen.Chat  -> key(s.conv.threadId) { ThreadScreen(s.conv) { back() } }
            Screen.NewConv  -> NewConvScreen({ stack = stack.dropLast(1) + Screen.Chat(it) }) { ShareState.clear(); back() }
            Screen.Settings -> SettingsScreen({ go(if (it.startsWith("x_")) Screen.Extra(it.removePrefix("x_")) else Screen.SettingSub(it)) }) { back() }
            is Screen.SettingSub -> SettingSubScreen(s.key, { themeChoice = it }) { back() }
            Screen.Admin    -> AdminScreen { back() }
            Screen.Archived -> ArchivedScreen({ go(Screen.Chat(it)) }) { back() }
            Screen.Starred  -> StarredScreen { back() }
            Screen.Scheduled -> ScheduledScreen { back() }
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
    val photo by produceState<Uri?>(initialValue = PhotoCache.map[address], key1 = address) {
        if (!PhotoCache.map.containsKey(address) && !PhotoCache.miss.contains(address)) {
            val u = withContext(Dispatchers.IO) { SmsRepository.contactPhotoUri(ctx, address) }
            if (u != null) PhotoCache.map[address] = u else PhotoCache.miss.add(address)
            value = u
        }
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
        val hnd = Handler(Looper.getMainLooper()); val bump = Runnable { t++ }
        val obs = object : ContentObserver(hnd) { override fun onChange(selfChange: Boolean) { hnd.removeCallbacks(bump); hnd.postDelayed(bump, 350) } }
        ctx.contentResolver.registerContentObserver(Uri.parse("content://mms-sms/"), true, obs)
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) t++ }
        lc?.addObserver(lo)
        onDispose { ctx.contentResolver.unregisterContentObserver(obs); lc?.removeObserver(lo) }
    }
    return t
}

// Update banner on the conversation list. The download itself lives in UpdateManager, so it
// keeps going (with a progress notification) when you leave the screen.
@Composable
fun UpdateCard(info: UpdateInfo, onDismiss: (() -> Unit)?, onDetails: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val st = UpdateManager.state
    LaunchedEffect(info) { if (UpdateManager.info == null || UpdateManager.info!!.build < info.build) UpdateManager.info = info }
    var waitingPerm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        UpdateManager.uiVisible = true
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && waitingPerm && Updater.canInstall(ctx)) { waitingPerm = false; UpdateManager.install(ctx) }
        }
        lc?.addObserver(lo)
        onDispose { lc?.removeObserver(lo); UpdateManager.uiVisible = false }
    }
    fun go() {
        when (st) {
            is UpdState.Ready -> if (Updater.canInstall(ctx)) UpdateManager.install(ctx) else { waitingPerm = true; Toast.makeText(ctx, "Turn on \"Allow from this source\" once, then come back", Toast.LENGTH_LONG).show(); Updater.openInstallPermission(ctx) }
            is UpdState.Downloading, UpdState.Installing -> {}
            else -> UpdateManager.startDownload(ctx, info)
        }
    }
    val title = when (st) {
        is UpdState.Downloading -> "Downloading update"
        is UpdState.Ready -> "Ready to install"
        UpdState.Installing -> "Updating…"
        else -> "Update available"
    }
    val sub = when (st) {
        is UpdState.Downloading -> "${(st.progress * 100).toInt()}%" + (if (st.totalBytes > 0) " · ${UpdateManager.mb(st.doneBytes)} of ${UpdateManager.mb(st.totalBytes)}" else "")
        is UpdState.Ready -> "Build ${info.build} · tap Install"
        UpdState.Installing -> "The app will restart by itself"
        is UpdState.Error -> st.message
        else -> "Build ${info.build}" + (if (info.sizeBytes > 0) " · ${UpdateManager.mb(info.sizeBytes)}" else "")
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
        .clickable { if (onDetails != null && st !is UpdState.Downloading && st !is UpdState.Ready && st != UpdState.Installing) onDetails() else go() },
        shape = RoundedCornerShape(24.dp), color = Tonal) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Brand), contentAlignment = Alignment.Center) {
                    Icon(if (st is UpdState.Ready) Icons.Default.InstallMobile else Icons.Default.SystemUpdate, null, tint = OnBrand, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = OnTonal)
                    Text(sub, fontSize = 13.sp, color = OnTonal.copy(alpha = .8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when (st) {
                    is UpdState.Downloading, UpdState.Installing -> {}
                    else -> FilledTonalButton({ go() }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Brand, contentColor = OnBrand), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        Text(if (st is UpdState.Ready) "Install" else if (st is UpdState.Error) "Retry" else "Update")
                    }
                }
                if (onDismiss != null && st !is UpdState.Downloading && st != UpdState.Installing) IconButton(onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = OnTonal) }
            }
            if (st is UpdState.Downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Brand, trackColor = Bg.copy(alpha = .5f))
            } else if (st == UpdState.Installing) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = Brand, trackColor = Bg.copy(alpha = .5f))
            }
        }
    }
}

// Full update screen: version, size, release notes as cards, and one big button that shows progress.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(info: UpdateInfo?, autoInstall: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var inf by remember { mutableStateOf(info ?: UpdateManager.info) }
    var checking by remember { mutableStateOf(inf == null) }
    LaunchedEffect(Unit) {
        if (inf == null) {
            val i = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            if (i != null && Updater.isNewer(i)) { inf = i; UpdateManager.info = i }
            checking = false
        }
    }
    LaunchedEffect(autoInstall, UpdateManager.state) {
        if (autoInstall && UpdateManager.state is UpdState.Ready && Updater.canInstall(ctx)) UpdateManager.install(ctx)
    }
    val notes = inf?.notes?.takeIf { it.isNotBlank() && !it.startsWith("Install app-debug") } ?: if (inf == null) WHATS_NEW else ""
    val sections = remember(notes) {
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur = "What's new"
        notes.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { l ->
            if (l == "New" || l == "Fixed" || l.endsWith(":") || l.startsWith("#")) cur = l.trimStart('#', ' ').trimEnd(':')
            else out.getOrPut(cur) { mutableListOf() }.add(l.removePrefix("- ").removePrefix("* ").removePrefix("• "))
        }
        out
    }
    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            title = { Text(if (inf != null) "App update" else "What's new", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) },
        bottomBar = { inf?.let { i -> Box(Modifier.navigationBarsPadding().padding(16.dp)) { UpdateButton(i) } } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(Brand), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Chat, null, tint = OnBrand, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Message", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    val line = when {
                        checking -> "Checking for updates…"
                        inf != null -> "Build ${BuildConfig.BUILD_NUMBER}  →  build ${inf!!.build}"
                        else -> "You're on the latest version · build ${BuildConfig.BUILD_NUMBER}"
                    }
                    Text(line, fontSize = 14.sp, color = TextSecond)
                    if (inf != null && inf!!.sizeBytes > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text(UpdateManager.mb(inf!!.sizeBytes)) }, leadingIcon = { Icon(Icons.Default.Download, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = {}, label = { Text("v${BuildConfig.VERSION_NAME}") }, leadingIcon = { Icon(Icons.Default.Info, null, Modifier.size(16.dp)) })
                        }
                    }
                }
            }
            sections.forEach { (title, items) ->
                item {
                    val isFix = title.equals("Fixed", true)
                    Surface(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = RoundedCornerShape(24.dp), color = SearchBg) {
                        Column(Modifier.padding(18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (isFix) Icons.Default.Build else Icons.Default.AutoAwesome, null, tint = Brand, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            }
                            Spacer(Modifier.height(10.dp))
                            items.forEach { t ->
                                Row(Modifier.padding(vertical = 5.dp)) {
                                    Box(Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(Brand))
                                    Spacer(Modifier.width(12.dp))
                                    Text(t, fontSize = 15.sp, color = TextPrimary, lineHeight = 21.sp)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun UpdateButton(info: UpdateInfo) {
    val ctx = LocalContext.current
    val st = UpdateManager.state
    var waitingPerm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        UpdateManager.uiVisible = true
        val lc = (ctx as? ComponentActivity)?.lifecycle
        val lo = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && waitingPerm && Updater.canInstall(ctx)) { waitingPerm = false; UpdateManager.install(ctx) }
        }
        lc?.addObserver(lo)
        onDispose { lc?.removeObserver(lo); UpdateManager.uiVisible = false }
    }
    val progress = (st as? UpdState.Downloading)?.progress
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(if (progress != null) Tonal else Brand)
        .clickable(enabled = st !is UpdState.Downloading && st != UpdState.Installing) {
            when (st) {
                is UpdState.Ready -> if (Updater.canInstall(ctx)) UpdateManager.install(ctx) else { waitingPerm = true; Toast.makeText(ctx, "Turn on \"Allow from this source\" once, then come back", Toast.LENGTH_LONG).show(); Updater.openInstallPermission(ctx) }
                else -> UpdateManager.startDownload(ctx, info)
            }
        }, contentAlignment = Alignment.Center) {
        if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0.02f, 1f)).align(Alignment.CenterStart).background(Brand))
        val label = when (st) {
            is UpdState.Downloading -> "Downloading… ${(st.progress * 100).toInt()}%" + (if (st.totalBytes > 0) "  ·  ${UpdateManager.mb(st.doneBytes)} / ${UpdateManager.mb(st.totalBytes)}" else "")
            is UpdState.Ready -> "Install now"
            UpdState.Installing -> "Installing… the app will restart"
            is UpdState.Error -> "Try again"
            else -> "Download and install" + (if (info.sizeBytes > 0) "  ·  ${UpdateManager.mb(info.sizeBytes)}" else "")
        }
        Text(label, color = OnBrand, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
    (st as? UpdState.Error)?.let { Text(it.message, color = Red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
}
