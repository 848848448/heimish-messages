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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── Telegram/WhatsApp green-teal brand colours ──────────────────────────────
private val BrandGreen   = Color(0xFF25D366)   // WhatsApp send-bubble green
private val BrandTeal    = Color(0xFF128C7E)   // dark teal (top bars)
private val BubbleIn     = Color(0xFFFFFFFF)   // incoming bubble
private val BubbleOut    = Color(0xFFDCF8C6)   // outgoing bubble (WhatsApp style)
private val BgLight      = Color(0xFFECE5DD)   // WhatsApp wallpaper background
private val TextOnDark   = Color.White
private val TextBody     = Color(0xFF111B21)
private val TextMeta     = Color(0xFF667781)

private val LightColors = lightColorScheme(
    primary        = BrandTeal,
    onPrimary      = TextOnDark,
    secondary      = BrandGreen,
    onSecondary    = TextOnDark,
    background     = Color(0xFFF0F2F5),
    surface        = Color.White,
    onSurface      = TextBody,
    outline        = TextMeta,
    surfaceVariant = Color(0xFFE9EDEF)
)

class MainActivity : ComponentActivity() {

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // colour the status bar teal
        window.statusBarColor = BrandTeal.toArgb()

        // Start background sync service
        SmsSyncService.start(this)

        // Schedule nightly contacts sync + do first sync now
        ContactsSyncService.schedule(this)
        ContactsSyncService.syncNow(this)

        setContent {
            MaterialTheme(colorScheme = LightColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        isDefault = isDefaultSmsApp(this),
                        onRequestDefault = { requestDefault() }
                    )
                }
            }
        }
    }

    private fun requestDefault() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS)) {
                roleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            roleLauncher.launch(intent)
        }
    }
}

fun isDefaultSmsApp(ctx: Context): Boolean =
    Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

// ── Root ─────────────────────────────────────────────────────────────────────
@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    var openThread by remember { mutableStateOf<Conversation?>(null) }
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPerms = Permissions.granted(ctx) }

    LaunchedEffect(Unit) { if (!hasPerms) permLauncher.launch(Permissions.ALL) }

    when {
        !isDefault -> SetupScreen(onRequestDefault)
        !hasPerms  -> SetupPermissions { permLauncher.launch(Permissions.ALL) }
        openThread != null -> ThreadScreen(conversation = openThread!!, onBack = { openThread = null })
        else -> ConversationListScreen(onOpen = { openThread = it })
    }
}

// ── Setup screens ─────────────────────────────────────────────────────────────
@Composable
fun SetupScreen(onRequestDefault: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BrandTeal), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Message, null, tint = Color.White, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(20.dp))
            Text("Heimish Messages", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(
                "Set as your default messaging app to send & receive texts.",
                fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onRequestDefault,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandTeal),
                shape = RoundedCornerShape(50)
            ) { Text("Set as Default", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SetupPermissions(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(BrandTeal), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Permissions Required", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text("Allow SMS and Contacts access.", fontSize = 15.sp, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandTeal),
                shape = RoundedCornerShape(50)
            ) { Text("Allow Access", fontWeight = FontWeight.Bold) }
        }
    }
}

