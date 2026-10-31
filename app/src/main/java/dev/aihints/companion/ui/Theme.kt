package dev.aihints.companion.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

private val Blue = Color(0xFF1F6FEB)
private val BlueDark = Color(0xFF0B3D91)
private val Teal = Color(0xFF0F766E)
private val Amber = Color(0xFFB45309)

private val Light = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF06214D),
    secondary = Teal,
    tertiary = Amber,
    background = Color(0xFFFDFBFF),
    surface = Color(0xFFFDFBFF),
    surfaceVariant = Color(0xFFE7E9EF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CC3FF),
    onPrimary = Color(0xFF002F63),
    primaryContainer = BlueDark,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF7FD8CE),
    tertiary = Color(0xFFFFB86B),
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF2A3036),
)

@Composable
fun AiHintsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) Dark else Light
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = scheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
        ),
        content = content,
    )
}
