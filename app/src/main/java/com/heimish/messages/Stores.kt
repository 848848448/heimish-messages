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


// ━━━━━━━━━━━━ LOCAL STORES (prefs-backed) ━━━━━━━━━━━━
internal fun msgKey(m: Message) = (if (m.isMms) "m" else "s") + m.id

object PinStore {
    private const val KEY = "pinned_threads"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isPinned(ctx: Context, threadId: Long) = set(ctx).getStringSet(KEY, emptySet())?.contains(threadId.toString()) == true
    fun toggle(ctx: Context, threadId: Long) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val k = threadId.toString()
        if (!s.add(k)) s.remove(k)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object ArchiveStore {
    private const val KEY = "archived_threads"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isArchived(ctx: Context, threadId: Long) = set(ctx).getStringSet(KEY, emptySet())?.contains(threadId.toString()) == true
    fun setArchived(ctx: Context, threadId: Long, archived: Boolean) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        val k = threadId.toString()
        if (archived) s.add(k) else s.remove(k)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object StarStore {
    private const val KEY = "starred_messages"
    private fun set(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun isStarred(ctx: Context, key: String) = set(ctx).getStringSet(KEY, emptySet())?.contains(key) == true
    fun toggle(ctx: Context, key: String) {
        val s = set(ctx).getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!s.add(key)) s.remove(key)
        set(ctx).edit().putStringSet(KEY, s).apply()
    }
}

object ReactionStore {
    private const val KEY = "reactions"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
    fun get(ctx: Context, key: String): String? = prefs(ctx).getString(KEY + "_" + key, null)
    fun set(ctx: Context, key: String, emoji: String?) {
        val e = prefs(ctx).edit()
        if (emoji == null) e.remove(KEY + "_" + key) else e.putString(KEY + "_" + key, emoji)
        e.apply()
    }
}

