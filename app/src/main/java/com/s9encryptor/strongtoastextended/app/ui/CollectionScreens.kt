package com.s9encryptor.strongtoastextended.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.app.state.StrongToastViewModel

@Composable
internal fun NameDialog(title: String, description: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    val valid = runCatching { InputValidator.normalizePresetName(name) }.isSuccess
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.BookmarkAdd, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Up to 64 characters") }, shape = RoundedCornerShape(14.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun PresetQuickCard(preset: Preset, busy: Boolean, onActivate: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(previewColor(preset.draft.colorHex).copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                Icon(if (preset.draft.rainbow) Icons.Outlined.AutoAwesome else Icons.Outlined.ChatBubbleOutline, null, tint = if (preset.draft.rainbow) MaterialTheme.colorScheme.primary else previewColor(preset.draft.colorHex), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${durationLabel(preset.draft.durationSeconds)} · ${if (preset.draft.rainbow) "Rainbow" else pretty(preset.draft.style)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalIconButton(onClick = onActivate, enabled = !busy, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.PlayArrow, "Activate ${preset.name}") }
        }
    }
}

@Composable
internal fun PresetsScreen(state: UiState, model: StrongToastViewModel, onEditor: () -> Unit, onEditPreset: (Preset) -> Unit) {
    var newPreset by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenList {
        item { PageHeading("Your collection", "Save a feeling. Bring it back in one tap.") }
        item {
            PrimaryAction("Save current editor as preset", Icons.Outlined.Add, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { newPreset = true }
        }
        if (state.presets.isEmpty()) item {
            GlassCard(accent = true) {
                Icon(Icons.Outlined.Bookmarks, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(35.dp))
                Text("A home for your favorites.", style = MaterialTheme.typography.headlineMedium)
                Text("Your saved text, color, font, duration and icon stay together. Create your first style in the editor.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SecondaryAction("Create a style", Icons.Outlined.Tune, modifier = Modifier.fillMaxWidth(), onClick = onEditor)
            }
        }
        else {
            item { Eyebrow("${state.presets.size} SAVED ${if (state.presets.size == 1) "STYLE" else "STYLES"}") }
            items(state.presets, key = { it.id }) { preset ->
                GlassCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(preset.name, style = MaterialTheme.typography.titleLarge)
                            Text("${durationLabel(preset.draft.durationSeconds)} · ${pretty(preset.draft.style)} · ${if (preset.draft.rainbow) "Rainbow" else preset.draft.colorHex}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { renameId = preset.id }, enabled = !state.busy) { Icon(Icons.Outlined.DriveFileRenameOutline, "Rename ${preset.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { deleteId = preset.id }, enabled = !state.busy) { Icon(Icons.Outlined.DeleteOutline, "Delete ${preset.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(preset.draft.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, color = if (preset.draft.rainbow) MaterialTheme.colorScheme.onSurface else previewColor(preset.draft.colorHex))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryAction("Activate", Icons.Outlined.PlayArrow, enabled = !state.busy, modifier = Modifier.weight(1f)) { model.activatePreset(preset) }
                        SecondaryAction("Edit", Icons.Outlined.Edit, enabled = !state.busy, modifier = Modifier.weight(1f)) { onEditPreset(preset) }
                    }
                }
            }
        }
    }
    if (newPreset) NameDialog("Save preset", "Keep the current editor settings together.", "", { newPreset = false }) { model.savePreset(it); newPreset = false }
    state.presets.find { it.id == renameId }?.let { preset ->
        NameDialog("Rename preset", "Choose a name that feels familiar.", preset.name, { renameId = null }) { model.renamePreset(preset.id, it); renameId = null }
    }
    state.presets.find { it.id == deleteId }?.let { preset ->
        AlertDialog(onDismissRequest = { deleteId = null }, icon = { Icon(Icons.Outlined.DeleteOutline, null) }, title = { Text("Delete ${preset.name}?") }, text = { Text("This removes the saved preset. The current Strong Toast will keep running.") }, confirmButton = { TextButton(onClick = { model.deletePreset(preset.id); deleteId = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("Keep") } })
    }
}

@Composable
internal fun SettingsScreen(state: UiState, model: StrongToastViewModel) {
    if (!state.loaded) {
        ScreenList {
            item { PageHeading("Make it feel right.", "Loading your saved preferences…") }
            item {
                GlassCard {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Your preferences will be ready in a moment.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    var diagnosticsOpen by rememberSaveable { mutableStateOf(false) }
    var defaultColor by remember(state.settings.defaultColor) { mutableStateOf(state.settings.defaultColor) }
    val validDefaultColor = runCatching { InputValidator.normalizeHex(defaultColor) }.getOrNull()
    ScreenList {
        item { PageHeading("Make it feel right.", "Small preferences. A consistent experience.") }
        item {
            GlassCard {
                SectionTitle("Appearance")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { theme ->
                        FilterChip(selected = state.settings.theme == theme, onClick = { model.updateSettings(state.settings.copy(theme = theme)) }, label = { Text(pretty(theme)) }, modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Reduced motion", "Keep previews still and simplify text effects.", state.settings.reducedMotion, { model.updateSettings(state.settings.copy(reducedMotion = it)) }, Icons.Outlined.MotionPhotosOff)
            }
        }
        item {
            GlassCard {
                SectionTitle("Your defaults", "Used when creating your next status.")
                ColorInput(defaultColor, { defaultColor = it }, "Default HEX color")
                if (validDefaultColor != null && validDefaultColor != state.settings.defaultColor) {
                    TextButton(onClick = { model.updateSettings(state.settings.copy(defaultColor = validDefaultColor)) }, modifier = Modifier.align(Alignment.End)) { Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Apply default color") }
                }
                Eyebrow("Default font")
                FontSelector(state.settings.defaultStyle) { model.updateSettings(state.settings.copy(defaultStyle = it)) }
                Eyebrow("Default duration")
                DurationInput(state.settings.defaultDurationSeconds) { seconds -> model.updateSettings(state.settings.copy(defaultDurationSeconds = seconds)) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Icon preference")
                IconSelector(state.settings.defaultIcon) { model.updateSettings(state.settings.copy(defaultIcon = it)) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Recovery & effects")
                ToggleRow("Automatic restoration", "Bring your status back after a dismissal or panel use, within its original duration.", state.settings.autoRestore, { model.updateSettings(state.settings.copy(autoRestore = it)) }, Icons.Outlined.Restore)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Enhanced effects", "Enable rainbow and countdown effects. Turn off if they behave unexpectedly.", state.settings.experimentalEffects, { model.updateSettings(state.settings.copy(experimentalEffects = it)) }, Icons.Outlined.AutoAwesome)
            }
        }
        item {
            GlassCard {
                SectionTitle("Timer completion")
                ToggleRow("Vibration", "Request a 30-second alert when time is over. Device restrictions still apply.", state.settings.vibration, { model.updateSettings(state.settings.copy(vibration = it)) }, Icons.Outlined.Vibration)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Shake animation", "Give the finished countdown a brief movement.", state.settings.shake, { model.updateSettings(state.settings.copy(shake = it)) }, Icons.Outlined.NotificationsActive)
            }
        }
        item { SectionTitle("Connection") }
        item { ConnectionCard(state, model) }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { diagnosticsOpen = !diagnosticsOpen }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Troubleshoot, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Advanced diagnostics", style = MaterialTheme.typography.titleMedium)
                        Text("Connection details and recent events", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (diagnosticsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (diagnosticsOpen) "Hide diagnostics" else "Show diagnostics")
                }
                if (diagnosticsOpen) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    DiagnosticValue("Root access", pretty(state.root))
                    DiagnosticValue("Hook connection", if (state.module.connected) "Connected" else "Not connected")
                    DiagnosticValue("Native controller", if (state.module.nativeReady) "Ready" else "Not yet available")
                    DiagnosticValue("Current mode", pretty(state.module.mode))
                    DiagnosticValue("Remaining duration", timeLabel(state.module.remainingMillis(state.nowElapsed)))
                    DiagnosticValue("Native toast visibility", if (!state.module.visibilityKnown) "Unverified" else if (state.module.visible) "Visible" else "Not visible")
                    DiagnosticValue("Session revision", state.module.revision.toString())
                    state.module.capabilities.toSortedMap().forEach { (name, available) -> DiagnosticValue(name, if (available) "Available" else "Unavailable") }
                    if (state.module.lastError.isNotBlank()) DiagnosticValue("Last module error", state.module.lastError)
                    SecondaryAction("Refresh connection", Icons.Outlined.Refresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = model::refresh)
                    Eyebrow("Recent events")
                    if (state.diagnostics.isEmpty()) Text("No diagnostic events yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else SelectionContainer {
                        Text(state.diagnostics.takeLast(30).joinToString("\n\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("If System UI becomes unstable, disable StrongToast Extended in LSPosed and restart the device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("STRONGTOAST EXTENDED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Designed to stay out of your way.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
            }
        }
    }
}

@Composable
private fun DiagnosticValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}
