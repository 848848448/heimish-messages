package com.heimish.messages

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ── Google Messages Material 3 Expressive colours ───────────────────────────
private val Brand       = Color(0xFF0B57D0)
private val BrandLight  = Color(0xFFD3E3FD)
private val BrandLight2 = Color(0xFFEDF2FA)
private val BubbleIn    = Color(0xFFE3E3E3)
private val BubbleOut   = Color(0xFF0B57D0)
private val BgSurf      = Color(0xFFF6F8FC)
private val TextDark    = Color(0xFF1F1F1F)
private val TextMeta    = Color(0xFF444746)
private val TextMetaLt  = Color(0xFF747775)
private val DivColor    = Color(0xFFE8EAED)

private val GMColors = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    secondary = Brand, onSecondary = Color.White,
    background = BgSurf, surface = Color.White,
    onSurface = TextDark, outline = DivColor,
    surfaceVariant = BrandLight2
)

class MainActivity : ComponentActivity() {
    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BrandLight.toArgb()

        SmsSyncService.start(this)
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNow(this)

        setContent {
            MaterialTheme(colorScheme = GMColors) {
                Surface(Modifier.fillMaxSize(), color = BrandLight) {
                    AppRoot(isDefault = isDefaultSmsApp(this), onRequestDefault = { requestDefault() })
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

// ── Root ─────────────────────────────────────────────────────────────────────
@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    var openThread by remember { mutableStateOf<Conversation?>(null) }
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPerms = Permissions.granted(ctx) }
    LaunchedEffect(Unit) { if (!hasPerms) permLauncher.launch(Permissions.ALL) }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPermissions { permLauncher.launch(Permissions.ALL) }
        openThread != null -> ThreadScreen(openThread!!) { openThread = null }
        else -> ConversationListScreen { openThread = it }
    }
}

// ── Setup screens ────────────────────────────────────────────────────────────
@Composable
fun SetupScreen(onRequestDefault: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Message, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Heimish Messages", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Set as your default messaging app to send & receive texts.",
                fontSize = 15.sp, color = Color.White.copy(alpha = .85f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onRequestDefault,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand),
                shape = RoundedCornerShape(50)
            ) { Text("Set as Default", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SetupPermissions(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brand), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Permissions Required", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Allow SMS and Contacts access.", fontSize = 15.sp, color = Color.White.copy(alpha = .85f))
            Spacer(Modifier.height(28.dp))
            Button(onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand),
                shape = RoundedCornerShape(50)
            ) { Text("Allow Access", fontWeight = FontWeight.Bold) }
        }
    }
}

// ── Conversation list (Google Messages M3E) ──────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onOpen: (Conversation) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { list = SmsRepository.loadConversations(ctx) }
    // Auto-refresh every 5s
    LaunchedEffect(Unit) { while (true) { delay(5000); list = SmsRepository.loadConversations(ctx) } }

    val filtered = if (search.isBlank()) list
    else list.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) }

    Scaffold(
        containerColor = BrandLight,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandLight, titleContentColor = TextDark,
                    actionIconContentColor = TextDark
                ),
                title = {
                    if (showSearch) {
                        TextField(value = search, onValueChange = { search = it },
                            placeholder = { Text("Search…", color = TextMetaLt) },
                            singleLine = true, colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = TextDark, cursorColor = Brand,
                                focusedIndicatorColor = Brand, unfocusedIndicatorColor = Color.Transparent
                            ), modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Messages", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch; if (!showSearch) search = "" }) {
                        Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                    if (!showSearch) {
                        IconButton(onClick = { /* profile menu */ }) {
                            Icon(Icons.Default.AccountCircle, "Profile", tint = TextMeta)
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { /* new conversation */ },
                containerColor = Brand, contentColor = Color.White,
                shape = RoundedCornerShape(16.dp),
                icon = { Icon(Icons.Filled.Chat, null) },
                text = { Text("Start chat", fontWeight = FontWeight.SemiBold) }
            )
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
            LazyColumn(
                Modifier.padding(pad).clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.White)
            ) {
                items(filtered, key = { it.threadId }) { conv ->
                    ConversationRow(conv) { onOpen(conv) }
                }
            }
        }
    }
}