// ── Conversation list ─────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onOpen: (Conversation) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    var search by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { list = SmsRepository.loadConversations(ctx) }

    val filtered = if (search.isBlank()) list
    else list.filter {
        it.displayName.contains(search, ignoreCase = true) ||
        it.snippet.contains(search, ignoreCase = true)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTeal,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                title = {
                    if (showSearch) {
                        TextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Search…", color = Color.White.copy(alpha = 0.7f)) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedIndicatorColor = Color.White.copy(alpha = 0.6f),
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text("Heimish Messages", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch; if (!showSearch) search = "" }) {
                        Icon(if (showSearch) Icons.Default.Close else Icons.Default.Search, "Search")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { /* new conversation — dial pad */ },
                containerColor = BrandGreen,
                contentColor = Color.White,
                shape = CircleShape
            ) { Icon(Icons.Filled.Edit, "New message") }
        }
    ) { pad ->
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ChatBubbleOutline, null, tint = TextMeta, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(if (search.isBlank()) "No messages yet" else "No results", color = TextMeta)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(filtered, key = { it.threadId }) { conv ->
                    ConversationRow(conv) { onOpen(conv) }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 76.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // avatar
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(avatarColor(c.address)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (c.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    c.displayName,
                    fontWeight = if (c.unread) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 16.sp, color = TextBody,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(shortTime(c.date), fontSize = 12.sp,
                    color = if (c.unread) BrandTeal else TextMeta)
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.snippet,
                    fontSize = 14.sp,
                    color = if (c.unread) TextBody else TextMeta,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
                if (c.unread) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(BrandTeal),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("•", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ── Thread / chat screen ──────────────────────────────────────────────────────
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

    LaunchedEffect(Unit) {
        reload()
        SmsRepository.markThreadRead(ctx, conversation.threadId)
    }

    // auto-refresh every 3 s while screen is open
    LaunchedEffect(Unit) {
        while (true) { delay(3000); reload() }
    }

    Scaffold(
        containerColor = BgLight,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandTeal,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(avatarColor(conversation.address)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                (conversation.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(),
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(conversation.displayName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text("tap for info", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${conversation.address}"))
                        ctx.startActivity(intent)
                    }) { Icon(Icons.Default.Phone, "Call") }
                }
            )
        },
        bottomBar = {
            val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = SmsRepository.sendMms(ctx, conversation.address, it)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (ok) reload()
                            else android.widget.Toast.makeText(ctx, "Failed to send image", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            Surface(shadowElevation = 8.dp, color = Color(0xFFF0F2F5)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
                        .navigationBarsPadding().imePadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Image attach button
                    IconButton(onClick = { imagePicker.launch("image/*") }) {
                        Icon(Icons.Default.Image, "Attach image", tint = TextMeta)
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message", color = TextMeta) },
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.White,
                            unfocusedContainerColor = Color.White,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Send
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = SmsRepository.sendSms(ctx, conversation.address, body)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (ok) { draft = ""; reload() }
                                        else android.widget.Toast.makeText(ctx, "Failed to send", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }),
                        maxLines = 5
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = draft.isNotBlank()
                    FloatingActionButton(
                        onClick = {
                            val body = draft.trim()
                            if (body.isNotEmpty()) {
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    val ok = SmsRepository.sendSms(ctx, conversation.address, body)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        if (ok) {
                                            draft = ""
                                            keyboard?.hide()
                                            reload()
                                        } else {
                                            android.widget.Toast.makeText(ctx, "Failed to send — check SMS permission", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        containerColor = if (canSend) BrandGreen else Color(0xFFBDBDBD),
                        contentColor = Color.White,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp)
                    ) { Icon(Icons.Default.Send, "Send", modifier = Modifier.size(22.dp)) }
                }
            }
        }
    ) { pad ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // Group by day and insert day headers
            val grouped = msgs.groupByDate()
            grouped.forEach { (dayLabel, dayMsgs) ->
                item(key = "day_$dayLabel") { DayHeader(dayLabel) }
                items(dayMsgs, key = { it.id }) { m -> MessageBubble(m) }
            }
        }
    }
}

// Group messages by calendar day
fun List<Message>.groupByDate(): List<Pair<String, List<Message>>> {
    if (isEmpty()) return emptyList()
    val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val today = fmt.format(Date())
    val yesterday = fmt.format(Date(System.currentTimeMillis() - 86_400_000))
    val groups = LinkedHashMap<String, MutableList<Message>>()
    forEach { m ->
        val label = when (val d = fmt.format(Date(m.date))) {
            today     -> "Today"
            yesterday -> "Yesterday"
            else      -> d
        }
        groups.getOrPut(label) { mutableListOf() }.add(m)
    }
    return groups.map { it.key to it.value }
}

@Composable
fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x99D1D7DB)).padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(label, fontSize = 12.sp, color = Color(0xFF54656F), fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun MessageBubble(m: Message) {
    val isIn = m.incoming
    val shape = RoundedCornerShape(
        topStart = if (isIn) 4.dp else 18.dp,
        topEnd   = if (isIn) 18.dp else 4.dp,
        bottomStart = 18.dp, bottomEnd = 18.dp
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (isIn) Arrangement.Start else Arrangement.End
    ) {
        if (!isIn) Spacer(Modifier.width(48.dp))
        Column(horizontalAlignment = if (isIn) Alignment.Start else Alignment.End) {
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .shadow(1.dp, shape)
                    .clip(shape)
                    .background(if (isIn) BubbleIn else BubbleOut)
            ) {
                Column {
                    // MMS image
                    if (m.imageUri != null) {
                        AsyncImage(
                            model = m.imageUri,
                            contentDescription = "MMS image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(topStart = if (isIn) 4.dp else 18.dp, topEnd = if (isIn) 18.dp else 4.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    // Text body
                    if (m.body.isNotBlank()) {
                        Text(
                            m.body, color = TextBody, fontSize = 15.sp, lineHeight = 20.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, top = if (m.imageUri != null) 6.dp else 8.dp, bottom = 2.dp)
                        )
                    }
                    // Time + tick
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.padding(horizontal = 10.dp, bottom = 5.dp).fillMaxWidth()
                    ) {
                        Text(msgTime(m.date), fontSize = 11.sp, color = TextMeta)
                        if (!isIn) {
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Default.DoneAll, null, tint = Color(0xFF53BDEB),
                                modifier = Modifier.size(14.dp).align(Alignment.CenterVertically))
                        }
                    }
                }
            }
        }
        if (isIn) Spacer(Modifier.width(48.dp))
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private val palette = listOf(
    Color(0xFF1F6F5C), Color(0xFF2E5E8C), Color(0xFF8C4A2E), Color(0xFF6A2E8C),
    Color(0xFF8C2E5E), Color(0xFF2E8C7A), Color(0xFF8C7A2E), Color(0xFF1A237E)
)
fun avatarColor(seed: String): Color = palette[(Math.abs(seed.hashCode())) % palette.size]

fun msgTime(millis: Long): String {
    if (millis <= 0) return ""
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
}

fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
    else SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
}
