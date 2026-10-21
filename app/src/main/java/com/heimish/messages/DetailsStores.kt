package com.heimish.messages

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hp(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)

// ━━━━━━━━━━━━ Per-conversation stores ━━━━━━━━━━━━

/** Per-conversation notification channel: sound, vibrate, importance; bubbles on/off. */
object ConvNotif {
    private const val PARENT = "heimish_sms"
    const val IMP_PRIORITY = 4; const val IMP_DEFAULT = 3; const val IMP_SILENT = 2

    fun custom(ctx: Context, tid: Long) = hp(ctx).getBoolean("cn_on_$tid", false) || hp(ctx).contains("tone_$tid")
    fun tone(ctx: Context, tid: Long): String = hp(ctx).getString("tone_$tid", "default") ?: "default"   // "default" | "" (silent) | uri
    fun vibrate(ctx: Context, tid: Long) = hp(ctx).getBoolean("cn_vib_$tid", true)
    fun importance(ctx: Context, tid: Long) = hp(ctx).getInt("cn_imp_$tid", IMP_PRIORITY)
    fun bubbleOn(ctx: Context, tid: Long) = hp(ctx).getBoolean("bubble_$tid", true)
    fun setBubble(ctx: Context, tid: Long, on: Boolean) = hp(ctx).edit().putBoolean("bubble_$tid", on).apply()

    fun set(ctx: Context, tid: Long, tone: String? = null, vibrate: Boolean? = null, importance: Int? = null) {
        hp(ctx).edit().apply {
            putBoolean("cn_on_$tid", true)
            if (tone != null) putString("tone_$tid", tone)
            if (vibrate != null) putBoolean("cn_vib_$tid", vibrate)
            if (importance != null) putInt("cn_imp_$tid", importance)
        }.apply()
        channelFor(ctx, tid)
    }

    fun reset(ctx: Context, tid: Long) {
        val old = hp(ctx).getString("cn_id_$tid", null)
        hp(ctx).edit().remove("cn_on_$tid").remove("tone_$tid").remove("cn_vib_$tid").remove("cn_imp_$tid").remove("cn_id_$tid").apply()
        if (old != null && Build.VERSION.SDK_INT >= 26) runCatching { nm(ctx).deleteNotificationChannel(old) }
    }

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The channel for this chat's notifications, created on demand; null = the app's normal channel. */
    fun channelFor(ctx: Context, tid: Long, title: String? = null): String? {
        if (!custom(ctx, tid) || Build.VERSION.SDK_INT < 26) return null
        val tone = tone(ctx, tid); val vib = vibrate(ctx, tid); val imp = importance(ctx, tid)
        val id = "conv_${tid}_" + Integer.toHexString("$tone|$vib|$imp".hashCode())
        return runCatching {
            val nm = nm(ctx)
            if (nm.getNotificationChannel(id) == null) {
                if (nm.getNotificationChannel(PARENT) == null) nm.createNotificationChannel(NotificationChannel(PARENT, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New message notifications"; enableVibration(true) })
                hp(ctx).getString("cn_id_$tid", null)?.takeIf { it != id }?.let { runCatching { nm.deleteNotificationChannel(it) } }
                val name = title ?: hp(ctx).getString("cn_name_$tid", null) ?: "Chat"
                val ch = NotificationChannel(id, name, when (imp) { IMP_PRIORITY -> NotificationManager.IMPORTANCE_HIGH; IMP_DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT; else -> NotificationManager.IMPORTANCE_LOW }).apply {
                    description = "Notifications for this conversation"
                    val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    when {
                        imp == IMP_SILENT || tone.isEmpty() -> setSound(null, null)
                        tone == "default" -> setSound(Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
                        else -> setSound(Uri.parse(tone), attrs)
                    }
                    enableVibration(vib && imp != IMP_SILENT)
                    if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
                    if (Build.VERSION.SDK_INT >= 30) setConversationId(PARENT, "thread_$tid")
                }
                nm.createNotificationChannel(ch)
            }
            hp(ctx).edit().putString("cn_id_$tid", id).apply()
            id
        }.getOrNull()
    }

    /** Reads back what the user changed in Android's own channel page, so the in-app switches show the truth. */
    fun syncFromSystem(ctx: Context, tid: Long) {
        if (Build.VERSION.SDK_INT < 26) return
        val id = hp(ctx).getString("cn_id_$tid", null) ?: return
        val ch = runCatching { nm(ctx).getNotificationChannel(id) }.getOrNull() ?: return
        val imp = when { ch.importance >= NotificationManager.IMPORTANCE_HIGH -> IMP_PRIORITY; ch.importance == NotificationManager.IMPORTANCE_DEFAULT -> IMP_DEFAULT; else -> IMP_SILENT }
        val snd = ch.sound
        val tone = when { snd == null -> if (imp == IMP_SILENT) tone(ctx, tid) else ""; snd == Settings.System.DEFAULT_NOTIFICATION_URI -> "default"; else -> snd.toString() }
        hp(ctx).edit().putInt("cn_imp_$tid", imp).putBoolean("cn_vib_$tid", ch.shouldVibrate() || (imp == IMP_SILENT && vibrate(ctx, tid))).putString("tone_$tid", tone).apply()
        hp(ctx).edit().putString("cn_id_$tid", id).apply()
    }

    fun openSystemSettings(ctx: Context, tid: Long, title: String) {
        hp(ctx).edit().putString("cn_name_$tid", title).apply()
        if (Build.VERSION.SDK_INT < 26) { Toast.makeText(ctx, "Needs Android 8 or newer", Toast.LENGTH_SHORT).show(); return }
        if (!custom(ctx, tid)) set(ctx, tid)
        val id = channelFor(ctx, tid, title) ?: return
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id)
                .apply { if (Build.VERSION.SDK_INT >= 30) putExtra(Settings.EXTRA_CONVERSATION_ID, "thread_$tid") })
        }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) } }
    }

    fun toneName(ctx: Context, tid: Long): String {
        val t = tone(ctx, tid)
        if (importance(ctx, tid) == IMP_SILENT || t.isEmpty()) return "None"
        if (t == "default") return "Default"
        return runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(t))?.getTitle(ctx) }.getOrNull() ?: "Custom"
    }
}

