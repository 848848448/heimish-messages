package com.heimish.messages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.annotation.DrawableRes
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Material Symbols: outlined on the tiles, filled on the active filter chip
private class SearchFilter(val key: String, val label: String, @DrawableRes val icon: Int, @DrawableRes val iconOn: Int)
private val SearchFilters = listOf(
    SearchFilter("unread", "Unread", R.drawable.ms_mark_chat_unread, R.drawable.ms_mark_chat_unread_fill),
    SearchFilter("known", "Known", R.drawable.ms_person, R.drawable.ms_person_fill),
    SearchFilter("unknown", "Unknown", R.drawable.ms_help, R.drawable.ms_help_fill),
    SearchFilter("starred", "Starred", R.drawable.ms_star, R.drawable.ms_star_fill),
    SearchFilter("images", "Images", R.drawable.ms_image, R.drawable.ms_image_fill),
    SearchFilter("videos", "Videos", R.drawable.ms_videocam, R.drawable.ms_videocam_fill),
    SearchFilter("places", "Places", R.drawable.ms_location_on, R.drawable.ms_location_on_fill),
    SearchFilter("links", "Links", R.drawable.ms_link, R.drawable.ms_link_fill),
)

internal object SearchMemory { var q = ""; var filter: String? = null }

private class FoundMsg(val conv: Conversation, val m: Message)

internal fun highlight(text: String, q: String, color: Color): AnnotatedString = buildAnnotatedString {
    if (q.isBlank()) { append(text); return@buildAnnotatedString }
    var i = 0
    while (true) {
        val j = text.indexOf(q, i, ignoreCase = true)
        if (j < 0) { append(text.substring(i)); break }
        append(text.substring(i, j))
        val end = (j + q.length).coerceAtMost(text.length)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(text.substring(j, end)) }
        i = end
    }
}

/** Google Messages search: search pill, a grid of filters, then results grouped in rounded cards. */
@Composable
fun SearchScreen(onOpen: (Conversation) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Kept while a result is open, so Back from that chat returns to the same search
    var q by remember { mutableStateOf(SearchMemory.q) }
    var filter by remember { mutableStateOf(SearchMemory.filter) }
    SideEffect { SearchMemory.q = q; SearchMemory.filter = filter }
    BackHandler { if (filter != null) filter = null else onBack() }
    // Start from the list we already have (instant), then refresh in the background
    val convs by produceState(ConvCache.list?.let { l -> val t = TrashStore.all(ctx).keys; l.filter { it.threadId !in t } } ?: emptyList()) {
        runCatching { value = withContext(Dispatchers.IO) { val l = SmsRepository.loadConversations(ctx); val t = TrashStore.all(ctx).keys; l.filter { it.threadId !in t } } }
    }
    val contacts by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.IO) { runCatching { SmsRepository.loadContactsList(ctx) }.getOrDefault(emptyList()) } }
    val needMsgs = filter in setOf("starred", "images", "videos", "places", "links")
    // Asks the message store for exactly what the filter wants, across ALL chats (it used to read only the 40 newest chats)
    val recent by produceState(emptyList<FoundMsg>(), filter, convs) {
        value = emptyList()
        val f = filter
        if (needMsgs && f != null && convs.isNotEmpty()) value = withContext(Dispatchers.IO) { runCatching { findForFilter(ctx, f, convs) }.getOrDefault(emptyList()) }
    }
    val hits by produceState(emptyList<MsgHit>(), q) {
        if (q.trim().length < 2) { value = emptyList(); return@produceState }
        delay(250) // wait until typing pauses instead of querying every letter
        value = withContext(Dispatchers.IO) { runCatching { val t = TrashStore.all(ctx).keys; SmsRepository.searchMessages(ctx, q.trim(), 60).filter { it.threadId !in t } }.getOrDefault(emptyList()) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val qt = q.trim()
    Scaffold(containerColor = Chrome) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(56.dp).clip(RoundedCornerShape(28.dp)).background(SearchBg).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (filter != null) filter = null else onBack() }) { Icon(Ms.ArrowBack, "Back", tint = TextPrimary) }
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search messages", color = TextSecond, fontSize = 17.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp, fontFamily = FontState.family),
                        cursorBrush = SolidColor(Brand), modifier = Modifier.fillMaxWidth().focusRequester(focus))
                }
                if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Ms.Close, "Clear", tint = TextSecond) }
            }
            if (filter != null) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                val f = SearchFilters.first { it.key == filter }
                Row(Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(Tonal).clickable { filter = null }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ImageVector.vectorResource(f.iconOn), null, tint = OnTonal, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(f.label, color = OnTonal, fontSize = 14.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.width(6.dp))
                    Icon(Ms.Close, "Remove filter", tint = OnTonal, modifier = Modifier.size(16.dp))
                }
            }
            if (filter == null && qt.isEmpty()) {
                // Filter tiles, two per row, like Google Messages
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SearchFilters.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { f ->
                                Row(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(20.dp)).background(Surf).clickable { filter = f.key }.padding(horizontal = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(ImageVector.vectorResource(f.icon), null, tint = Brand, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(14.dp))
                                    Text(f.label, color = TextPrimary, fontSize = 16.sp)
                                }
                            }
                        }
                    }
                }
            } else SearchResults(ctx, convs, contacts, recent, hits, filter, needMsgs, qt, onOpen)
        }
    }
}

