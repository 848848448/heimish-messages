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
fun ConvListScreen(onOpen: (Conversation) -> Unit, onNew: () -> Unit, onSettings: () -> Unit, onAdmin: () -> Unit, onArchived: () -> Unit, onStarred: () -> Unit, onScheduled: () -> Unit, onSpam: () -> Unit = {}, onWhatsNew: (UpdateInfo) -> Unit = {}, onTrash: () -> Unit = {}, onSearch: () -> Unit = {}) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(ConvCache.list ?: emptyList()) }
    var loaded by remember { mutableStateOf(ConvCache.list != null) }
    var showSearchBar by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var ctxConv by remember { mutableStateOf<Conversation?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // "Start chat" shrinks to a round button while scrolling down and grows back when scrolling up (Google Messages)
    var fabExtended by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevI = listState.firstVisibleItemIndex; var prevO = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (i, o) ->
            when {
                i == 0 && o < 8 -> fabExtended = true
                i > prevI || (i == prevI && o > prevO + 6) -> fabExtended = false
                i < prevI || (i == prevI && o < prevO - 6) -> fabExtended = true
            }
            prevI = i; prevO = o
        }
    }

    fun refresh() { scope.launch { list = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx) }; ConvCache.list = list; loaded = true } }
    val providerTick = rememberProviderTick()
    LaunchedEffect(providerTick) {
        if (providerTick > 1) delay(150)
        val fresh = withContext(Dispatchers.IO) { SmsRepository.loadConversations(ctx).also { TrashStore.maintain(ctx, it) } }
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
                TrashStore.add(ctx, c.threadId); tick++
                val r = snackbar.showSnackbar("Moved to trash", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) TrashStore.remove(ctx, c.threadId)
                hidden = hidden - c.threadId; tick++
            } else {
                ArchiveStore.setArchived(ctx, c.threadId, true); tick++
                val r = snackbar.showSnackbar("Conversation archived", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) { ArchiveStore.setArchived(ctx, c.threadId, false); tick++ }
            }
        }
    }
    /** Several chats at once (selection bar): ONE "N conversations…" bar whose Undo brings all of them back
     *  (it used to queue one bar per chat, ~4 s each, and Undo only restored the one on screen). */
    fun actMany(kind: String, cs: List<Conversation>) {
        if (cs.isEmpty()) return
        if (cs.size == 1) { act(kind, cs[0]); return }
        val ids = cs.map { it.threadId }
        scope.launch {
            if (kind == "delete") {
                hidden = hidden + ids
                TrashStore.addAll(ctx, ids); tick++
                val r = snackbar.showSnackbar("${ids.size} conversations moved to trash", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) TrashStore.removeAll(ctx, ids)
                hidden = hidden - ids.toSet(); tick++
            } else {
                ids.forEach { ArchiveStore.setArchived(ctx, it, true) }; tick++
                val r = snackbar.showSnackbar("${ids.size} conversations archived", "Undo", duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) { ids.forEach { ArchiveStore.setArchived(ctx, it, false) }; tick++ }
            }
        }
    }
    val spamIds = remember(list, tick) { SpamStore.all(ctx) }
    val hits by produceState(emptyList<MsgHit>(), search) {
        value = if (search.trim().length >= 2) { delay(250); withContext(Dispatchers.IO) { SmsRepository.searchMessages(ctx, search.trim()) } } else emptyList()
    }
    val visible = remember(list, tick, hidden, spamIds) {
        val trashed = TrashStore.all(ctx).keys // read once, not once per chat
        list.filter { it.threadId !in trashed && it.threadId !in hidden && it.threadId !in spamIds && !ArchiveStore.isArchived(ctx, it.threadId) && !BlockedStore.isBlocked(ctx, it.address) }
    }
    var cat by remember { mutableStateOf("All") }
    var sel by remember { mutableStateOf(setOf<Long>()) }
    BackHandler(enabled = sel.isNotEmpty()) { sel = emptySet() }
    val searched0 = if (cat == "All") visible else if (cat == "Unread") visible.filter { it.unread } else if (cat == "Unknown") visible.filter { it.displayName == it.address } else if (cat in listOf("Personal", "Transactions", "Promos")) visible.filter { chatCategory(it) == cat } else visible.filter { LabelStore.get(ctx, it.threadId) == cat }
    val searched = if (search.isBlank()) searched0 else searched0.filter { it.displayName.contains(search, true) || it.snippet.contains(search, true) || it.address.contains(search, true) }
    val filtered = remember(searched, tick) { val pins = searched.filter { PinStore.isPinned(ctx, it.threadId) }.map { it.threadId }.toSet(); searched.sortedWith(compareByDescending<Conversation> { if (it.threadId in pins) 1 else 0 }.thenByDescending { it.date }) }

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
                    MenuBtn(Ms.PushPin, if (pinned) "Unpin" else "Pin to top") {
                        PinStore.toggle(ctx, c.threadId); tick++; ctxConv = null
                    }
                    MenuBtn(Ms.CheckCircleFill, "Select") { sel = setOf(c.threadId); ctxConv = null }
                    MenuBtn(Ms.Archive, "Archive") {
                        act("archive", c); ctxConv = null
                    }
                    MenuBtn(if (c.unread) Ms.Drafts else Ms.MarkEmailUnread,
                        if (c.unread) "Mark as read" else "Mark as unread") {
                        scope.launch { withContext(Dispatchers.IO) { if (c.unread) SmsRepository.markThreadRead(ctx, c.threadId) else SmsRepository.markThreadUnread(ctx, c.threadId) }; refresh() }
                        ctxConv = null
                    }
                    MenuBtn(Ms.Block, "Block ${c.displayName}", Red) {
                        BlockedStore.block(ctx, c.address)
                        Toast.makeText(ctx, "Blocked", Toast.LENGTH_SHORT).show(); tick++; ctxConv = null
                    }
                    MenuBtn(Ms.Delete, "Delete", Red) {
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
                title = { Text("Messages", fontWeight = FontWeight.Normal, fontSize = if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380) 22.sp else 24.sp, letterSpacing = 0.sp, modifier = Modifier.padding(start = 4.dp)) },
                actions = {
                    IconButton(onSearch) { Icon(Ms.Search, "Search", tint = TextPrimary) }
                    Box {
                        Box(Modifier.padding(end = 12.dp, start = 4.dp).size(34.dp).clip(CircleShape).background(Brand).clickable { showMenu = true }, contentAlignment = Alignment.Center) {
                            Icon(Ms.PersonFill, "Account menu", tint = OnBrand, modifier = Modifier.size(22.dp))
                        }
                        if (showMenu) AccountMenu(listOf(
                            listOf(MenuEntry(Ms.Archive, "Archived") { showMenu = false; onArchived() },
                                MenuEntry(Ms.Delete, "Trash") { showMenu = false; onTrash() },
                                MenuEntry(Ms.Block, "Spam & blocked") { showMenu = false; onSpam() }),
                            listOf(MenuEntry(Ms.Star, "Starred") { showMenu = false; onStarred() },
                                MenuEntry(Ms.Schedule, "Scheduled") { showMenu = false; onScheduled() },
                                MenuEntry(Ms.MarkChatRead, "Mark all as read") {
                                    showMenu = false
                                    scope.launch(Dispatchers.IO) { list.filter { it.unread }.forEach { SmsRepository.markThreadRead(ctx, it.threadId) } }
                                    refresh()
                                }),
                            // Admin is hidden until the version line in Settings is tapped 7 times (AdminAuth).
                            listOfNotNull(MenuEntry(Ms.Settings, "Messages settings") { showMenu = false; onSettings() },
                                if (AdminAuth.revealed) MenuEntry(Ms.AdminPanelSettings, "Admin panel") { showMenu = false; onAdmin() } else null)
                        )) { showMenu = false }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNew, containerColor = FabColor, contentColor = OnFabColor, shape = RoundedCornerShape(16.dp),
                expanded = fabExtended, modifier = Modifier.height(56.dp),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 3.dp, focusedElevation = 3.dp, hoveredElevation = 4.dp),
                icon = { Icon(Ms.Chat, "New chat", modifier = Modifier.size(24.dp)) }, text = { Text("Start chat", fontWeight = FontWeight.Medium, fontSize = 15.sp) })
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 0.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(ListBg).padding(top = 10.dp)) {
            // Search opens from the search icon in the top bar, like Google Messages
            if (showSearchBar || search.isNotEmpty()) TextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search conversations", color = TextHint, fontSize = 15.sp) },
                leadingIcon = { Icon(Ms.Search, null, tint = TextHint) },
                trailingIcon = { if (search.isNotEmpty()) IconButton({ search = "" }) { Icon(Ms.Close, null, tint = TextHint) } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = FieldBg, unfocusedContainerColor = FieldBg,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Brand, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
                )
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf("All", "Unread", "Personal", "Transactions", "Promos", "Unknown") + LabelStore.allLabels(ctx)).forEach { c ->
                    val on = cat == c
                    val chipBg by animateColorAsState(if (on) Tonal else Color.Transparent, label = "chipBg")
                    val chipBorder by animateColorAsState(if (on) Tonal else TextHint.copy(alpha = .45f), label = "chipBorder")
                    Row(Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(chipBg)
                        .border(1.dp, chipBorder, RoundedCornerShape(8.dp)).clickable { cat = c }.animateContentSize().padding(start = if (on) 8.dp else 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (on) { Icon(Ms.Check, null, tint = OnTonal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(c, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) OnTonal else TextSecond, maxLines = 1)
                    }
                }
            }
            if (sel.isNotEmpty()) Row(Modifier.fillMaxWidth().background(Tonal).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ sel = emptySet() }) { Icon(Ms.Close, "Cancel", tint = OnTonal) }
                Text("${sel.size} selected", Modifier.weight(1f), color = OnTonal, fontWeight = FontWeight.SemiBold)
                IconButton({ sel.forEach { PinStore.toggle(ctx, it) }; sel = emptySet(); tick++ }) { Icon(Ms.PushPin, "Pin", tint = OnTonal) }
                IconButton({ val ids = sel; scope.launch { withContext(Dispatchers.IO) { ids.forEach { SmsRepository.markThreadRead(ctx, it) } }; refresh() }; sel = emptySet() }) { Icon(Ms.DoneAll, "Mark read", tint = OnTonal) }
                IconButton({ val ids = sel; actMany("archive", list.filter { it.threadId in ids }); sel = emptySet() }) { Icon(Ms.Archive, "Archive", tint = OnTonal) }
                IconButton({ val ids = sel; actMany("delete", list.filter { it.threadId in ids }); sel = emptySet() }) { Icon(Ms.Delete, "Delete", tint = OnTonal) }
            }
            if (latestKnown > BuildConfig.BUILD_NUMBER) Text("Message ${BuildConfig.VERSION_NAME} · " + (if (latestKnown > BuildConfig.BUILD_NUMBER) "update available" else if (latestKnown > 0) "up to date" else "checking for updates…"),
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp), fontSize = 11.sp, color = if (latestKnown > BuildConfig.BUILD_NUMBER) Brand else TextHint)
            update?.let { u -> UpdateCard(u, { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).edit().putInt("upd_dismissed", u.build).apply(); update = null }, { onWhatsNew(u) }) }
            Spacer(Modifier.height(6.dp))
            if (filtered.isEmpty() && hits.isEmpty() && loaded) {
                if (search.isBlank()) EmptyState(if (cat == "All") Ms.Forum else Ms.FilterList,
                    if (cat == "All") "No conversations yet" else "Nothing in $cat",
                    if (cat == "All") null else "Chats that fit this filter show up here")
                else EmptyState(Ms.SearchOff, "No results for \"$search\"")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    items(filtered, key = { it.threadId }) { conv ->
                        fun toggleSel(x: Conversation) { sel = if (x.threadId in sel) sel - x.threadId else sel + x.threadId }
                        val selBg by animateColorAsState(if (conv.threadId in sel) Tonal else Color.Transparent, label = "selBg")
                        Box(Modifier.animateItem().background(selBg)) {
                        CompositionLocalProvider(LocalAvatarTap provides sel.isEmpty()) {
                            SwipeableConvRow(conv, PinStore.isPinned(ctx, conv.threadId), { if (sel.isNotEmpty()) toggleSel(it) else onOpen(it) }, onRefresh = { refresh(); tick++ }, onLongPress = { if (sel.isNotEmpty()) toggleSel(it) else ctxConv = it }, onAction = { k, cc -> act(k, cc) })
                        }
                        }
                    }
                    if (hits.isNotEmpty()) {
                        item { Text("Messages", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Brand, letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp)) }
                        items(hits, key = { "hit" + it.id }) { h ->
                            Row(Modifier.fillMaxWidth().clickable { onOpen(Conversation(h.threadId, h.address, h.name, h.body, h.date, false)) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Avatar(h.name, h.address, 40.dp, 16.sp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Row { Text(h.name, fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = TextPrimary, style = listTextStyle()); Text(shortTime(h.date), fontSize = 12.sp, color = TextHint) }
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
    // Fixed strong colors: white text on the light theme colors was unreadable in dark mode
    val actionColor = when (swipeAction) { "delete" -> Color(0xFFB3261E); "read" -> Color(0xFF0B57D0); else -> Color(0xFF5F6368) }
    // Swipe right has its own action, like Google Messages (default: mark read / unread)
    val rightAction = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getString("swipe_right", "read") ?: "read" }
    val rightColor = when (rightAction) { "delete" -> Color(0xFFB3261E); "archive" -> Color(0xFF5F6368); else -> Color(0xFF0B57D0) }
    val rightLabel = when (rightAction) { "delete" -> "Delete"; "archive" -> "Archive"; "none" -> ""; else -> if (c.unread) "Read" else "Unread" }

    Box(
        Modifier.fillMaxWidth().background(
            if (offsetX < -20f && swipeAction != "none") actionColor else if (offsetX > 20f && rightAction != "none") rightColor else Color.Transparent
        )
    ) {
        if (offsetX > 20f && rightAction != "none") {
            Box(Modifier.fillMaxSize().absolutePadding(left = 24.dp), contentAlignment = androidx.compose.ui.AbsoluteAlignment.CenterLeft) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(when (rightAction) { "delete" -> Ms.Delete; "archive" -> Ms.Archive; else -> if (c.unread) Ms.DoneAll else Ms.MarkChatUnread }, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(rightLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (offsetX < -20f && swipeAction != "none") {
            Box(Modifier.fillMaxSize().absolutePadding(right = 24.dp), contentAlignment = androidx.compose.ui.AbsoluteAlignment.CenterRight) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(when (swipeAction) { "delete" -> Ms.Delete; "read" -> Ms.DoneAll; else -> Ms.Archive }, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Text(actionLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .absoluteOffset { IntOffset(offsetX.roundToInt(), 0) }
                .background(ListBg)
                .combinedClickable(
                    onClick = { onOpen(c) },
                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress(c) }
                )
                .pointerInput(c.threadId, c.unread, swipeAction, rightAction) { // re-key so read/unread toggles the right way
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold && swipeAction != "none") {
                                when (swipeAction) {
                                    "delete" -> onAction("delete", c)
                                    "read" -> { scope.launch(Dispatchers.IO) { SmsRepository.markThreadRead(ctx, c.threadId); withContext(Dispatchers.Main) { onRefresh() } }; Toast.makeText(ctx, "Marked as read", Toast.LENGTH_SHORT).show() }
                                    else -> onAction("archive", c)
                                }
                            } else if (offsetX > threshold && rightAction != "none") {
                                when (rightAction) {
                                    "delete" -> onAction("delete", c)
                                    "archive" -> onAction("archive", c)
                                    else -> { val wasUnread = c.unread; scope.launch(Dispatchers.IO) { if (wasUnread) SmsRepository.markThreadRead(ctx, c.threadId) else SmsRepository.markThreadUnread(ctx, c.threadId); withContext(Dispatchers.Main) { onRefresh() } } }
                                }
                            }
                            // Spring back into place instead of jumping
                            val from = offsetX
                            scope.launch { runCatching { androidx.compose.animation.core.animate(from, 0f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 500f)) { v, _ -> offsetX = v } }.onFailure { offsetX = 0f } }
                        },
                        onHorizontalDrag = { _, dx -> offsetX = (offsetX + dx).coerceIn(if (swipeAction == "none") 0f else -200f, if (rightAction == "none") 0f else 200f) }
                    )
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val small = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 380
            Avatar(c.displayName, c.address, if (small) 48.dp else 52.dp, if (small) 19.sp else 21.sp)
            Spacer(Modifier.width(if (small) 14.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                val draft = DraftStore.get(ctx, c.threadId)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (pinned) { Icon(Ms.PushPinFill, "Pinned", tint = TextSecond, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(c.displayName, fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = TextPrimary, style = listTextStyle())
                    Spacer(Modifier.width(8.dp))
                    Text(shortTime(c.date), fontSize = 12.sp,
                        color = if (c.unread) Brand else TextSecond,
                        fontWeight = if (c.unread) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pColor = if (draft.isNotBlank()) Red else if (c.unread) TextPrimary else TextSecond
                    if (draft.isNotBlank()) PreviewLine(draft, pColor, 14.sp, FontWeight.Normal, Modifier.weight(1f), prefix = "Draft: ")
                    else PreviewLine(Reactions.pretty(c.snippet), pColor, 14.sp, if (c.unread) FontWeight.Medium else FontWeight.Normal, Modifier.weight(1f),
                        prefix = if (c.outgoing) "You: " else "", label = LabelStore.get(ctx, c.threadId),
                        iconTint = if (c.unread) TextPrimary else TextSecond)
                    // Unread badge, like Google Messages: a count pill, or a dot for one message
                    AnimatedVisibility(c.unread, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                        if (c.unreadCount > 1) Box(Modifier.padding(start = 10.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(CircleShape).background(Brand).padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center) {
                            Text(if (c.unreadCount > 99) "99+" else c.unreadCount.toString(), color = OnBrand, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        else Box(Modifier.padding(start = 10.dp).size(10.dp).clip(CircleShape).background(Brand))
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
    // Start from the chat list already in memory so the screen (and its saved scroll spot) is there at once
    var list by remember { mutableStateOf(ConvCache.list?.let { l -> val t = TrashStore.all(ctx).keys; l.filter { it.threadId !in t && ArchiveStore.isArchived(ctx, it.threadId) } } ?: emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { val l = SmsRepository.loadConversations(ctx); val t = TrashStore.all(ctx).keys; l.filter { it.threadId !in t && ArchiveStore.isArchived(ctx, it.threadId) } } }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } },
            title = { Text("Archived", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (list.isEmpty()) {
            EmptyState(Ms.Archive, "No archived conversations", null, Modifier.padding(pad))
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(list, key = { it.threadId }) { conv ->
                Row(Modifier.animateItem().fillMaxWidth().clickable { onOpen(conv) }.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conv.displayName, conv.address, 48.dp, 19.sp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conv.displayName, fontSize = 16.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
                        Spacer(Modifier.height(2.dp))
                        PreviewLine(conv.snippet, TextSecond, 14.sp, FontWeight.Normal)
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
fun StarredScreen(onOpen: (Conversation) -> Unit = {}, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var starred by remember { mutableStateOf(StarredMemory.list) }
    LaunchedEffect(Unit) {
        StarredMemory.list = withContext(Dispatchers.IO) {
            // Only open the chats that really have starred messages (it used to read every chat, which hung)
            val keys = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE).getStringSet("starred_messages", emptySet())?.toSet() ?: emptySet()
            val out = mutableListOf<Pair<Conversation, Message>>()
            if (keys.isNotEmpty()) {
                val threads = HashSet<Long>()
                fun threadsOf(uri: android.net.Uri, ids: List<Long>) = ids.chunked(400).forEach { ch ->
                    runCatching { ctx.contentResolver.query(uri, arrayOf("thread_id"), "_id IN (" + ch.joinToString(",") + ")", null, null)?.use { c -> while (c.moveToNext()) threads.add(c.getLong(0)) } }
                }
                threadsOf(android.provider.Telephony.Sms.CONTENT_URI, keys.filter { it.startsWith("s") }.mapNotNull { it.drop(1).toLongOrNull() })
                threadsOf(android.net.Uri.parse("content://mms"), keys.filter { it.startsWith("m") }.mapNotNull { it.drop(1).toLongOrNull()?.minus(1_000_000L) })
                val convs = (ConvCache.list ?: SmsRepository.loadConversations(ctx)).associateBy { it.threadId }
                for (t in threads) {
                    val c = convs[t] ?: continue
                    for (m in SmsRepository.loadMessages(ctx, t)) if (msgKey(m) in keys) out.add(c to m)
                }
            }
            out.sortedByDescending { it.second.date }
        }
        starred = StarredMemory.list
    }

    Scaffold(containerColor = Bg,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } },
            title = { Text("Starred messages", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (starred.isEmpty()) {
            EmptyState(Ms.Star, "No starred messages", null, Modifier.padding(pad))
        } else LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            items(starred, key = { (_, m) -> msgKey(m) }) { (conv, m) ->
                Row(Modifier.fillMaxWidth().clickable { ChatJump.request(conv.threadId, msgKey(m)); onOpen(conv) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conv.displayName, conv.address, 44.dp, 16.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(conv.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false), style = listTextStyle())
                            Spacer(Modifier.width(6.dp))
                            Icon(Ms.StarFill, null, tint = Amber, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.weight(1f))
                            Text(shortTime(m.date), fontSize = 11.sp, color = TextHint)
                        }
                        Spacer(Modifier.height(2.dp))
                        if (m.body.isBlank()) PreviewLine(if (m.mediaMime?.startsWith("audio/") == true) "\uD83C\uDFA4 Voice message" else if (m.imageUri != null) "\uD83D\uDCF7 Photo" else " ", TextSecond, 14.sp, FontWeight.Normal)
                        else Text(m.body, fontSize = 14.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } },
            title = { Text("Scheduled messages", fontWeight = FontWeight.SemiBold) }) }
    ) { pad ->
        if (items2.isEmpty()) {
            EmptyState(Ms.Schedule, "Nothing scheduled", null, Modifier.padding(pad))
        } else LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items2, key = { it.id }) { sm ->
                Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SearchBg).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Tonal), contentAlignment = Alignment.Center) {
                        Icon(Ms.Schedule, null, tint = OnTonal, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(SmsRepository.getContactName(ctx, sm.address) ?: sm.address, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (sm.mediaPath != null) Text(if (sm.mime?.startsWith("video") == true) "\uD83C\uDFA5 Video" else "\uD83D\uDCF7 Photo", fontSize = 13.sp, color = TextSecond)
                        if (sm.body.isNotBlank()) Text(sm.body, fontSize = 13.sp, color = TextSecond, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        if (sm.failed) Text("Not sent · " + fmt.format(Date(sm.sendAt)), fontSize = 12.sp, color = Red, fontWeight = FontWeight.Medium)
                        else Text(fmt.format(Date(sm.sendAt)), fontSize = 12.sp, color = Brand, fontWeight = FontWeight.Medium)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Ms.MoreVert, "More", tint = TextSecond) }
                        DropdownMenu(menu, { menu = false }, containerColor = Surf) {
                            DropdownMenuItem({ Text("Send now", color = TextPrimary) }, { menu = false
                                Thread { ScheduledSendReceiver.fire(ctx.applicationContext, sm.id, manual = true) }.start(); tick++ }, leadingIcon = { Icon(Ms.SendFill, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Edit message", color = TextPrimary) }, { menu = false
                                val et = android.widget.EditText(ctx); et.setText(sm.body); et.setSelection(et.text.length)
                                android.app.AlertDialog.Builder(ctx).setTitle("Edit scheduled message").setView(et).setPositiveButton("Save") { _, _ ->
                                    val nb = et.text.toString()
                                    if (nb.isNotBlank() || sm.mediaPath != null) { ScheduledSendReceiver.update(ctx, sm.id, body = nb); tick++ }
                                }.setNegativeButton("Cancel", null).show() }, leadingIcon = { Icon(Ms.Edit, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Change time", color = TextPrimary) }, { menu = false
                                pickDateTime(ctx, sm.sendAt) { at -> ScheduledSendReceiver.update(ctx, sm.id, sendAt = at); tick++ } }, leadingIcon = { Icon(Ms.Schedule, null, tint = TextSecond) })
                            DropdownMenuItem({ Text("Delete", color = Red) }, { menu = false; ScheduledSendReceiver.cancel(ctx, sm.id); tick++ }, leadingIcon = { Icon(Ms.Delete, null, tint = Red) })
                        }
                    }
                }
            }
        }
    }
}

/** Date picker, then time picker; calls [onPicked] with a future time only. */
fun pickDateTime(ctx: Context, start: Long, onPicked: (Long) -> Unit) {
    val now = Calendar.getInstance().apply { timeInMillis = maxOf(start, System.currentTimeMillis()) }
    android.app.DatePickerDialog(ctx, { _, y, mo, d ->
        android.app.TimePickerDialog(ctx, { _, h, mi ->
            val t = Calendar.getInstance().apply { set(y, mo, d, h, mi, 0); set(Calendar.MILLISECOND, 0) }
            if (t.timeInMillis <= System.currentTimeMillis()) android.widget.Toast.makeText(ctx, "Pick a future time", android.widget.Toast.LENGTH_SHORT).show()
            else onPicked(t.timeInMillis)
        }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = System.currentTimeMillis() - 1000 }.show()
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

    Scaffold(containerColor = Chrome,
        topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = Chrome, titleContentColor = TextPrimary, navigationIconContentColor = TextPrimary),
            navigationIcon = { IconButton(onBack) { Icon(Ms.ArrowBack, "Back") } },
            title = { Text("New chat", fontWeight = FontWeight.Normal, fontSize = 22.sp) }) }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TextField(to, { to = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Type name or phone number", color = TextSecond) },
                leadingIcon = { Text("To:", color = TextPrimary, modifier = Modifier.padding(start = 12.dp)) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = SearchBg, unfocusedContainerColor = SearchBg, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Brand),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                keyboardActions = KeyboardActions(onDone = {
                    if (to.isNotBlank() && !isGroup) openConv(contacts.find { it.second == to.trim() }?.first ?: to.trim(), typedNumber ?: to.trim())
                    else if (isGroup && typedNumber != null) { if (selected.none { it.second == typedNumber }) selected = selected + (typedNumber to typedNumber); to = "" }
                }))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { isGroup = !isGroup }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Tonal, contentColor = OnTonal)) {
                    Icon(if (isGroup) Ms.GroupRemove else Ms.GroupAdd, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isGroup) "Cancel group" else "Create group")
                }
            }
            if (isGroup && selected.isNotEmpty()) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    selected.forEach { (name, num) ->
                        InputChip(selected = true, onClick = { selected = selected.filter { it.second != num } }, label = { Text(name, fontSize = 13.sp) },
                            trailingIcon = { Icon(Ms.Close, null, Modifier.size(16.dp)) },
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
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
                filtered.groupBy { (it.first.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#').toString() }.forEach { (letter, group) ->
                    item(key = "hdr_$letter") { Text(letter, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 6.dp)) }
                    items(group, key = { it.second }) { (name, number) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp)).background(Surf).clickable {
                            if (isGroup) { if (selected.none { it.second == number }) selected = selected + (name to number); to = "" }
                            else openConv(name, number)
                        }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name, number, 52.dp, 20.sp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) { Text(name, fontSize = 17.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(number, fontSize = 14.sp, color = TextSecond, maxLines = 1) }
                            if (isGroup && selected.any { it.second == number }) Icon(Ms.CheckCircleFill, null, tint = Brand)
                        }
                    }
                }
            }
        }
    }
}



/** Last starred list, so the screen comes back with its rows (and its scroll spot) right away. */
object StarredMemory { var list: List<Pair<Conversation, Message>> = emptyList() }