/** A local name and photo for a group chat (SMS/MMS groups have none on the network). */
object GroupInfo {
    fun name(ctx: Context, tid: Long): String? = hp(ctx).getString("gname_$tid", null)?.takeIf { it.isNotBlank() }
    fun setName(ctx: Context, tid: Long, n: String?) = hp(ctx).edit().apply { if (n.isNullOrBlank()) remove("gname_$tid") else putString("gname_$tid", n.trim()) }.apply()
    fun iconUri(ctx: Context, tid: Long): Uri? = hp(ctx).getString("gicon_$tid", null)?.let { java.io.File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }
    fun setIcon(ctx: Context, tid: Long, src: Uri?, address: String): Boolean {
        hp(ctx).getString("gicon_$tid", null)?.let { java.io.File(it).delete() }
        PhotoCache.map.remove(address); PhotoCache.miss.remove(address)
        if (src == null) { hp(ctx).edit().remove("gicon_$tid").apply(); return true }
        return runCatching {
            val bytes = ctx.contentResolver.openInputStream(src)?.use { it.readBytes() } ?: return false
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            val s = 512f / maxOf(bm.width, bm.height)
            val out = if (s < 1f) Bitmap.createScaledBitmap(bm, (bm.width * s).toInt(), (bm.height * s).toInt(), true) else bm
            val dir = java.io.File(ctx.filesDir, "group_icons").apply { mkdirs() }
            val f = java.io.File(dir, "${tid}_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            hp(ctx).edit().putString("gicon_$tid", f.absolutePath).apply()
            PhotoCache.map[address] = Uri.fromFile(f)
            true
        }.getOrDefault(false)
    }
}

/** The SIM a conversation sends from (dual-SIM phones); -1 = the app/phone default. */
object ConvSim {
    private fun key(address: String) = "csim_" + SmsRepository.splitRecipients(address).map { it.replace(Regex("[^0-9+]"), "").takeLast(10) }.sorted().joinToString(",")
    fun get(ctx: Context, address: String): Int = runCatching { hp(ctx).getInt(key(address), -1) }.getOrDefault(-1)
    fun set(ctx: Context, address: String, sub: Int) = hp(ctx).edit().apply { if (sub < 0) remove(key(address)) else putInt(key(address), sub) }.apply()
    fun sims(ctx: Context): List<android.telephony.SubscriptionInfo> = runCatching {
        (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as android.telephony.SubscriptionManager).activeSubscriptionInfoList ?: emptyList()
    }.getOrDefault(emptyList())
}

/** Blocking writes to the app list and, as default SMS app, to Android's system block list (BlockedNumberContract). */
object Blocker {
    private fun canSystem(ctx: Context) = Build.VERSION.SDK_INT >= 24 && runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(ctx) }.getOrDefault(false)
    fun isBlocked(ctx: Context, address: String): Boolean = BlockedStore.isBlocked(ctx, address) ||
        (canSystem(ctx) && runCatching { BlockedNumberContract.isBlocked(ctx, address) }.getOrDefault(false))
    fun block(ctx: Context, address: String) {
        BlockedStore.block(ctx, address)
        if (canSystem(ctx)) runCatching { ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, address) }) }
    }
    fun unblock(ctx: Context, address: String) {
        BlockedStore.getAll(ctx).filter { it.replace(Regex("[^+0-9]"), "") == address.replace(Regex("[^+0-9]"), "") }.forEach { BlockedStore.unblock(ctx, it) }
        BlockedStore.unblock(ctx, address)
        if (canSystem(ctx)) runCatching { BlockedNumberContract.unblock(ctx, address) }
    }
}

internal fun openChatWith(ctx: Context, numbers: List<String>) {
    val list = numbers.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.replace(Regex("[^0-9+]"), "").takeLast(10) }
    if (list.isEmpty()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(list.joinToString(";")))).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
}

private fun contactLookup(ctx: Context, number: String): Uri? = runCatching {
    val lu = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
    ctx.contentResolver.query(lu, arrayOf(ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY), null, null, null)?.use { c ->
        if (c.moveToFirst()) ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)) else null
    }
}.getOrNull()

internal fun openContact(ctx: Context, number: String) {
    val u = contactLookup(ctx, number)
    runCatching {
        if (u != null) ctx.startActivity(Intent(Intent.ACTION_VIEW, u))
        else ctx.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).putExtra(ContactsContract.Intents.Insert.PHONE, number))
    }.onFailure { Toast.makeText(ctx, "No contacts app found", Toast.LENGTH_SHORT).show() }
}

internal fun snoozeLabel(ctx: Context, tid: Long): String? {
    val p = hp(ctx)
    if (p.getBoolean("muted_$tid", false)) return "Notifications are off"
    val until = p.getLong("muted_until_$tid", 0L)
    if (until > System.currentTimeMillis()) return "Snoozed until " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(until))
    return null
}
