package com.heimish.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True for a message the full-screen viewer shows: a photo, or a video (a voice note labelled video/3gpp stays out once
 *  its file says it has no picture). */
internal fun isViewerMedia(m: Message): Boolean {
    val u = m.imageUri ?: return false
    val mime = m.mediaMime ?: return true
    if (mime.startsWith("image/")) return true
    if (!mime.startsWith("video/")) return false
    val p = MmsMedia.cachedProbe(u) ?: return true
    return p.hasVideo || !p.hasAudio
}

/** The chat's media viewer: every photo and video in the chat, opened at the tapped one (swipe for the others). */
@Composable
fun ChatPhotoViewer(msgs: List<Message>, startKey: String, isGroupChat: Boolean, chatName: String, onClose: () -> Unit, onReacted: () -> Unit) {
    val photos = remember(msgs, startKey) { msgs.filter { isViewerMedia(it) || msgKey(it) == startKey && it.imageUri != null } }
    val items = remember(photos, isGroupChat, chatName) {
        photos.map { m ->
            val who = if (!m.incoming) "You" else if (isGroupChat) (m.address?.let { a -> SmsRepository.peekContactName(a)?.first ?: a } ?: "") else chatName
            ViewerItem(m.imageUri!!, who, m.date, msgKey(m), m.incoming, m.address, m.mediaMime?.takeIf { it.startsWith("video/") })
        }
    }
    val start = remember(startKey) { photos.indexOfFirst { msgKey(it) == startKey }.coerceAtLeast(0) }
    PhotoViewer(items, start, onClose = onClose, onReacted = { onReacted() })
}
/** The last messages read for recently opened chats, so opening a chat again shows it instantly. */
object ThreadCache {
    private val cache = android.util.LruCache<Long, List<Message>>(16)
    fun get(threadId: Long): List<Message>? = cache.get(threadId)
    fun put(threadId: Long, msgs: List<Message>) { cache.put(threadId, msgs) }
}


/** One row of the chat list (built once per change of the messages, bottom-up). */
@androidx.compose.runtime.Immutable
internal sealed class ChatRow(val key: String, val type: String) {
    class Day(val label: String) : ChatRow("day_$label", "day")
    data object Unread : ChatRow("unread_divider", "unread")
    class Msg(val m: Message, val i: Int, val day: List<Message>) :
        ChatRow(msgKey(m), if (m.imageUri != null) "media" else if (m.incoming) "in" else "out")
}

/** A chat opened to reply (e.g. "Reply" from a notification) gets the keyboard; every other opening does not. */
object ReplyFocus {
    @Volatile private var threadId = -1L
    fun request(id: Long) { threadId = id }
    fun take(id: Long): Boolean = (threadId == id).also { if (it) threadId = -1L }
}

/** The slim "Unread messages" line above the first text that was new when the chat was opened (GM / WhatsApp style). */
@Composable
fun UnreadDivider() {
    val lang = java.util.Locale.getDefault().language
    val label = if (lang == "yi" || lang == "ji" || lang == "he" || lang == "iw") "נייע מעסעדזשעס" else "Unread messages"
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).height(1.dp).background(Brand.copy(alpha = .35f)))
        androidx.compose.material3.Text(label, color = Brand, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .background(Brand.copy(alpha = .10f)).padding(horizontal = 10.dp, vertical = 3.dp))
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).height(1.dp).background(Brand.copy(alpha = .35f)))
    }
}
