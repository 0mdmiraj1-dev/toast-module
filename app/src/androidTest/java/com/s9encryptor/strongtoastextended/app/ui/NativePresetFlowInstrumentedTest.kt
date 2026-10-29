package com.s9encryptor.strongtoastextended.app.ui

import android.os.SystemClock
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
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Run on an emulator or device. Test-only transport never starts su or sends native commands. */
@RunWith(AndroidJUnit4::class)
class NativePresetFlowInstrumentedTest {
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

    @After fun cleanup() { models.clear() }

    private fun page(name: String) { compose.onNodeWithTag("tab-$name").performClick() }
    private fun reveal(matcher: SemanticsMatcher) {
        compose.onNodeWithTag("screen-list").performScrollToNode(matcher)
    }

    @Test
    fun presetSaveRenameActivateAndDeletePersistThroughTheScreens() {
        launch()
        page("PRESETS")
        compose.waitForIdle()
        compose.onNodeWithText("Save current editor as preset").performClick()
        compose.onNodeWithText("Preset name").performTextInput("Focus")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil { store.preferences.value.presets.size == 1 }
        compose.onNodeWithContentDescription("Rename Focus").performClick()
        compose.onNodeWithText("Preset name").performTextReplacement("Deep focus")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil { store.preferences.value.presets.single().name == "Deep focus" }
        reveal(hasText("Activate"))
        compose.onNodeWithText("Activate").performClick()
        compose.waitUntil { controller.commands.any { it.first == CommandType.START_STATUS } }
        compose.onNodeWithContentDescription("Delete Deep focus").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil { store.preferences.value.presets.isEmpty() }
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