/** Messages for a search filter, read straight from the SMS/MMS store (newest first, capped). */
private fun findForFilter(ctx: android.content.Context, filter: String, convs: List<Conversation>): List<FoundMsg> {
    val byThread = convs.associateBy { it.threadId }
    val cr = ctx.contentResolver
    val out = ArrayList<FoundMsg>()
    fun convFor(t: Long, addr: String) = byThread[t] ?: Conversation(t, addr, SmsRepository.getContactName(ctx, addr) ?: addr, "", 0L, false)
    when (filter) {
        "starred" -> {
            val keys = ctx.getSharedPreferences("heimish_prefs", android.content.Context.MODE_PRIVATE).getStringSet("starred_messages", emptySet())?.toSet() ?: emptySet()
            val threads = HashSet<Long>()
            fun threadsOf(uri: android.net.Uri, ids: List<Long>) = ids.chunked(400).forEach { ch ->
                runCatching { cr.query(uri, arrayOf("thread_id"), "_id IN (" + ch.joinToString(",") + ")", null, null)?.use { c -> while (c.moveToNext()) threads.add(c.getLong(0)) } }
            }
            threadsOf(android.provider.Telephony.Sms.CONTENT_URI, keys.filter { it.startsWith("s") }.mapNotNull { it.drop(1).toLongOrNull() })
            threadsOf(android.net.Uri.parse("content://mms"), keys.filter { it.startsWith("m") }.mapNotNull { it.drop(1).toLongOrNull()?.minus(1_000_000L) })
            for (t in threads) {
                val c = byThread[t] ?: continue
                for (m in SmsRepository.loadMessages(ctx, t, 2000)) if (msgKey(m) in keys) out.add(FoundMsg(c, m))
            }
        }
        "images", "videos" -> {
            val kind = if (filter == "images") "image/%" else "video/%"
            // newest attachments first: part rows of that type, then their messages
            val parts = LinkedHashMap<Long, Pair<Long, String>>() // mms id -> (part id, type)
            cr.query(android.net.Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct"), "ct LIKE ?", arrayOf(kind), "_id DESC LIMIT 300")?.use { c ->
                while (c.moveToNext()) { val mid = c.getLong(1); if (!parts.containsKey(mid)) parts[mid] = c.getLong(0) to (c.getString(2) ?: "") }
            }
            parts.keys.chunked(400).forEach { ch ->
                cr.query(android.net.Uri.parse("content://mms"), arrayOf("_id", "thread_id", "date", "msg_box"), "_id IN (" + ch.joinToString(",") + ")", null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val mid = c.getLong(0); val t = c.getLong(1); val p = parts[mid] ?: continue
                        val conv = byThread[t] ?: continue
                        val m = Message(id = mid + 1_000_000L, body = "", date = c.getLong(2) * 1000L, incoming = c.getInt(3) == 1, isMms = true,
                            imageUri = android.net.Uri.parse("content://mms/part/" + p.first), mediaMime = p.second)
                        out.add(FoundMsg(conv, m))
                    }
                }
            }
        }
        "links", "places" -> {
            val like = if (filter == "links") listOf("%http%", "%www.%") else listOf("%maps%", "%goo.gl%", "%geo:%")
            val sel = like.joinToString(" OR ") { android.provider.Telephony.Sms.BODY + " LIKE ?" }
            cr.query(android.provider.Telephony.Sms.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms._ID, android.provider.Telephony.Sms.THREAD_ID, android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.BODY,
                    android.provider.Telephony.Sms.DATE, android.provider.Telephony.Sms.TYPE),
                sel, like.toTypedArray(), android.provider.Telephony.Sms.DATE + " DESC LIMIT 400")?.use { c ->
                while (c.moveToNext()) {
                    val t = c.getLong(1); val a = c.getString(2) ?: ""
                    if (byThread[t] == null) continue // trashed / spam chats stay out
                    out.add(FoundMsg(convFor(t, a), Message(id = c.getLong(0), body = c.getString(3) ?: "", date = c.getLong(4), incoming = c.getInt(5) == 1, address = a, type = c.getInt(5))))
                }
            }
        }
    }
    return out
}

