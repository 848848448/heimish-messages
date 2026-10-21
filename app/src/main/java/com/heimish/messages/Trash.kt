package com.heimish.messages

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deleted conversations wait here 30 days (like Google Messages) before they are really deleted. */
object TrashStore {
    private const val KEY = "trash_threads"
    const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private fun p(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun all(ctx: Context): Map<Long, Long> = (p(ctx).getStringSet(KEY, emptySet()) ?: emptySet()).mapNotNull { s ->
        val parts = s.split(":"); val t = parts.getOrNull(0)?.toLongOrNull(); val at = parts.getOrNull(1)?.toLongOrNull()
        if (t != null && at != null) t to at else null
    }.toMap()
    fun has(ctx: Context, t: Long) = all(ctx).containsKey(t)
    private fun save(ctx: Context, m: Map<Long, Long>) = p(ctx).edit().putStringSet(KEY, m.map { "${it.key}:${it.value}" }.toSet()).apply()
    fun add(ctx: Context, t: Long) = save(ctx, all(ctx) + (t to System.currentTimeMillis()))
    fun remove(ctx: Context, t: Long) = save(ctx, all(ctx) - t)
    fun clear(ctx: Context) = save(ctx, emptyMap())
    /** Really delete chats older than 30 days; a chat that got a new message after it was trashed comes back. */
    fun maintain(ctx: Context, convs: List<Conversation>) {
        val now = System.currentTimeMillis()
        val m = all(ctx).toMutableMap()
        var changed = false
        convs.forEach { c -> m[c.threadId]?.let { at -> if (c.date > at + 5_000) { m.remove(c.threadId); changed = true } } }
        m.filter { now - it.value > KEEP_MS }.keys.forEach { t -> runCatching { SmsRepository.deleteThread(ctx, t) }; m.remove(t); changed = true }
        if (changed) save(ctx, m)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val trash = remember(tick) { TrashStore.all(ctx) }
    val list by produceState(emptyList<Conversation>(), tick) { value = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }.filter { it.threadId in trash } }
    var pick by remember { mutableStateOf<Conversation?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = Chrome, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary, actionIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } },
            title = { Text("Trash", fontSize = 22.sp) },
            actions = {
                if (list.isNotEmpty()) {
                    IconButton({ list.forEach { TrashStore.remove(ctx, it.threadId) }; tick++ }) { Icon(Ms.RestoreFromTrash, "Restore all") }
                    IconButton({ confirmAll = true }) { Icon(Ms.DeleteForever, "Delete all") }
                }
            })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Bg)) {
            Row(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Surf).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ms.Info, null, tint = TextSecond, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                Text("Conversations stay in the trash for 30 days before they're deleted forever", color = TextSecond, fontSize = 14.sp)
            }
            if (list.isEmpty()) EmptyState(Ms.Delete, "Nothing in the trash", "Deleted conversations wait here for 30 days") else LazyColumn {
                items(list, key = { it.threadId }) { c ->
                    val left = (((trash[c.threadId] ?: 0L) + TrashStore.KEEP_MS - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                    Row(Modifier.animateItem().fillMaxWidth().clickable { pick = c }.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.displayName, c.address, 54.dp, 20.sp); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row { Text(c.displayName, color = TextPrimary, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(shortTime(c.date), color = TextSecond, fontSize = 13.sp) }
                            Text(c.snippet, color = TextSecond, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (left == 1L) "1 day left" else "$left days left", color = TextHint, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    pick?.let { c ->
        AlertDialog(onDismissRequest = { pick = null }, containerColor = Surf, title = { Text(c.displayName) },
            text = { Text("Restore this conversation, or delete it forever?") },
            confirmButton = { TextButton({ TrashStore.remove(ctx, c.threadId); pick = null; tick++ }) { Text("Restore", color = Brand) } },
            dismissButton = { TextButton({ scope.launch { withContext(Dispatchers.IO) { SmsRepository.deleteThread(ctx, c.threadId) }; TrashStore.remove(ctx, c.threadId); tick++ }; pick = null }) { Text("Delete forever", color = Red) } })
    }
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false }, containerColor = Surf, title = { Text("Delete all forever?") },
        text = { Text("${list.size} conversations will be deleted. This can't be undone.") },
        confirmButton = { TextButton({ val ids = list.map { it.threadId }; scope.launch { withContext(Dispatchers.IO) { ids.forEach { SmsRepository.deleteThread(ctx, it) } }; ids.forEach { TrashStore.remove(ctx, it) }; tick++ }; confirmAll = false }) { Text("Delete", color = Red) } },
        dismissButton = { TextButton({ confirmAll = false }) { Text("Cancel", color = Brand) } })
}

class MenuEntry(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Google Messages account menu (tap the circle at the top right): big rounded groups of rows. */
@Composable
fun AccountMenu(groups: List<List<MenuEntry>>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.94f).clip(RoundedCornerShape(28.dp)).background(Chrome).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onDismiss) { Icon(Ms.Close, "Close", tint = TextPrimary) }
                Text("Messages", color = TextPrimary, fontSize = 20.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.width(48.dp))
            }
            Spacer(Modifier.height(8.dp))
            groups.forEach { g ->
                Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(24.dp)).background(Surf)) {
                    g.forEach { e ->
                        Row(Modifier.fillMaxWidth().clickable { e.onClick() }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(e.icon, null, tint = TextPrimary, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(18.dp))
                            Text(e.label, color = TextPrimary, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
