package com.edgeai.assistant

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The brand palette: bright and friendly, the same on every phone (wallpaper colours came out too muted). */
object Brand {
    val violet = Color(0xFF6C4CF1)
    val blue = Color(0xFF3D7BFF)
    val teal = Color(0xFF00BFA6)
    val pink = Color(0xFFFF5C8A)
    val amber = Color(0xFFFFB547)
    val coral = Color(0xFFFF7A59)

    val primaryGradient = Brush.linearGradient(listOf(violet, blue))
    val warmGradient = Brush.linearGradient(listOf(pink, coral, amber))
    val coolGradient = Brush.linearGradient(listOf(blue, teal))

    /** Soft tints for chips, cards and avatars, cycled so neighbours differ. */
    private val lightTints = listOf(Color(0xFFEDE7FF), Color(0xFFE0F2FF), Color(0xFFDDF7F1), Color(0xFFFFE4EC), Color(0xFFFFF1D6), Color(0xFFFFE6DE))
    private val darkTints = listOf(Color(0xFF2E2560), Color(0xFF1B3157), Color(0xFF0F3D37), Color(0xFF4A1F2E), Color(0xFF4A3A16), Color(0xFF4A2A20))
    private val accents = listOf(violet, blue, teal, pink, amber, coral)

    fun tint(i: Int, dark: Boolean) = (if (dark) darkTints else lightTints)[Math.floorMod(i, lightTints.size)]
    fun accent(i: Int) = accents[Math.floorMod(i, accents.size)]
}

private val Light = lightColorScheme(
    primary = Brand.violet, onPrimary = Color.White, primaryContainer = Color(0xFFEDE7FF), onPrimaryContainer = Color(0xFF21005D),
    secondary = Brand.teal, onSecondary = Color.White, secondaryContainer = Color(0xFFCCF5EC), onSecondaryContainer = Color(0xFF00382F),
    tertiary = Brand.pink, onTertiary = Color.White, tertiaryContainer = Color(0xFFFFD9E2), onTertiaryContainer = Color(0xFF3E001D),
    background = Color(0xFFFBF9FF), surface = Color(0xFFFBF9FF), surfaceContainerHigh = Color(0xFFF0ECFA), surfaceContainer = Color(0xFFF4F1FC),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB9A6FF), onPrimary = Color(0xFF2A0F87), primaryContainer = Color(0xFF4A32B8), onPrimaryContainer = Color(0xFFEDE7FF),
    secondary = Color(0xFF5FE0C8), secondaryContainer = Color(0xFF00504A), tertiary = Color(0xFFFF8FAF), tertiaryContainer = Color(0xFF7A1F40),
    background = Color(0xFF121019), surface = Color(0xFF121019), surfaceContainerHigh = Color(0xFF26222F), surfaceContainer = Color(0xFF1D1A25),
)

@Composable
fun EdgeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) Dark else Light
    // The window's own theme is the platform default; paint the app's background so text always has contrast.
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** Status colours that keep their meaning in light and dark. */
object StatusColors {
    val good = Color(0xFF1E9E5A)
    val goodDark = Color(0xFF7FE0A8)
}

/** An emoji for what a function does, for reply avatars. */
fun emojiFor(function: String?): String = when (function) {
    "set_alarm", "dismiss_alarm" -> "⏰"
    "set_timer", "show_timers" -> "⏲️"
    "create_reminder" -> "📝"
    "call_contact" -> "📞"
    "send_message", "message_app" -> "💬"
    "set_flashlight" -> "🔦"
    "set_volume" -> "🔊"
    "set_do_not_disturb" -> "🌙"
    "media_control", "play_media" -> "🎵"
    "open_app" -> "📱"
    "web_search" -> "🔍"
    "navigate" -> "🧭"
    "search_maps" -> "📍"
    "camera" -> "📸"
    "search_app_store" -> "🛍️"
    else -> "✨"
}
