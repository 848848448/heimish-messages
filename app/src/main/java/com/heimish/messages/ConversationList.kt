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
    val searched0 = if (cat == "All") visible else if (cat == "Unread") visible.filter { it.unread } else if (cat == "Unknown") visible.filter { it.displayName == it.address } else if (cat in listOf("Personal", "Transactions", "Promos")) visible.filter { chatCategory(it) == cat } else visible.filter { LabelStore.get(ctx, it.threadId) == cat }
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

    Scaffold(containerColor = Chrome,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, actionIconContentColor = TextSecond),
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
        Column(Modifier.fillMaxSize().padding(pad).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Bg).padding(top = 8.dp)) {
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("All", "Unread", "Personal", "Transactions", "Promos", "Unknown") + LabelStore.allLabels(ctx)).forEach { c ->
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
            if (latestKnown > BuildConfig.BUILD_NUMBER) Text("Message ${BuildConfig.VERSION_NAME} · build ${BuildConfig.BUILD_NUMBER} · " + (if (latestKnown > BuildConfig.BUILD_NUMBER) "update available" else if (latestKnown > 0) "up to date" else "checking for updates…"),
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
    // Swipe right has its own action, like Google Messages (default: mark read / unread)
    val rightAction = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("swipe_right", "read") ?: "read" }
    val rightColor = when (rightAction) { "delete" -> Red; "archive" -> Color(0xFF5F6368); else -> Brand }
    val rightLabel = when (rightAction) { "delete" -> "Delete"; "archive" -> "Archive"; "none" -> ""; else -> if (c.unread) "Read" else "Unread" }

    Box(
        Modifier.fillMaxWidth().background(
            if (offsetX < -20f && swipeAction != "none") actionColor else if (offsetX > 20f && rightAction != "none") rightColor else Color.Transparent
        )
    ) {
        if (offsetX > 20f && rightAction != "none") {
            Box(Modifier.fillMaxSize().padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(when (rightAction) { "delete" -> Icons.Default.Delete; "archive" -> Icons.Default.Archive; else -> if (c.unread) Icons.Default.DoneAll else Icons.Default.MarkChatUnread }, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(rightLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
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
                .offset { IntOffset(offsetX.roundToInt(), 0) }
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
                            } else if (offsetX > threshold && rightAction != "none") {
                                when (rightAction) {
                                    "delete" -> onAction("delete", c)
                                    "archive" -> onAction("archive", c)
                                    else -> { val wasUnread = c.unread; scope.launch(Dispatchers.IO) { if (wasUnread) SmsRepository.markThreadRead(ctx, c.threadId) else SmsRepository.markThreadUnread(ctx, c.threadId); withContext(Dispatchers.Main) { onRefresh() } } }
                                }
                            }
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dx -> offsetX = (offsetX + dx).coerceIn(if (swipeAction == "none") 0f else -200f, if (rightAction == "none") 0f else 200f) }
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
                        (if (DraftStore.get(ctx, c.threadId).isNotBlank()) "Draft: " + DraftStore.get(ctx, c.threadId) else (LabelStore.get(ctx, c.threadId)?.let { "\uD83C\uDFF7 $it · " } ?: "") + (if (c.outgoing) "You: " else "") + c.snippet.ifBlank { " " }),
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
                    TextButton(onClick = {
                        val et = android.widget.EditText(ctx); et.setText(sm.body)
                        android.app.AlertDialog.Builder(ctx).setTitle("Edit scheduled message").setView(et).setPositiveButton("Save") { _, _ ->
                            val nb = et.text.toString()
                            if (nb.isNotBlank()) { ScheduledSendReceiver.cancel(ctx, sm.id); ScheduledSendReceiver.schedule(ctx, sm.address, nb, sm.sendAt); tick++ }
                        }.setNegativeButton("Cancel", null).show()
                    }) { Text("Edit", color = Brand) }
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

    val scopeNc = rememberCoroutineScope()
    fun openConv(name: String, number: String) {
        scopeNc.launch {
            // A real thread id (group or not) — the group button used to make one up from hashCode(),
            // so a new group chat never showed the messages you sent.
            val threadId = withContext(Dispatchers.IO) { SmsRepository.getOrCreateThreadId(ctx, number) }
            onOpen(Conversation(threadId, number, name, "", System.currentTimeMillis(), false))
        }
    }
    val typedNumber = to.trim().takeIf { t -> t.count { it.isDigit() } >= 3 && t.all { it.isDigit() || it in "+-() ." } }?.replace(Regex("[\\s()\\-.]"), "")

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
                keyboardActions = KeyboardActions(onDone = {
                    if (to.isNotBlank() && !isGroup) openConv(contacts.find { it.second == to.trim() }?.first ?: to.trim(), typedNumber ?: to.trim())
                    else if (isGroup && typedNumber != null) { if (selected.none { it.second == typedNumber }) selected = selected + (typedNumber to typedNumber); to = "" }
                }))
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
                            openConv(names, addr)
                        }, colors = ButtonDefaults.buttonColors(Brand, contentColor = OnBrand), shape = RoundedCornerShape(20.dp)) { Text("Start group", fontSize = 13.sp) }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (typedNumber != null && filtered.none { it.second == typedNumber }) item(key = "typed") {
                    Row(Modifier.fillMaxWidth().clickable {
                        if (isGroup) { if (selected.none { it.second == typedNumber }) selected = selected + (typedNumber to typedNumber); to = "" }
                        else openConv(typedNumber, typedNumber)
                    }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(typedNumber, typedNumber, 44.dp, 17.sp)
                        Spacer(Modifier.width(14.dp))
                        Text((if (isGroup) "Add " else "Send to ") + typedNumber, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Brand)
                    }
                }
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