@Composable
fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(avatarColor(c.address)),
            contentAlignment = Alignment.Center
        ) {
            Text((c.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(c.displayName,
                    fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 16.sp, color = TextDark,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortTime(c.date), fontSize = 12.sp,
                    color = if (c.unread) Brand else TextMetaLt)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.snippet, fontSize = 14.sp,
                    color = if (c.unread) TextDark else TextMetaLt,
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

// ── Thread / chat screen (Google Messages style) ─────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(conversation: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msgs by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun reload() {
        msgs = SmsRepository.loadMessages(ctx, conversation.threadId)
        scope.launch { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1) }
    }

    LaunchedEffect(Unit) { reload(); SmsRepository.markThreadRead(ctx, conversation.threadId) }
    LaunchedEffect(Unit) { while (true) { delay(3000); reload() } }

    fun doSend() {
        val body = draft.trim()
        if (body.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val ok = SmsRepository.sendSms(ctx, conversation.address, body)
            withContext(Dispatchers.Main) {
                if (ok) { draft = ""; keyboard?.hide(); reload() }
                else android.widget.Toast.makeText(ctx, "Failed to send", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = BrandLight,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandLight, titleContentColor = TextDark,
                    navigationIconContentColor = TextDark, actionIconContentColor = TextDark
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(avatarColor(conversation.address)),
                            contentAlignment = Alignment.Center) {
                            Text((conversation.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(conversation.address, fontSize = 12.sp, color = TextMetaLt)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}")))
                    }) { Icon(Icons.Default.Phone, "Call") }
                    IconButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tel:${conversation.address}")))
                    }) { Icon(Icons.Default.Videocam, "Video") }
                    IconButton(onClick = { }) { Icon(Icons.Default.MoreVert, "More") }
                }
            )
        },
        bottomBar = {
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    scope.launch(Dispatchers.IO) {
                        val ok = SmsRepository.sendMms(ctx, conversation.address, it)
                        withContext(Dispatchers.Main) { if (ok) reload() }
                    }
                }
            }
            Surface(color = Color.White) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)
                        .navigationBarsPadding().imePadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // + button
                    IconButton(onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.size(40.dp)
                            .clip(CircleShape).background(Brand)
                    ) { Icon(Icons.Default.Add, "Attach", tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(6.dp))
                    // Input field
                    OutlinedTextField(
                        value = draft, onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message", color = TextMetaLt) },
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = BrandLight2, unfocusedContainerColor = BrandLight2,
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent
                        ),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {}) { Icon(Icons.Default.EmojiEmotions, "Emoji", tint = TextMeta) }
                                IconButton(onClick = { imagePicker.launch("image/*") }) {
                                    Icon(Icons.Default.Image, "Gallery", tint = TextMeta)
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        maxLines = 5
                    )
                    Spacer(Modifier.width(6.dp))
                    // Send / Mic button
                    val hasDraft = draft.isNotBlank()
                    FloatingActionButton(
                        onClick = { if (hasDraft) doSend() },
                        modifier = Modifier.size(46.dp),
                        containerColor = if (hasDraft) Brand else BrandLight,
                        contentColor = if (hasDraft) Color.White else Brand,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp)
                    ) {
                        Icon(
                            if (hasDraft) Icons.Default.Send else Icons.Default.Mic,
                            "Send", modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
        ) {
            val grouped = msgs.groupByDate()
            grouped.forEach { (dayLabel, dayMsgs) ->
                item(key = "day_$dayLabel") { DayHeader(dayLabel) }
                items(dayMsgs, key = { it.id }) { m -> MessageBubble(m) }
            }
        }
    }
}

// ── Day header ───────────────────────────────────────────────────────────────
fun List<Message>.groupByDate(): List<Pair<String, List<Message>>> {
    if (isEmpty()) return emptyList()
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val today = fmt.format(Date()); val yesterday = fmt.format(Date(System.currentTimeMillis() - 86_400_000))
    val groups = LinkedHashMap<String, MutableList<Message>>()
    forEach { m ->
        val label = when (val d = fmt.format(Date(m.date))) {
            today -> "Today"; yesterday -> "Yesterday"; else -> d
        }
        groups.getOrPut(label) { mutableListOf() }.add(m)
    }
    return groups.map { it.key to it.value }
}

@Composable
fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(BrandLight2)
            .padding(horizontal = 14.dp, vertical = 5.dp)) {
            Text(label, fontSize = 12.sp, color = TextMeta, fontWeight = FontWeight.Medium)
        }
    }
}

// ── Message bubble (Google Messages dark blue out, light gray in) ─────────────
@Composable
fun MessageBubble(m: Message) {
    val isIn = m.incoming
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (isIn) 4.dp else 20.dp,
        bottomEnd = if (isIn) 20.dp else 4.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
    ) {
        if (!isIn) Spacer(Modifier.width(56.dp))
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .background(if (isIn) BubbleIn else BubbleOut)
        ) {
            Column {
                if (m.imageUri != null) {
                    AsyncImage(
                        model = m.imageUri, contentDescription = "MMS image",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
                if (m.body.isNotBlank()) {
                    Text(m.body,
                        color = if (isIn) TextDark else Color.White,
                        fontSize = 15.sp, lineHeight = 20.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp,
                            top = if (m.imageUri != null) 6.dp else 10.dp, bottom = 2.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).fillMaxWidth()
                ) {
                    Text(msgTime(m.date), fontSize = 11.sp,
                        color = if (isIn) TextMetaLt else Color.White.copy(alpha = .7f))
                    if (!isIn) {
                        Spacer(Modifier.width(4.dp))
                        Text("• SMS", fontSize = 11.sp,
                            color = Color.White.copy(alpha = .7f))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.DoneAll, null,
                            tint = Color.White.copy(alpha = .85f),
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        if (isIn) Spacer(Modifier.width(56.dp))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private val palette = listOf(
    Color(0xFF1F6F5C), Color(0xFF2E5E8C), Color(0xFF8C4A2E), Color(0xFF6A2E8C),
    Color(0xFF8C2E5E), Color(0xFF2E8C7A), Color(0xFF8C7A2E), Color(0xFF1A237E)
)
fun avatarColor(seed: String): Color = palette[Math.abs(seed.hashCode()) % palette.size]

fun msgTime(millis: Long): String {
    if (millis <= 0) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
}

fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return if (now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) &&
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR))
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
}
