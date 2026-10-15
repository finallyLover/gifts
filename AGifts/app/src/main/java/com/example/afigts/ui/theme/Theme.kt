package com.example.afigts.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================
// 亮色方案（与电脑端 Element Plus 风格对齐）
// ============================================================
private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF002145),

    secondary = Color(0xFF606266),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Color(0xFFE3E6EB),
    onSecondaryContainer = Color(0xFF1C1D21),

    tertiary = Color(0xFF909399),
    onTertiary = androidx.compose.ui.graphics.Color.White,

    background = AppBackground,
    onBackground = Color(0xFF303133),

    surface = HeaderBackground,
    onSurface = Color(0xFF303133),
    surfaceVariant = Color(0xFFF5F7FA),
    onSurfaceVariant = Color(0xFF606266),

    error = DangerRed,
    onError = androidx.compose.ui.graphics.Color.White
)

// ============================================================
// 暗色方案（深侧栏风格）
// ============================================================
private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = Color(0xFF002145),
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = Color(0xFFD1E4FF),

    secondary = Color(0xFFC0C4CC),
    onSecondary = Color(0xFF2C2F36),
    secondaryContainer = Color(0xFF43474E),
    onSecondaryContainer = Color(0xFFE3E6EB),

    tertiary = Color(0xFF909399),
    onTertiary = Color(0xFF2C2F36),

    background = SidebarDark,
    onBackground = Color(0xFFE3E6EB),

    surface = Color(0xFF2B2F38),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF3A3F4B),
    onSurfaceVariant = SidebarText,

    error = DangerRed,
    onError = androidx.compose.ui.graphics.Color.White
)

@Composable
fun AFigtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 关闭 dynamicColor，确保所有设备上风格统一（与电脑端一致）
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // 状态栏颜色适配
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
