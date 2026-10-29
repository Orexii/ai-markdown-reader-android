package com.orexii.aimarkdownreader.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import com.orexii.aimarkdownreader.data.ThemeChoice

// Wagyu's colours, from the icon: navy page, Shiba red and harness yellow.
private val Navy = Color(0xFF1C2742)
private val Shiba = Color(0xFFD4813D)
private val Harness = Color(0xFFF4C21A)

private val Light = lightColorScheme(primary = Color(0xFF8A4A12), secondary = Navy, tertiary = Color(0xFF7A5B00), secondaryContainer = Color(0xFFFFE8D2))
private val Dark = darkColorScheme(primary = Shiba, secondary = Color(0xFFB8C4E6), tertiary = Harness, background = Color(0xFF121417), surface = Color(0xFF121417))

@Composable
fun isDark(theme: ThemeChoice): Boolean = when (theme) {
    ThemeChoice.SYSTEM -> isSystemInDarkTheme()
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReaderTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    val dark = isDark(theme)
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors) {
        // Test tags double as view ids, so UI tests (UiAutomator) can find the controls.
        Box(Modifier.semantics { testTagsAsResourceId = true }) { content() }
    }
}
