package com.heimish.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Something shared into the app from another app, waiting for a chat to land in. */
object ShareState {
    var text: String? = null
    var uris: List<Uri> = emptyList()
    var mime: String? = null
    fun clear() { text = null; uris = emptyList(); mime = null }
    val isEmpty get() = text.isNullOrBlank() && uris.isEmpty()
}

/**
 * Turns an incoming Intent into a place in the app: notification taps, conversation shortcuts,
 * sms:/smsto: links (and "Message" from Contacts / the dialer), and shares from other apps.
 */
object IntentRouter {
    var pending by mutableStateOf<Intent?>(null)

    sealed class Target {
        data class Chat(val conv: Conversation) : Target()
        data object PickRecipient : Target()
    }

    /** "sms:+1555,+1666?body=hi" -> "+1555;+1666" */
    fun recipientsFrom(uri: Uri): String {
        val ssp = (uri.schemeSpecificPart ?: "").substringBefore("?")
        return ssp.split(",", ";").map { it.replace(Regex("[\\s()\\-.]"), "").trim() }.filter { it.isNotBlank() }.joinToString(";")
    }

    private fun bodyFrom(i: Intent, uri: Uri?): String? {
        i.getStringExtra("sms_body")?.let { if (it.isNotBlank()) return it }
        i.getStringExtra(Intent.EXTRA_TEXT)?.let { if (it.isNotBlank()) return it }
        val q = uri?.schemeSpecificPart?.substringAfter("?", "") ?: ""
        return q.split("&").firstOrNull { it.startsWith("body=") }?.removePrefix("body=")?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }
    }

    private fun chatFor(ctx: Context, threadId: Long, address: String, name: String?): Target.Chat {
        val addr = SmsRepository.threadAddress(ctx, threadId, address)
        val title = name?.takeIf { it.isNotBlank() && it != address }
            ?: SmsRepository.splitRecipients(addr).joinToString(", ") { SmsRepository.getContactName(ctx, it) ?: it }
        return Target.Chat(Conversation(threadId, addr, title, "", System.currentTimeMillis(), false))
    }

    @Suppress("DEPRECATION")
    fun resolve(ctx: Context, i: Intent): Target? {
        // Notification tap / conversation shortcut
        val tid = i.getLongExtra("thread_id", -1L)
        val addrExtra = i.getStringExtra("address")
        if (tid >= 0 && i.getBooleanExtra("focus_reply", false)) ReplyFocus.request(tid)
        if (tid >= 0 && addrExtra != null) {
            // Reminder notification: open right at the message
            i.getStringExtra("jump_key")?.let { k -> android.os.Handler(android.os.Looper.getMainLooper()).post { ChatJump.request(tid, k) } }
            return chatFor(ctx, tid, addrExtra, i.getStringExtra("name"))
        }

        when (i.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                ShareState.clear()
                ShareState.text = i.getStringExtra(Intent.EXTRA_TEXT)
                ShareState.mime = i.type
                ShareState.uris = if (i.action == Intent.ACTION_SEND_MULTIPLE) {
                    (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
                } else {
                    listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
                }
                // Direct share to a conversation from the share sheet
                val sc = i.getStringExtra(Intent.EXTRA_SHORTCUT_ID) ?: i.getStringExtra("android.intent.extra.shortcut.ID")
                val scTid = sc?.removePrefix("thread_")?.toLongOrNull()
                if (scTid != null) {
                    val addr = SmsRepository.threadAddress(ctx, scTid, "")
                    if (addr.isNotBlank()) return chatFor(ctx, scTid, addr, null)
                }
                return if (ShareState.isEmpty) null else Target.PickRecipient
            }
            Intent.ACTION_SENDTO, Intent.ACTION_VIEW -> {
                val data = i.data ?: return null
                if (data.scheme !in setOf("sms", "smsto", "mms", "mmsto")) return null
                val to = recipientsFrom(data)
                val body = bodyFrom(i, data)
                if (to.isBlank()) {
                    ShareState.clear(); ShareState.text = body
                    return Target.PickRecipient
                }
                val threadId = SmsRepository.getOrCreateThreadId(ctx, to)
                if (!body.isNullOrBlank() && threadId > 0) DraftStore.set(ctx, threadId, body)
                return chatFor(ctx, threadId, to, null)
            }
        }
        return null
    }
}
