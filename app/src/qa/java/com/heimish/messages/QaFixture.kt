package com.heimish.messages

// QA-only fixture screen (no launcher entry, not exported): renders REAL conversation
// rows with synthetic models so layout behavior - two-line group titles, long RTL
// names, unread/timestamp alignment, large fonts - can be verified on an emulator
// without writing anything to the SMS/MMS provider. Launched by the QA harness via
// `adb shell am start -n com.heimish.messages/.QaFixtureActivity`. Never reads or
// shows real messages.

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

class QaFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val ctx = LocalContext.current
            val prefs = ctx.getSharedPreferences("heimish_prefs", Context.MODE_PRIVATE)
            // Same theme path as AppRoot so the rows render with production colors and type
            ThemeState.dark = when (prefs.getString("theme", "system")) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            val useDynamic = prefs.getBoolean("dynamic_color", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ThemeState.amoled = prefs.getBoolean("amoled", false)
            ThemeState.scheme = if (useDynamic) (if (ThemeState.dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else null
            val fsc = prefs.getFloat("font_scale", 0f)
            val dens = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides (if (fsc > 0f) androidx.compose.ui.unit.Density(dens.density, fsc) else dens)) {
                FontState.family = if (prefs.getString("font", "google") == "system") androidx.compose.ui.text.font.FontFamily.Default else GoogleSans
                MaterialTheme(colorScheme = GMColors, typography = remember(FontState.family) { appTypography(FontState.family) }) {
                    Surface(Modifier.fillMaxSize(), color = Bg) {
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            Column(Modifier.fillMaxSize()) {
                                Text("QA list fixture", color = TextSecond, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                                fixtureRows().forEach { c ->
                                    SwipeableConvRow(c, pinned = false, onOpen = {}, onRefresh = {}, onLongPress = {})
                                    HorizontalDivider(thickness = .5.dp, color = DivLt)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun fixtureRows(): List<Conversation> {
    val now = System.currentTimeMillis()
    return listOf(
        // One-line control: baseline title height for the wrap comparison
        Conversation(9001L, "5551000001", "Duvid Katz", "On my way, see you soon", now, unread = false),
        // Long English group title: must wrap to two lines, timestamp and unread pill keep their places
        Conversation(9002L, "5551000002;5551000003;5551000004", "Yankel Friedman, Shmiel Berkovitch, Chaim Yosef Weinberger, Moishe Dovid Katz", "See you at the kiddush after davening", now, unread = true, unreadCount = 12),
        // Long RTL group title: same wrap under bidi layout
        Conversation(9003L, "5551000005;5551000006", "שמואל בערגר, חנה לאה לאָווין, משה כּץ, רבקה גולדשטיין", "נעמען די קינדער מיט", now, unread = false),
        // Long RTL group title with unread state
        Conversation(9004L, "5551000007;5551000008;5551000009", "אברהם יצחק הכהן גרויסמאן, שרה לאה בלומענפעלד", "גוט שבע", now, unread = true, unreadCount = 3)
    )
}
