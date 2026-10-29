package com.s9encryptor.strongtoastextended.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle as NativeFontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s9encryptor.strongtoastextended.app.model.*
import java.util.Locale

internal fun pretty(value: Enum<*>): String = value.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
internal fun timeLabel(millis: Long): String {
    val seconds = ((millis.coerceAtLeast(0L) + 999L) / 1000L)
    return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
}
internal fun durationLabel(seconds: Int): String = when {
    seconds < 60 -> "$seconds sec"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60}m ${seconds % 60}s"
}
internal fun previewColor(hex: String): Color = runCatching { Color(InputValidator.colorArgb(hex)) }.getOrDefault(Neon)
internal fun timerColor(fraction: Float): Color = when {
    fraction > .40f -> Neon
    fraction > .33f -> androidx.compose.ui.graphics.lerp(Neon, Color(0xFFFFA632), ((.40f - fraction) / .07f).coerceIn(0f, 1f))
    fraction > .21f -> Color(0xFFFFA632)
    fraction > .15f -> androidx.compose.ui.graphics.lerp(Color(0xFFFFA632), Color(0xFFFF5149), ((.21f - fraction) / .06f).coerceIn(0f, 1f))
    else -> Color(0xFFFF5149)
}

@Composable
internal fun GlassCard(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = colors.surface, border = BorderStroke(1.dp, if (accent) colors.primary.copy(alpha = .25f) else colors.outlineVariant.copy(alpha = .65f)),
    ) {
        Column(
            Modifier.background(Brush.linearGradient(listOf(if (accent) colors.primary.copy(alpha = .065f) else colors.surfaceVariant.copy(alpha = .20f), Color.Transparent))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), content = content,
        )
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(Locale.ROOT), modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun StatusPill(label: String, positive: Boolean = false, modifier: Modifier = Modifier) {
    val tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = .10f)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(tint))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, letterSpacing = .4.sp)
    }
}

@Composable
internal fun ToastPreview(text: String, colorHex: String, style: FontStyle, rainbow: Boolean, reducedMotion: Boolean, icon: IconChoice, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val animatedPhase = if (rainbow && !reducedMotion) {
        val animation = rememberInfiniteTransition(label = "rainbow preview")
        val phase by animation.animateFloat(0f, 1f, infiniteRepeatable(tween(6200, easing = LinearEasing), RepeatMode.Restart), label = "color movement")
        phase
    } else 0f
    val gradient = Brush.linearGradient(Rainbow, start = Offset(animatedPhase * 600f - 300f, 0f), end = Offset(animatedPhase * 600f + 400f, 0f))
    Box(modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(20.dp)).background(Brush.radialGradient(listOf(primary.copy(alpha = .07f), Color.Transparent), radius = 550f)).padding(vertical = 25.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(17.dp), color = Color(0xFF040705), border = BorderStroke(1.dp, Color.White.copy(alpha = .09f)), shadowElevation = 10.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.heightIn(min = 58.dp).padding(start = 16.dp, end = 11.dp, top = 13.dp, bottom = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val textModifier = Modifier.weight(1f).then(if (!reducedMotion) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
                val baseStyle = TextStyle(fontSize = 16.sp, fontWeight = if (style == FontStyle.BOLD) FontWeight.Bold else FontWeight.Normal, fontStyle = if (style == FontStyle.ITALIC) NativeFontStyle.Italic else NativeFontStyle.Normal)
                val textStyle = if (rainbow) baseStyle.copy(brush = gradient) else baseStyle.copy(color = previewColor(colorHex))
                Text(text.ifEmpty { "Your next status" }, modifier = textModifier, style = textStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = .08f)), contentAlignment = Alignment.Center) {
                    Icon(if (icon == IconChoice.HYPERCEILER) Icons.Outlined.AutoAwesome else Icons.Outlined.Bolt, contentDescription = "${pretty(icon)} preview icon", tint = Color(0xFFE8F4E6), modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

@Composable
internal fun PrimaryAction(text: String, icon: ImageVector, enabled: Boolean = true, busy: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = modifier.heightIn(min = 50.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp)); Text(text)
    }
}

@Composable
internal fun SecondaryAction(text: String, icon: ImageVector, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(15.dp)) {
        Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(7.dp)); Text(text)
    }
}

