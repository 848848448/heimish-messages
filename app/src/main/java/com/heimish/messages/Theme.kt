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


// ━━━━━━━━━━━━ THEME — Google Messages / WhatsApp / Telegram grade ━━━━━━━━━
object ThemeState {
    var dark by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    /** Material You colors from the wallpaper (Android 12+), or null for the classic Google Messages blue. */
    var scheme by mutableStateOf<ColorScheme?>(null)
    var amoled by mutableStateOf(false)
    /** Per-chat theme accent (Chat theme), set while that chat is open. */
    var chat by mutableStateOf<ChatPalette?>(null)
}
internal val D get() = ThemeState.dark
private val S get() = ThemeState.scheme
private val CP get() = ThemeState.chat
private val Black get() = D && ThemeState.amoled

internal val Brand: Color        get() = CP?.primary ?: S?.primary ?: if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
internal val BrandDk: Color      get() = S?.let { if (D) it.inversePrimary else it.onPrimaryContainer } ?: if (D) Color(0xFF7CACF8) else Color(0xFF0842A0)
internal val OnBrand: Color      get() = S?.onPrimary ?: if (D) Color(0xFF003258) else Color.White
internal val Tonal: Color        get() = CP?.container ?: S?.primaryContainer ?: if (D) Color(0xFF004A77) else Color(0xFFD3E3FD)
internal val OnTonal: Color      get() = CP?.onContainer ?: S?.onPrimaryContainer ?: if (D) Color(0xFFC2E7FF) else Color(0xFF001D35)
internal val Bg: Color           get() = if (Black) Color.Black else S?.surface ?: if (D) Color(0xFF131314) else Color.White
internal val Surf: Color         get() = if (Black) Color(0xFF101010) else S?.surfaceContainer ?: if (D) Color(0xFF1E1F20) else Color.White
internal val SearchBg: Color     get() = if (Black) Color(0xFF1C1C1E) else S?.surfaceContainerHigh ?: if (D) Color(0xFF2F3033) else Color(0xFFF0F4F9)
internal val SheetBg: Color      get() = if (Black) Color(0xFF101010) else S?.surfaceContainerLow ?: if (D) Color(0xFF1E1F20) else Color(0xFFF8FAFD)
internal val BubbleIn: Color     get() = if (Black) Color(0xFF262628) else S?.surfaceContainerHighest ?: if (D) Color(0xFF444746) else Color(0xFFE9EEF6)
// Google Messages (2025): your messages are a soft blue with dark text, theirs are grey
internal val BubbleOut: Color    get() = CP?.container ?: S?.primaryContainer ?: if (D) Color(0xFF004A77) else Color(0xFFD3E3FD)
internal val BubbleOutText: Color  get() = CP?.onContainer ?: S?.onPrimaryContainer ?: if (D) Color(0xFFC2E7FF) else Color(0xFF041E49)
internal val BubbleOutSub: Color   get() = CP?.onContainer?.copy(.68f) ?: (S?.onPrimaryContainer ?: if (D) Color(0xFFC2E7FF) else Color(0xFF041E49)).copy(.68f)
internal val BubbleOutLink: Color  get() = CP?.primary ?: S?.primary ?: if (D) Color(0xFFA8C7FA) else Color(0xFF0B57D0)
/** The tinted frame around the main lists (Google Messages' Material 3 Expressive look). */
internal val Chrome: Color       get() = if (Black) Color.Black else S?.surfaceContainer ?: if (D) Color(0xFF1E1F20) else Color(0xFFF0F4F9)
internal val TextPrimary: Color  get() = S?.onSurface ?: if (D) Color(0xFFE3E3E3) else Color(0xFF1F1F1F)
internal val TextSecond: Color   get() = S?.onSurfaceVariant ?: if (D) Color(0xFFC4C7C5) else Color(0xFF444746)
internal val TextHint: Color     get() = S?.outline ?: if (D) Color(0xFF8E918F) else Color(0xFF747775)
internal val DivLt: Color        get() = if (Black) Color(0xFF222224) else S?.outlineVariant ?: if (D) Color(0xFF2F3033) else Color(0xFFE8EAED)
internal val AttachSurf: Color   get() = if (Black) Color(0xFF1C1C1E) else S?.surfaceContainerHighest ?: if (D) Color(0xFF2D2F31) else Color(0xFFE9EEF6)
internal val MenuSurf: Color     get() = if (Black) Color(0xFF1C1C1E) else S?.surfaceContainerHigh ?: if (D) Color(0xFF282A2C) else Color(0xFFF0F4F9)
internal val FabColor: Color     get() = S?.primaryContainer ?: if (D) Color(0xFF004A77) else Color(0xFFC2E7FF)
internal val OnFabColor: Color   get() = S?.onPrimaryContainer ?: if (D) Color(0xFFC2E7FF) else Color(0xFF001D35)
internal val TileBg: Color       get() = if (Black) Color(0xFF1C1C1E) else S?.surfaceContainerHighest ?: if (D) Color(0xFF333537) else Color(0xFFE4E9F2)
internal val Red: Color          get() = S?.error ?: if (D) Color(0xFFF2B8B5) else Color(0xFFB3261E)
internal val Amber: Color        get() = Color(0xFFFFC107)