/** Results list (split out: the old early "return" out of the Column could crash Compose when the search opened). */
@Composable
private fun SearchResults(ctx: android.content.Context, convs: List<Conversation>, contacts: List<Pair<String, String>>, recent: List<FoundMsg>, hits: List<MsgHit>,
                          filter: String?, needMsgs: Boolean, qt: String, onOpen: (Conversation) -> Unit) {
            val scope = rememberCoroutineScope()
            val convMatches = convs.filter { c ->
                (qt.isEmpty() || c.displayName.contains(qt, true) || c.address.contains(qt)) && when (filter) {
                    "unread" -> c.unread
                    // displayName already holds the contact name; no contact lookups on every keystroke
                    "known" -> c.address.contains(";") || c.displayName != c.address
                    "unknown" -> !c.address.contains(";") && c.displayName == c.address
                    null -> qt.isNotEmpty()
                    else -> false
                }
            }
            val msgMatches: List<FoundMsg> = if (needMsgs) recent.filter { fm ->
                val m = fm.m
                (qt.isEmpty() || m.body.contains(qt, true)) && when (filter) {
                    "starred" -> StarStore.isStarred(ctx, msgKey(m))
                    "images" -> m.imageUri != null && (m.mediaMime?.startsWith("image") ?: true)
                    "videos" -> m.mediaMime?.startsWith("video") == true
                    "links" -> isLinkMsg(m)
                    "places" -> Regex("(?i)maps\\.google|goo\\.gl/maps|maps\\.app\\.goo|maps\\.apple|geo:").containsMatchIn(m.body)
                    else -> false
                }
            }.sortedByDescending { it.m.date } else emptyList()
            val contactMatches = if (filter == null && qt.isNotEmpty()) contacts.filter { it.first.contains(qt, true) || it.second.filter(Char::isDigit).contains(qt.filter(Char::isDigit).ifEmpty { "\u0000" }) }.take(20) else emptyList()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                fun header(t: String, k: String = t) = item(key = "h_$k") { Text(t, color = TextSecond, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)) }
                if (convMatches.isNotEmpty()) {
                    header("Conversations")
                    items(convMatches, key = { "c" + it.threadId }) { c ->
                        ResultRow(c.displayName, c.address, highlight(c.displayName, qt, Brand), AnnotatedString(c.snippet), shortTime(c.date)) { onOpen(c) }
                    }
                }
                if (filter == null && hits.isNotEmpty()) {
                    header("Messages")
                    items(hits, key = { "m" + it.id }) { h ->
                        ResultRow(h.name, h.address, AnnotatedString(h.name), highlight(h.body, qt, Brand), shortTime(h.date)) {
                            onOpen(convs.firstOrNull { it.threadId == h.threadId } ?: Conversation(h.threadId, h.address, h.name, h.body, h.date, false))
                        }
                    }
                }
                if (msgMatches.isNotEmpty()) {
                    header("Messages", "found") // own key: two items with the same key crash a LazyColumn
                    items(msgMatches.take(200), key = { "f" + msgKey(it.m) }) { fm ->
                        ResultRow(fm.conv.displayName, fm.conv.address, AnnotatedString(fm.conv.displayName),
                            highlight(fm.m.body.ifBlank { if (fm.m.mediaMime?.startsWith("video") == true) "Video" else "Photo" }, qt, Brand), shortTime(fm.m.date), media = fm.m.imageUri) { onOpen(fm.conv) }
                    }
                }
                if (contactMatches.isNotEmpty()) {
                    header("Contacts")
                    items(contactMatches, key = { "p" + it.second + it.first }) { (name, number) ->
                        ResultRow(name, number, highlight(name, qt, Brand), AnnotatedString(number), "") {
                            // the thread lookup can create a thread: off the main thread
                            scope.launch {
                                val tid = withContext(Dispatchers.IO) { runCatching { SmsRepository.getThreadIdForAddress(ctx, number) }.getOrDefault(-1L) }
                                onOpen(Conversation(tid, number, name, "", System.currentTimeMillis(), false))
                            }
                        }
                    }
                }
                if (convMatches.isEmpty() && msgMatches.isEmpty() && contactMatches.isEmpty() && (filter != null || hits.isEmpty())) item {
                    if (needMsgs && recent.isEmpty()) Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Brand, strokeWidth = 3.dp)
                    } else EmptyState(Ms.SearchOff, "No results", null, Modifier.padding(top = 48.dp))
                }
            }
}

@Composable
private fun ResultRow(name: String, address: String, title: AnnotatedString, sub: AnnotatedString, time: String, media: android.net.Uri? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp)).background(Surf).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(name, address, 44.dp, 18.sp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = TextPrimary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (time.isNotEmpty()) Text(time, color = TextSecond, fontSize = 12.sp)
            }
            if (sub.isNotEmpty()) Text(sub, color = TextSecond, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (media != null) { Spacer(Modifier.width(10.dp)); coil.compose.AsyncImage(media, null, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}