@Composable
internal fun FontSelector(style: FontStyle, onChange: (FontStyle) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FontStyle.entries.forEach { item ->
            FilterChip(selected = style == item, onClick = { onChange(item) }, label = { Text(pretty(item), fontWeight = if (item == FontStyle.BOLD) FontWeight.Bold else FontWeight.Medium, fontStyle = if (item == FontStyle.ITALIC) NativeFontStyle.Italic else NativeFontStyle.Normal) }, modifier = Modifier.weight(1f), leadingIcon = if (style == item) ({ Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

@Composable
internal fun DurationInput(totalSeconds: Int, onChange: (Int) -> Unit) {
    var minutes by remember(totalSeconds / 60) { mutableStateOf((totalSeconds / 60).toString()) }
    var seconds by remember(totalSeconds % 60) { mutableStateOf((totalSeconds % 60).toString()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(minutes, { value ->
            if (value.length <= 2 && value.all(Char::isDigit)) {
                val m = (value.toIntOrNull() ?: 0).coerceIn(0, 60)
                minutes = if (value.isEmpty()) "" else m.toString()
                val otherSeconds = if (m == 60) 0 else (seconds.toIntOrNull() ?: 0).coerceIn(0, 59)
                if (m == 60) seconds = "0"
                onChange(m * 60 + otherSeconds)
            }
        }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
        OutlinedTextField(seconds, { value ->
            if (value.length <= 2 && value.all(Char::isDigit)) {
                val m = (minutes.toIntOrNull() ?: 0).coerceIn(0, 60)
                val s = if (m == 60) 0 else (value.toIntOrNull() ?: 0).coerceIn(0, 59)
                seconds = if (value.isEmpty() && m < 60) "" else s.toString()
                onChange(m * 60 + s)
            }
        }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Seconds") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
    }
    Text("Up to 60 minutes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ColorInput(value: String, onChange: (String) -> Unit, label: String = "HEX color") {
    val invalid = runCatching { InputValidator.normalizeHex(value) }.isFailure
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, leadingIcon = { Box(Modifier.size(20.dp).clip(CircleShape).background(previewColor(value))) }, singleLine = true, isError = invalid, supportingText = { Text(if (invalid) "Use 6 or 8 HEX digits, with or without #" else "${runCatching { InputValidator.normalizeHex(value) }.getOrDefault(value)} · custom color") }, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("#0BFC03", "#FFFFFF", "#44D9FF", "#A98CFF", "#FF6CB0", "#FF5149", "#FFA632").forEach { hex ->
            val selected = runCatching { InputValidator.normalizeHex(value) }.getOrNull() == hex
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { onChange(hex) }.semantics { contentDescription = "Select color $hex" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(previewColor(hex)), contentAlignment = Alignment.Center) {
                    if (selected) Icon(Icons.Outlined.Check, null, tint = Color(0xFF061E0B), modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

@Composable
internal fun IconSelector(selected: IconChoice, onChange: (IconChoice) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconChoice.entries.forEach { icon ->
            val active = selected == icon
            Surface(modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onChange(icon) }, color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (icon == IconChoice.HYPERCEILER) Icons.Outlined.AutoAwesome else Icons.Outlined.Bolt, null, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (icon == IconChoice.HYPERCEILER) "HyperCeiler" else "StrongToast", style = MaterialTheme.typography.labelLarge)
                    Text(if (icon == IconChoice.HYPERCEILER) "Custom drawable" else "Built-in icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.sp)
                }
            }
        }
    }
    Text("The built-in icon is used if the selected package or drawable is unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ToggleRow(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, icon: ImageVector? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}
