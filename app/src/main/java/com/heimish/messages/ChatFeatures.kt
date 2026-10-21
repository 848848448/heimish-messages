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
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.blur
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

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Chat features (Release 22): reply quotes, reactions from others, in-chat search hits, copy-code chips, scheduled send UI,
// reminders on a message. Kept out of ThreadScreen.kt so that file stays small enough to upload.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/** What the bubbles of the open chat need besides the message itself. */
@Stable
class ChatBubbleExtras(
    val reactions: Map<String, List<RemoteReaction>> = emptyMap(),
    val query: String = "",
    val currentHit: String? = null,
    val flashKey: String? = null,
    val replyVer: Int = 0,
    val onJump: (String) -> Unit = {},
)
val LocalChatExtras = compositionLocalOf { ChatBubbleExtras() }

private val HitBg = Color(0xFFFFE082)
private val HitBgNow = Color(0xFFFFB300)

/** Search hits in [s] get a yellow background (the current hit a stronger one). */
fun highlightHits(s: androidx.compose.ui.text.AnnotatedString, q: String, current: Boolean): androidx.compose.ui.text.AnnotatedString {
    if (q.isBlank()) return s
    val b = androidx.compose.ui.text.AnnotatedString.Builder(s)
    var i = s.text.indexOf(q, ignoreCase = true)
    while (i >= 0) {
        b.addStyle(SpanStyle(background = if (current) HitBgNow else HitBg, color = Color(0xFF202124)), i, i + q.length)
        i = s.text.indexOf(q, i + q.length, ignoreCase = true)
    }
    return b.toAnnotatedString()
}

/** The quoted message on top of a reply bubble (tap = jump to it). */
@Composable
fun ReplyQuoteBlock(m: Message, isIn: Boolean, textColor: Color, barColor: Color) {
    val ctx = LocalContext.current
    val ex = LocalChatExtras.current
    val q = remember(m.id, m.isMms, ex.replyVer) { ReplyStore.get(ctx, msgKey(m)) } ?: return
    Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp).widthIn(min = 140.dp).height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(16.dp)).background(textColor.copy(alpha = .09f)).clickable { ex.onJump(q.key) }
        .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(barColor))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(q.who, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = barColor, maxLines = 1, overflow = TextOverflow.Ellipsis, style = listTextStyle())
            Text(q.snippet, fontSize = 13.sp, color = textColor.copy(alpha = .8f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = listTextStyle())
        }
    }
}

/** Reactions under a bubble: yours plus other people's (from their "Liked “…”" texts). Tap = who reacted. */
@Composable
fun ReactionChips(key: String, mine: String?, isIn: Boolean) {
    val ex = LocalChatExtras.current
    val remote = ex.reactions[key].orEmpty()
    if (mine == null && remote.isEmpty()) return
    val ctx = LocalContext.current
    val all = remote.map { it.emoji } + listOfNotNull(mine)
    val shown = all.distinct().take(4).joinToString("")
    Row(Modifier.padding(horizontal = 8.dp).offset(y = (-6).dp).clip(CircleShape).background(Surf).border(1.dp, DivLt, CircleShape)
        .clickable {
            val who = remote.map { r -> (if (r.from == "me") "You" else SmsRepository.getContactName(ctx, r.from) ?: r.from) + " " + r.emoji } + listOfNotNull(mine?.let { "You $it" })
            Toast.makeText(ctx, who.distinct().joinToString("\n"), Toast.LENGTH_LONG).show()
        }
        .padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(shown, fontSize = 13.sp)
        if (all.size > 1) Text(" " + all.size, fontSize = 12.sp, color = TextSecond)
    }
}

fun copyCode(ctx: Context, code: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", code))
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied $code", Toast.LENGTH_SHORT).show()
}

