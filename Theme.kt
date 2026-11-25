package com.vantora.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vantora.app.device.Advice
import com.vantora.app.device.Severity

/** Vantora design system: deep indigo canvas, violet primary, cyan accent, system sans-serif (Roboto) type scale. */
object VColors {
    val Bg = Color(0xFF0E1020)
    val Surface = Color(0xFF171A30)
    val SurfaceHi = Color(0xFF20244A)
    val Violet = Color(0xFF7C5CFF)
    val Cyan = Color(0xFF22D3EE)
    val Ok = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
    val Muted = Color(0xFF9AA0C3)
}

private val scheme = darkColorScheme(
    primary = VColors.Violet, secondary = VColors.Cyan, tertiary = VColors.Cyan,
    background = VColors.Bg, surface = VColors.Surface, surfaceVariant = VColors.SurfaceHi,
    onPrimary = Color.White, onBackground = Color(0xFFEDEEFF), onSurface = Color(0xFFEDEEFF),
    onSurfaceVariant = VColors.Muted, error = VColors.Bad
)

private val type = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp)
)

@Composable
fun VantoraTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, shapes = Shapes(medium = RoundedCornerShape(16.dp)), content = content)

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = VColors.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = VColors.Cyan)
            content()
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = VColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
        Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun AdviceRow(a: Advice) {
    val c = when (a.severity) { Severity.INFO -> VColors.Ok; Severity.WARNING -> VColors.Warn; Severity.BLOCKER -> VColors.Bad }
    val tag = when (a.severity) { Severity.INFO -> "OK"; Severity.WARNING -> "WARNING"; Severity.BLOCKER -> "BLOCKER" }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(tag, c)
        Text(a.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Empty(text: String) = Box(Modifier.fillMaxWidth().padding(24.dp)) { Text(text, color = VColors.Muted) }

fun formatBytes(b: Long): String = when {
    b < 0 -> "?"
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    else -> "${b / 1024} KB"
}

@Composable
fun Modifier.verticalScrollCompat(): Modifier =
    this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Surface(color = VColors.Bg, shape = RoundedCornerShape(size / 4), modifier = modifier.size(size)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.vantora.app.R.drawable.ic_logo_foreground), null,
            Modifier.fillMaxSize()
        )
    }
}