internal val GMColors: ColorScheme
    @Composable get() {
        val base = S
        if (base != null) return if (Black) base.copy(background = Color.Black, surface = Color.Black, surfaceContainer = Surf, surfaceContainerLow = SheetBg, surfaceContainerHigh = SearchBg, surfaceContainerHighest = AttachSurf) else base
        return if (ThemeState.dark) darkColorScheme(
            primary = Brand, onPrimary = OnBrand, secondary = Brand, primaryContainer = Tonal, onPrimaryContainer = OnTonal,
            background = Bg, surface = Bg, onSurface = TextPrimary, surfaceContainer = Surf, surfaceContainerHigh = SearchBg, surfaceContainerHighest = AttachSurf,
            surfaceVariant = SearchBg, onSurfaceVariant = TextSecond,
            outline = TextHint, outlineVariant = DivLt
        ) else lightColorScheme(
            primary = Brand, onPrimary = OnBrand, secondary = Brand, primaryContainer = Tonal, onPrimaryContainer = OnTonal,
            background = Bg, surface = Bg, onSurface = TextPrimary, surfaceContainer = Surf, surfaceContainerHigh = SearchBg, surfaceContainerHighest = AttachSurf,
            surfaceVariant = SearchBg, onSurfaceVariant = TextSecond,
            outline = TextHint, outlineVariant = DivLt
        )
    }

/** Rubik: clean, modern, and draws Yiddish / Hebrew letters beautifully. Settings > Theme can switch back to the system font. */
internal val Rubik = androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.font.Font(R.font.rubik_regular, androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.font.Font(R.font.rubik_medium, androidx.compose.ui.text.font.FontWeight.Medium),
    androidx.compose.ui.text.font.Font(R.font.rubik_bold, androidx.compose.ui.text.font.FontWeight.Bold),
)

object FontState { var family by mutableStateOf<androidx.compose.ui.text.font.FontFamily>(Rubik) }

internal fun appTypography(ff: androidx.compose.ui.text.font.FontFamily): Typography {
    val b = Typography()
    fun androidx.compose.ui.text.TextStyle.f() = copy(fontFamily = ff)
    return Typography(
        displayLarge = b.displayLarge.f(), displayMedium = b.displayMedium.f(), displaySmall = b.displaySmall.f(),
        headlineLarge = b.headlineLarge.f(), headlineMedium = b.headlineMedium.f(), headlineSmall = b.headlineSmall.f(),
        titleLarge = b.titleLarge.f().copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), titleMedium = b.titleMedium.f(), titleSmall = b.titleSmall.f(),
        bodyLarge = b.bodyLarge.f(), bodyMedium = b.bodyMedium.f(), bodySmall = b.bodySmall.f(),
        labelLarge = b.labelLarge.f(), labelMedium = b.labelMedium.f(), labelSmall = b.labelSmall.f(),
    )
}