/** One-tap "Copy code 123456" under a message with a one-time code. */
@Composable
fun CopyCodeChip(code: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Surface(onClick = { copyCode(ctx, code) }, shape = RoundedCornerShape(16.dp), color = Tonal, contentColor = OnTonal, modifier = modifier) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ms.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
            Text("Copy code $code", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Smart replies under the last incoming message (on-device rules, Yiddish-aware). */
@Composable
fun SmartReplyRow(last: Message, onSend: (String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE) }
    val code = remember(last.id, last.body) { if (prefs.getBoolean("otp_chip", true)) Otp.find(last.body) else null }
    val replies = remember(last.id, last.body) {
        if (!prefs.getBoolean("smart_replies", true) || code != null || Reactions.parse(last.body) != null) emptyList() else SmartReplies.suggest(last.body)
    }
    // a one-time code already has its "Copy code" chip under the bubble; no reply chips for it
    if (replies.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        replies.forEach { reply ->
            Surface(onClick = { onSend(reply) }, shape = RoundedCornerShape(20.dp), color = Color.Transparent, border = BorderStroke(1.dp, DivLt)) {
                Text(reply, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 14.sp, color = Brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Time menus (schedule send, remind me) ───────────────────────────────────
object TimeMenu {
    private fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private fun t(ctx: Context, ms: Long) = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(ms))

    /** Quick choices plus "Pick date and time…". */
    fun show(ctx: Context, title: String, reminder: Boolean, onPick: (Long) -> Unit) {
        val now = System.currentTimeMillis()
        val opts = ArrayList<Pair<String, Long>>()
        opts.add("In 1 hour" to now + 3_600_000L)
        if (reminder) opts.add("In 3 hours" to now + 3 * 3_600_000L)
        if (at(0, 20) > now + 600_000L) opts.add("Tonight, " + t(ctx, at(0, 20)) to at(0, 20))
        opts.add("Tomorrow morning, " + t(ctx, at(1, if (reminder) 9 else 8)) to at(1, if (reminder) 9 else 8))
        if (!reminder) opts.add("Tomorrow afternoon, " + t(ctx, at(1, 13)) to at(1, 13))
        val labels = (opts.map { it.first } + "Pick date and time…").toTypedArray()
        android.app.AlertDialog.Builder(ctx).setTitle(title).setItems(labels) { _, i ->
            if (i < opts.size) onPick(opts[i].second) else pickDateTime(ctx, now + 3_600_000L, onPick)
        }.show()
    }
    fun label(ms: Long): String = SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
}

// ── Scheduled messages inside the chat ──────────────────────────────────────
@Composable
fun ScheduledInChat(sm: ScheduledMessage, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.End) {
        Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).border(1.5.dp, Brand.copy(alpha = .6f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.End) {
            if (sm.mediaPath != null) {
                if (sm.mime?.startsWith("video") == true) Text("\uD83C\uDFA5 Video", color = TextPrimary, fontSize = 15.sp)
                else AsyncImage(File(sm.mediaPath), null, Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            if (sm.body.isNotBlank()) Text(sm.body, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.padding(top = if (sm.mediaPath != null) 6.dp else 0.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, end = 6.dp)) {
            Icon(Ms.Schedule, null, tint = Brand, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(3.dp))
            Text("Scheduled · " + TimeMenu.label(sm.sendAt), fontSize = 11.sp, color = TextHint)
        }
    }
}

/** Tap a scheduled message: send now, edit the text, change the time, or delete it. */
@Composable
fun ScheduledMessageDialog(sm: ScheduledMessage, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Surf, title = { Text("Scheduled message", color = TextPrimary) },
        text = {
            Column {
                if (sm.mediaPath != null && sm.mime?.startsWith("video") != true) AsyncImage(File(sm.mediaPath), null, Modifier.size(140.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                if (sm.body.isNotBlank()) Text(sm.body, color = TextPrimary, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ms.Schedule, null, tint = Brand, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(SimpleDateFormat("EEEE, MMM d, h:mm a", Locale.getDefault()).format(Date(sm.sendAt)), color = Brand, fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({
                        onDismiss()
                        val et = android.widget.EditText(ctx); et.setText(sm.body); et.setSelection(et.text.length)
                        android.app.AlertDialog.Builder(ctx).setTitle("Edit scheduled message").setView(et).setPositiveButton("Save") { _, _ ->
                            val nb = et.text.toString()
                            if (nb.isNotBlank() || sm.mediaPath != null) { ScheduledSendReceiver.update(ctx, sm.id, body = nb); onChanged() }
                        }.setNegativeButton("Cancel", null).show()
                    }) { Text("Edit text", color = Brand) }
                    TextButton({ onDismiss(); pickDateTime(ctx, sm.sendAt) { at -> ScheduledSendReceiver.update(ctx, sm.id, sendAt = at); onChanged() } }) { Text("Change time", color = Brand) }
                    TextButton({ onDismiss(); ScheduledSendReceiver.cancel(ctx, sm.id); onChanged() }) { Text("Delete", color = Red) }
                }
            }
        },
        confirmButton = { TextButton({ onDismiss(); Thread { ScheduledSendReceiver.fire(ctx.applicationContext, sm.id); main.post(onChanged) }.start() }) { Text("Send now", color = Brand) } },
        dismissButton = { TextButton(onDismiss) { Text("Close", color = TextSecond) } })
}

// ── In-chat search: hits highlighted, ↑ ↓ to go through them ────────────────
@Composable
fun ChatSearchActions(hits: Int, at: Int, filter: Int, onPrev: () -> Unit, onNext: () -> Unit, onFilter: () -> Unit, onClose: () -> Unit) {
    if (hits > 0) Text("${at + 1} / $hits", fontSize = 13.sp, color = TextSecond, modifier = Modifier.padding(horizontal = 2.dp))
    // Up = older hit, down = newer (the newest hit is shown first)
    IconButton(onPrev, enabled = hits > 0 && at < hits - 1) { Icon(Ms.KeyboardArrowUp, "Older match") }
    IconButton(onNext, enabled = hits > 0 && at > 0) { Icon(Ms.KeyboardArrowDown, "Newer match") }
    TextButton(onFilter) { Text(listOf("All", "Photos", "Links")[filter], color = Brand) }
    IconButton(onClose) { Icon(Ms.Close, "Close search") }
}

// ── Long-press menu for a message ───────────────────────────────────────────
@Composable
fun MessageActionsOverlay(
    m: Message, conversation: Conversation, address: String, reactTick: Int, bubbleY: Float?,
    onReply: () -> Unit, onResend: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit, onDetails: () -> Unit, onForward: () -> Unit,
    onChanged: () -> Unit, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val key = msgKey(m)
    val starred = StarStore.isStarred(ctx, key)
    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val reminder = remember(key) { ReminderStore.forMessage(ctx, key) }
    val acts = buildList {
        if (m.failed) add(LongPressAction(Ms.Refresh, "Resend", Brand) { onResend(); onDismiss() })
        add(LongPressAction(Ms.Reply, "Reply") { onReply(); onDismiss() })
        // Google Messages: Forward opens the in-app "Forward message" picker (text, photos, videos, voice notes, files)
        add(LongPressAction(Ms.Forward, "Forward") { onForward(); onDismiss() })
        if (m.body.isNotBlank()) add(LongPressAction(Ms.ContentCopy, "Copy") {
            clip.setPrimaryClip(ClipData.newPlainText("msg", m.body))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show(); onDismiss()
        })
        Otp.find(m.body)?.let { code -> add(LongPressAction(Ms.Password, "Copy code $code") { copyCode(ctx, code); onDismiss() }) }
        add(LongPressAction(if (starred) Ms.StarFill else Ms.Star, if (starred) "Unstar" else "Star") { StarStore.toggle(ctx, key); onChanged(); onDismiss() })
        if (reminder != null) add(LongPressAction(Ms.NotificationsOff, "Cancel reminder (" + TimeMenu.label(reminder.at) + ")") {
            RemindReceiver.cancel(ctx, reminder.id); Toast.makeText(ctx, "Reminder removed", Toast.LENGTH_SHORT).show(); onDismiss()
        }) else add(LongPressAction(Ms.Notifications, "Remind me") {
            onDismiss()
            TimeMenu.show(ctx, "Remind me about this message", reminder = true) { at ->
                RemindReceiver.set(ctx, conversation.copy(address = address), at, m)
                Toast.makeText(ctx, "I'll remind you " + TimeMenu.label(at), Toast.LENGTH_SHORT).show()
            }
        })
        if (MsgPinStore.has(ctx, key)) add(LongPressAction(Ms.PushPin, "Unpin") { MsgPinStore.toggle(ctx, key); onChanged(); onDismiss() })
        add(LongPressAction(Ms.Delete, "Delete") { onDelete(); onDismiss() })
        add(LongPressAction(Ms.Checklist, "Select more") { onSelect(); onDismiss() })
        add(LongPressAction(Ms.Info, "Info") { onDetails(); onDismiss() })
    }
    LongPressOverlay(m, reactTick, bubbleY, ReactionStore.get(ctx, key), acts,
        onReact = { emoji ->
            val cur = ReactionStore.get(ctx, key)
            val next = if (cur == emoji) null else emoji
            ReactionStore.set(ctx, key, next)
            // Over SMS the other phone only sees text: send the standard "Liked “…”" so iPhones and Google Messages show it as a reaction
            if (next != null) Reactions.sendFallback(ctx, address, m, next, removed = false)
            else if (cur != null) Reactions.sendFallback(ctx, address, m, cur, removed = true)
            onChanged(); onDismiss()
        },
        onDismiss = onDismiss)
}
