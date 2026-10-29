package com.s9encryptor.strongtoastextended.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.app.state.StrongToastViewModel

internal enum class Destination(val title: String, val label: String, val icon: ImageVector) {
    HOME("Overview", "Home", Icons.Outlined.Dashboard),
    EDITOR("Status studio", "Editor", Icons.Outlined.Tune),
    TIMER("Countdown", "Timer", Icons.Outlined.Timer),
    PRESETS("Your collection", "Presets", Icons.Outlined.Bookmarks),
    SETTINGS("Preferences", "Settings", Icons.Outlined.Settings),
}

@Composable
fun StrongToastApp(state: UiState, model: StrongToastViewModel) {
    StrongToastTheme(state.settings.theme) {
        var destinationName by rememberSaveable { mutableStateOf(Destination.HOME.name) }
        var editingPresetId by rememberSaveable { mutableStateOf<String?>(null) }
        val destination = Destination.valueOf(destinationName)
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(state.message) {
            state.message?.takeIf { it.isNotBlank() }?.let {
                snackbar.showSnackbar(it, withDismissAction = true)
                model.clearMessage()
            }
        }
        Scaffold(
            modifier = Modifier.imePadding(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { AppHeader(state, onRefresh = model::refresh) },
            bottomBar = { AppNavigation(destination, state.settings.reducedMotion) { destinationName = it.name } },
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(horizontal = 16.dp)) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .018f), Color.Transparent)))) {
                Crossfade(destination, animationSpec = tween(if (state.settings.reducedMotion) 0 else 180), label = "page transition") { page ->
                    when (page) {
                        Destination.HOME -> DashboardScreen(state, model, onEditor = { editingPresetId = null; destinationName = Destination.EDITOR.name })
                        Destination.EDITOR -> EditorScreen(state, model, state.presets.find { it.id == editingPresetId }, onFinishEdit = { editingPresetId = null })
                        Destination.TIMER -> TimerScreen(state, model)
                        Destination.PRESETS -> PresetsScreen(state, model, onEditor = { editingPresetId = null; destinationName = Destination.EDITOR.name }, onEditPreset = { preset -> model.loadPreset(preset); editingPresetId = preset.id; destinationName = Destination.EDITOR.name })
                        Destination.SETTINGS -> SettingsScreen(state, model)
                    }
                }
                if (!state.loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun AppHeader(state: UiState, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(39.dp).clip(RoundedCornerShape(13.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .22f), MaterialTheme.colorScheme.primary.copy(alpha = .04f)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("StrongToast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
            Text("EXTENDED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 2.sp)
        }
        IconButton(onClick = onRefresh, enabled = !state.busy) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh module status", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AppNavigation(selected: Destination, reducedMotion: Boolean, onSelect: (Destination) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Destination.entries.forEach { page ->
                val active = page == selected
                val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, tween(if (reducedMotion) 0 else 150), label = "navigation color")
                Column(Modifier.weight(1f).testTag("tab-${page.name}").semantics { this.selected = active }.clip(RoundedCornerShape(15.dp)).clickable(role = Role.Tab) { onSelect(page) }.padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(9.dp)).background(if (active) tint.copy(alpha = .12f) else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(page.icon, page.title, tint = tint, modifier = Modifier.size(21.dp))
                    }
                    Text(page.label, style = MaterialTheme.typography.labelSmall, color = tint, letterSpacing = 0.sp)
                }
            }
        }
    }
}
