package com.heimish.messages

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { recreate() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkThemeCompat()) darkColorScheme() else lightColorScheme()) {
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
            val intent = android.content.Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            roleLauncher.launch(intent)
        }
    }
}

@Composable
private fun isSystemInDarkThemeCompat(): Boolean =
    androidx.compose.foundation.isSystemInDarkTheme()

fun isDefaultSmsApp(ctx: Context): Boolean =
    Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

@Composable
fun AppRoot(isDefault: Boolean, onRequestDefault: () -> Unit) {
    val ctx = LocalContext.current
    var openThread by remember { mutableStateOf<Conversation?>(null) }
    var hasPerms by remember { mutableStateOf(Permissions.granted(ctx)) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPerms = Permissions.granted(ctx) }

    LaunchedEffect(Unit) {
        if (!hasPerms) permLauncher.launch(Permissions.ALL)
    }

    if (!isDefault) {
        SetupScreen(onRequestDefault)
        return
    }
    if (!hasPerms) {
        SetupPermissions { permLauncher.launch(Permissions.ALL) }
        return
    }

    val thread = openThread
    if (thread == null) {
        ConversationListScreen(onOpen = { openThread = it })
    } else {
        ThreadScreen(conversation = thread, onBack = { openThread = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onRequestDefault: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Heimish Messages", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "To send and receive your texts, set this as your default messaging app.",
            fontSize = 15.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequestDefault) { Text("Set as default") }
    }
}

@Composable
fun SetupPermissions(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Almost there", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Allow SMS and Contacts so your messages can load.", fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(onOpen: (Conversation) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(emptyList<Conversation>()) }
    LaunchedEffect(Unit) { list = SmsRepository.loadConversations(ctx) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Messages", fontWeight = FontWeight.Bold) })
    }) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("No conversations yet", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(Modifier.padding(pad)) {
                items(list) { conv ->
                    ConversationRow(conv) { onOpen(conv) }
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ConversationRow(c: Conversation, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(avatarColor(c.address)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                (c.displayName.firstOrNull()?.uppercaseChar() ?: '#').toString(),
                color = Color.White, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.displayName,
                fontWeight = if (c.unread) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                c.snippet,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (c.unread) FontWeight.Medium else FontWeight.Normal
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(shortTime(c.date), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(conversation: Conversation, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var msgs by remember { mutableStateOf(emptyList<Message>()) }
    var draft by remember { mutableStateOf("") }

    fun reload() { msgs = SmsRepository.loadMessages(ctx, conversation.threadId) }
    LaunchedEffect(Unit) {
        reload()
        SmsRepository.markThreadRead(ctx, conversation.threadId)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(conversation.displayName, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            }
        )
    }, bottomBar = {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Text message") },
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    val body = draft.trim()
                    if (body.isNotEmpty()) {
                        SmsRepository.sendSms(ctx, conversation.address, body)
                        draft = ""
                        reload()
                    }
                }
            ) { Icon(Icons.Default.Send, "Send") }
        }
    }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(msgs) { m -> MessageBubble(m) }
        }
    }
}

@Composable
fun MessageBubble(m: Message) {
    val align = if (m.incoming) Alignment.Start else Alignment.End
    val bg = if (m.incoming) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary
    val fg = if (m.incoming) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary
    Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            Text(m.body, color = fg, fontSize = 15.sp)
        }
        Text(shortTime(m.date), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

private val palette = listOf(
    Color(0xFF1F6F5C), Color(0xFF2E5E8C), Color(0xFF8C4A2E), Color(0xFF6A2E8C),
    Color(0xFF8C2E5E), Color(0xFF2E8C7A), Color(0xFF8C7A2E)
)
fun avatarColor(seed: String): Color =
    palette[(Math.abs(seed.hashCode())) % palette.size]

fun shortTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) SimpleDateFormat("h:mm a", Locale.getDefault())
    else SimpleDateFormat("MMM d", Locale.getDefault())
    return fmt.format(Date(millis))
}
