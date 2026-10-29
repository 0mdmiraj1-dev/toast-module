package com.s9encryptor.strongtoastextended.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.app.state.StrongToastViewModel

@Composable
internal fun ScreenList(content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.testTag("screen-list").widthIn(max = 840.dp).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
    }
}

@Composable
internal fun PageHeading(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun DashboardScreen(state: UiState, model: StrongToastViewModel, onEditor: () -> Unit) {
    val module = state.module
    val acknowledged = module.connected && module.active
    val remaining = module.remainingMillis(state.nowElapsed)
    val text = if (acknowledged) { if (module.alerting) "Time over!" else if (module.mode == ToastMode.TIMER) "Remaining ${timeLabel(remaining)}" else module.text } else state.draft.text
    val rainbow = if (acknowledged) module.mode == ToastMode.RGB else state.draft.rainbow
    val lifecycleLabel = when {
        !module.connected -> "Unverified"
        !module.active -> "Inactive"
        !module.visibilityKnown -> "Visibility unverified"
        module.visible -> if (module.alerting) "Time over" else "Visible"
        module.panelOpen -> "Panel open"
        else -> "Restoring soon"
    }
    ScreenList {
        item { PageHeading("Make space\nfor your status.", "A little presence. Exactly the way you want it.") }
        item {
            GlassCard(accent = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(if (acknowledged) "Current Strong Toast" else "Live preview", Modifier.weight(1f))
                    StatusPill(lifecycleLabel, module.connected && module.active && module.visibilityKnown && module.visible)
                }
                ToastPreview(text, if (acknowledged) module.colorHex else state.draft.colorHex, if (acknowledged) module.style else state.draft.style, rainbow, state.settings.reducedMotion, if (acknowledged) module.icon else state.draft.icon)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("REMAINING", if (acknowledged) timeLabel(remaining) else "—")
                    Metric("COLOR MODE", if (acknowledged && module.mode == ToastMode.TIMER) "Timer" else if (rainbow) "Rainbow" else "Solid")
                    Metric("STYLE", pretty(if (acknowledged) module.style else state.draft.style))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    PrimaryAction(if (acknowledged) "Update" else "Start", if (acknowledged) Icons.Outlined.Sync else Icons.Outlined.PlayArrow, busy = state.busy, modifier = Modifier.weight(1f)) { if (acknowledged) model.updateStatus() else model.startStatus() }
                    SecondaryAction("Stop", Icons.Outlined.Stop, enabled = state.busy || state.module.active || state.root == RootStatus.GRANTED, modifier = Modifier.weight(1f), onClick = model::stop)
                }
                if (acknowledged && module.panelOpen) Text("Your status is waiting for the panel to close. Its original timer keeps running.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { ConnectionCard(state, model) }
        item {
            SectionTitle("Quick access", "Your recent saved styles", trailing = { TextButton(onClick = onEditor) { Text("Create"); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(15.dp)) } })
        }
        if (state.presets.isEmpty()) item {
            GlassCard {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BookmarkBorder, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text("A style worth keeping", style = MaterialTheme.typography.titleMedium); Text("Save a preset in the editor for one-tap access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SecondaryAction("Open status editor", Icons.Outlined.Tune, modifier = Modifier.fillMaxWidth(), onClick = onEditor)
            }
        }
        else items(state.presets.take(3), key = { "quick-${it.id}" }) { preset ->
            PresetQuickCard(preset, state.busy) { model.activatePreset(preset) }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Eyebrow(label)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun ConnectionCard(state: UiState, model: StrongToastViewModel) {
    val module = state.module
    GlassCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HealthItem("Root", when (state.root) { RootStatus.GRANTED -> "Granted"; RootStatus.REQUESTING -> "Waiting"; RootStatus.UNAVAILABLE -> "Unavailable"; RootStatus.UNKNOWN -> "Unknown"; else -> "Not granted" }, state.root == RootStatus.GRANTED)
            HealthItem("LSPosed", if (module.connected) { if (module.moduleActive) "Active" else "Limited" } else "Unknown", module.connected && module.moduleActive)
            HealthItem("System UI", if (module.connected) "Connected" else "Disconnected", module.connected)
        }
        if (!module.connected) {
            Text("Enable StrongToast Extended for System UI in LSPosed, then restart your device and refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!module.nativeReady) {
            Text("Connected to the module. The native toast controller will be prepared when you start a status.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.root != RootStatus.GRANTED) {
            SecondaryAction(if (state.root == RootStatus.REQUESTING) "Waiting for approval" else "Grant root access", Icons.Outlined.VerifiedUser, enabled = !state.busy && state.root != RootStatus.REQUESTING, modifier = Modifier.fillMaxWidth(), onClick = model::grantRoot)
        }
    }
}

@Composable
private fun HealthItem(label: String, value: String, positive: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, letterSpacing = 0.sp)
        }
    }
}

private data class PendingPresetSave(val id: String, val name: String, val draft: ToastDraft)

@Composable
internal fun EditorScreen(state: UiState, model: StrongToastViewModel, editingPreset: Preset? = null, onFinishEdit: () -> Unit = {}) {
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var pendingPresetSave by remember { mutableStateOf<PendingPresetSave?>(null) }
    LaunchedEffect(pendingPresetSave, state.presets, editingPreset?.id) {
        val pending = pendingPresetSave ?: return@LaunchedEffect
        if (editingPreset?.id != pending.id) {
            pendingPresetSave = null
            return@LaunchedEffect
        }
        if (state.presets.any { it.id == pending.id && it.name == pending.name && it.draft == pending.draft }) {
            pendingPresetSave = null
            onFinishEdit()
        }
    }
    val focus = LocalFocusManager.current
    ScreenList {
        item { PageHeading("Status studio", "Words, color and character. Make it yours.") }
        if (editingPreset != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Editing ${editingPreset.name}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = onFinishEdit) { Text("Finish editing") }
            }
        }
        item {
            GlassCard(accent = true) {
                Eyebrow("Live preview")
                ToastPreview(state.draft.text, state.draft.colorHex, state.draft.style, state.draft.rainbow, state.settings.reducedMotion, state.draft.icon)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(durationLabel(state.draft.durationSeconds), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.draft.rainbow) "Animated rainbow" else "Solid color", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Your message")
                OutlinedTextField(state.draft.text, model::updateText, modifier = Modifier.fillMaxWidth(), label = { Text("Status text") }, placeholder = { Text("A thought worth keeping close…") }, minLines = 2, maxLines = 6, shape = RoundedCornerShape(15.dp), supportingText = { Text("Unicode, emojis and long messages supported") })
                FontSelector(state.draft.style, model::updateStyle)
            }
        }
        item {
            GlassCard {
                SectionTitle("Color & motion")
                ToggleRow("Animated rainbow", "Move a spectrum of colors across your words.", state.draft.rainbow, model::updateRainbow, Icons.Outlined.AutoAwesome)
                if (!state.draft.rainbow) ColorInput(state.draft.colorHex, model::updateColor)
                else Text(if (state.settings.reducedMotion) "Reduced motion keeps the preview and effect still." else "The color animation runs inside System UI after starting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { GlassCard { SectionTitle("Duration"); DurationInput(state.draft.durationSeconds, model::updateDuration) } }
        item { GlassCard { SectionTitle("Right-side icon"); IconSelector(state.draft.icon, model::updateIcon) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction(if (state.module.connected && state.module.active) "Update status" else "Start status", Icons.Outlined.PlayArrow, busy = state.busy, modifier = Modifier.weight(1f)) {
                    focus.clearFocus(); if (state.module.connected && state.module.active) model.updateStatus() else model.startStatus()
                }
                IconButton(onClick = { saveDialog = true }, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(15.dp)).background(MaterialTheme.colorScheme.surfaceVariant), enabled = !state.busy) { Icon(Icons.Outlined.BookmarkAdd, if (editingPreset == null) "Save status as preset" else "Save changes to preset", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
    if (saveDialog) NameDialog(if (editingPreset == null) "Save preset" else "Update preset", "Keep this style for next time.", editingPreset?.name.orEmpty(), onDismiss = { saveDialog = false }) { name ->
        val presetId = editingPreset?.id
        pendingPresetSave = if (presetId == null) null else runCatching {
            PendingPresetSave(presetId, InputValidator.normalizePresetName(name), InputValidator.normalizeDraft(state.draft))
        }.getOrNull()
        model.savePreset(name, presetId)
        saveDialog = false
    }
}

@Composable
internal fun TimerScreen(state: UiState, model: StrongToastViewModel) {
    val activeTimer = state.module.connected && state.module.active && state.module.mode == ToastMode.TIMER
    val remaining = if (activeTimer) state.module.remainingMillis(state.nowElapsed) else state.timerMinutes * 60_000L
    val total = (if (activeTimer && state.module.countdownDurationMillis > 0L) state.module.countdownDurationMillis else state.timerMinutes * 60_000L).coerceAtLeast(1000L)
    val fraction = if (activeTimer) (remaining.toFloat() / total).coerceIn(0f, 1f) else 1f
    val tint = if (activeTimer && state.module.alerting) Color(0xFFFF5149) else timerColor(fraction)
    var minutesText by remember(state.timerMinutes) { mutableStateOf(state.timerMinutes.toString()) }
    ScreenList {
        item { PageHeading("Time, in focus.", "A quiet countdown that stays with you.") }
        item {
            GlassCard(accent = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Eyebrow(if (activeTimer) "Live countdown" else "Timer preview", Modifier.weight(1f)); StatusPill(if (activeTimer) { if (state.module.alerting) "Time over" else "Running" } else "Ready", activeTimer) }
                Box(Modifier.fillMaxWidth().height(265.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(232.dp), strokeWidth = 5.dp, color = tint, trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(if (activeTimer && state.module.alerting) Icons.Outlined.NotificationsActive else Icons.Outlined.Timer, null, tint = tint, modifier = Modifier.size(23.dp))
                        Text(if (activeTimer && state.module.alerting) "Time over!" else timeLabel(remaining), style = if (activeTimer && state.module.alerting) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (activeTimer && state.module.panelOpen) "Waiting for the panel" else if (activeTimer) "REMAINING" else "MINUTES : SECONDS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryAction(if (activeTimer) "Restart" else "Start timer", Icons.Outlined.PlayArrow, busy = state.busy, modifier = Modifier.weight(1f), onClick = model::startTimer)
                    SecondaryAction("Stop", Icons.Outlined.Stop, enabled = state.busy || state.module.active || state.root == RootStatus.GRANTED, modifier = Modifier.weight(1f), onClick = model::stop)
                }
                TextButton(onClick = model::resetTimer, enabled = !state.busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Outlined.RestartAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Reset timer") }
            }
        }
        item {
            GlassCard {
                SectionTitle("Set your time", "1–60 minutes")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(minutesText, { s -> if (s.length <= 2 && s.all(Char::isDigit)) { minutesText = s; s.toIntOrNull()?.let { model.setTimerMinutes(it.coerceIn(1, 60)) } } }, modifier = Modifier.width(112.dp), label = { Text("Minutes") }, singleLine = true, shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Slider(state.timerMinutes.toFloat(), { model.setTimerMinutes(it.toInt().coerceIn(1, 60)) }, valueRange = 1f..60f, steps = 58, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 15, 30).forEach { minutes -> FilterChip(selected = state.timerMinutes == minutes, onClick = { model.setTimerMinutes(minutes) }, label = { Text("${minutes}m") }, modifier = Modifier.weight(1f)) }
                }
                FontSelector(state.draft.style, model::updateStyle)
            }
        }
        item {
            GlassCard {
                SectionTitle("At the finish line", "“Time over!” stays visible for 30 seconds.")
                ToggleRow("Vibration alert", "Request up to 30 seconds of vibration. Device sound and vibration rules apply.", state.settings.vibration, { model.updateSettings(state.settings.copy(vibration = it)) }, Icons.Outlined.Vibration, enabled = state.loaded)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("A little shake", "A short movement when the countdown ends.", state.settings.shake, { model.updateSettings(state.settings.copy(shake = it)) }, Icons.Outlined.NotificationsActive, enabled = state.loaded)
            }
        }
    }
}
