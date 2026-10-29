package com.s9encryptor.strongtoastextended.app.ui

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.s9encryptor.strongtoastextended.app.data.PreferencesRepository
import com.s9encryptor.strongtoastextended.app.data.StoredPreferences
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.app.state.StrongToastViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native Compose interactions with test-only storage/transport; never starts su. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeAppInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val store = MemoryPreferences()
    private val controller = RecordingController()
    private val models = ViewModelStore()
    private lateinit var model: StrongToastViewModel

    private fun launch() {
        compose.setContent {
            if (!::model.isInitialized) {
                model = StrongToastViewModel(store, controller, SystemClock::elapsedRealtime)
                models.put("ui", model)
            }
            val state by model.uiState.collectAsState()
            StrongToastApp(state, model)
        }
        compose.waitUntil { ::model.isInitialized && model.uiState.value.loaded }
    }

    private fun capture(name: String) {
        val destination = File("build/reports/native-ui/$name.png")
        destination.parentFile?.mkdirs()
        destination.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @After fun cleanup() { models.clear() }

    private fun page(name: String) { compose.onNodeWithTag("tab-$name").performClick() }
    private fun reveal(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("screen-list").performScrollToNode(matcher)
    }

    @Test fun editorSendsStyledUnicodeAndStopUsesTheRealActionBinding() {
        launch()
        compose.onNodeWithText("Visible").assertDoesNotExist()
        capture("dashboard")
        page("EDITOR")
        reveal(hasText("Status text"))
        compose.onNodeWithText("Status text").performTextReplacement("@S9_Encryptor 😀 ")
        compose.onNodeWithText("Bold").performClick()
        reveal(hasText("HEX color"))
        compose.onNodeWithText("HEX color").performTextReplacement("a1b2c3")
        reveal(hasText("Start status"))
        compose.onNodeWithText("Start status").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.START_STATUS } }
        val sent = controller.commands.first { it.first == CommandType.START_STATUS }.second
        assertEquals("@S9_Encryptor 😀 ", sent.text)
        assertEquals(FontStyle.BOLD, sent.style)
        assertEquals("#A1B2C3", sent.colorHex)
        page("HOME")
        reveal(hasText("Stop"))
        compose.onNodeWithText("Stop").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.STOP_STATUS } }
    }

    @Test fun savedPresetActivatesAndLoadsIntoTheEditor() {
        val draft = ToastDraft(text = "Focus 😀 ", colorHex = "#A1B2C3", style = FontStyle.ITALIC)
        store.preferences.value = store.preferences.value.copy(presets = listOf(Preset("focus", "Focus", draft)))
        launch()
        page("PRESETS")
        reveal(hasText("Activate"))
        compose.onNodeWithText("Activate").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.START_STATUS } }
        assertEquals(draft, controller.commands.first { it.first == CommandType.START_STATUS }.second)
        compose.onNodeWithText("Edit").performClick()
        reveal(hasText("Status text"))
        compose.onNodeWithText("Status text").assertTextContains("Focus 😀 ")
        assertEquals(FontStyle.ITALIC, model.uiState.value.draft.style)
    }

    @Test fun timerAndSettingsAreReachableAndBoundToTheViewModel() {
        launch()
        page("TIMER")
        capture("timer")
        compose.onNodeWithText("Start timer").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.START_TIMER } }
        compose.onNodeWithText("Stop").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.STOP_STATUS } }
        page("SETTINGS")
        compose.onNodeWithText("Light").performClick()
        compose.waitUntil { store.preferences.value.settings.theme == ThemeMode.LIGHT }
        reveal(hasText("Automatic restoration"))
        compose.onNodeWithContentDescription("Automatic restoration").performClick()
        compose.waitUntil { !store.preferences.value.settings.autoRestore }
        page("HOME")
        compose.onNodeWithTag("tab-HOME").assertIsSelected()
    }

    private class RecordingController : ToastController {
        override val snapshots = MutableStateFlow(ModuleSnapshot(connected = true, moduleActive = true, nativeReady = true))
        override val rootStatus = MutableStateFlow(RootStatus.GRANTED)
        override val diagnostics = MutableStateFlow(emptyList<String>())
        val commands = mutableListOf<Pair<CommandType, ToastDraft>>()
        override suspend fun command(type: CommandType, draft: ToastDraft, settings: UserSettings): OperationResult {
            commands += type to draft
            // This fixture acknowledges transport only; it never claims a rendered Toast.
            return OperationResult(true, snapshot = snapshots.value)
        }
        override suspend fun connect(settings: UserSettings) = OperationResult(true, snapshot = snapshots.value)
        override suspend fun requestRoot() = OperationResult(true)
        override fun closeTransport() = Unit
    }

    private class MemoryPreferences : PreferencesRepository {
        override val preferences = MutableStateFlow(StoredPreferences(settings = UserSettings(reducedMotion = true)))
        override suspend fun saveDraft(draft: ToastDraft) { preferences.value = preferences.value.copy(draft = draft) }
        override suspend fun saveSettings(settings: UserSettings, draft: ToastDraft, revision: Long) {
            preferences.value = preferences.value.copy(settings = settings, draft = draft, settingsRevision = revision)
        }
        override suspend fun savePreset(name: String, draft: ToastDraft, id: String?): Preset {
            val preset = Preset(id ?: "test-preset", name, draft)
            preferences.value = preferences.value.copy(presets = preferences.value.presets.filterNot { it.id == preset.id } + preset)
            return preset
        }
        override suspend fun renamePreset(id: String, name: String) {
            preferences.value = preferences.value.copy(presets = preferences.value.presets.map { if (it.id == id) it.copy(name = name) else it })
        }
        override suspend fun deletePreset(id: String) {
            preferences.value = preferences.value.copy(presets = preferences.value.presets.filterNot { it.id == id })
        }
        override suspend fun markPresetUsed(id: String) = Unit
    }
}
