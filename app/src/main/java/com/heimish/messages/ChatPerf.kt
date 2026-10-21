package com.heimish.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The chat's photo viewer: every photo in the chat, opened at the tapped one (swipe for the others). */
@Composable
fun ChatPhotoViewer(msgs: List<Message>, startKey: String, isGroupChat: Boolean, chatName: String, onClose: () -> Unit, onReacted: () -> Unit) {
    val photos = remember(msgs) { msgs.filter { it.imageUri != null && (it.mediaMime == null || it.mediaMime.startsWith("image/")) } }
    val items = remember(photos, isGroupChat, chatName) {
        photos.map { m ->
            val who = if (!m.incoming) "You" else if (isGroupChat) (m.address?.let { a -> SmsRepository.peekContactName(a)?.first ?: a } ?: "") else chatName
            ViewerItem(m.imageUri!!, who, m.date, msgKey(m), m.incoming, m.address)
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
