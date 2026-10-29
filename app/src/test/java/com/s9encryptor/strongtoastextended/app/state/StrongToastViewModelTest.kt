package com.s9encryptor.strongtoastextended.app.state

import androidx.lifecycle.ViewModelStore
import com.s9encryptor.strongtoastextended.app.data.PreferencesRepository
import com.s9encryptor.strongtoastextended.app.data.StoredPreferences
import com.s9encryptor.strongtoastextended.app.model.*
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class StateMainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StrongToastViewModelTest {
    @get:Rule val main = StateMainDispatcherRule()
    private val models = mutableListOf<ViewModelStore>()

    private fun model(store: FakePreferences, controller: FakeController): StrongToastViewModel {
        val model = StrongToastViewModel(store, controller) { 123_000L }
        ViewModelStore().also { it.put("test", model); models += it }
        return model
    }

    @After fun clearModels() { models.forEach { it.clear() } }

    @Test fun backgroundBeforeInitialLoadDoesNotOverwriteSavedDraftOrDefaults() = runTest {
        val store = FakePreferences()
        val controller = FakeController()
        val model = model(store, controller)
        model.onBackground()
        model.updateText("An accidental early edit")
        model.updateSettings(UserSettings(theme = ThemeMode.LIGHT))
        model.startStatus()
        advanceUntilIdle()
        assertTrue(store.draftWrites.isEmpty())
        assertTrue(store.settingsWrites.isEmpty())
        assertTrue(controller.commands.isEmpty())

        val saved = StoredPreferences(
            settings = UserSettings(theme = ThemeMode.LIGHT, defaultColor = "#ABCDEF"),
            draft = ToastDraft(text = "Saved Unicode 😀 ", colorHex = "#ABCDEF"),
            settingsRevision = 7,
        )
        store.emit(saved)
        advanceUntilIdle()
        assertTrue(model.uiState.value.loaded)
        assertEquals(saved.draft, model.uiState.value.draft)
        assertEquals(saved.settings, model.uiState.value.settings)
        assertTrue(store.draftWrites.isEmpty())
    }

    @Test fun stopRefreshAndRootApprovalAreAvailableBeforePreferencesLoad() = runTest {
        val store = FakePreferences()
        val controller = FakeController()
        val model = model(store, controller)
        runCurrent()
        model.stop()
        runCurrent()
        model.refresh()
        runCurrent()
        model.grantRoot()
        advanceUntilIdle()
        assertEquals(listOf(CommandType.STOP_STATUS, CommandType.GET_CURRENT_STATE), controller.commands)
        assertEquals(1, controller.rootRequests)
        assertTrue(store.draftWrites.isEmpty())
        assertFalse(model.uiState.value.loaded)
    }

    @Test fun moduleApplyFailureDoesNotRollBackSuccessfullySavedSettings() = runTest {
        val store = FakePreferences()
        val controller = FakeController(connected = true)
        val model = model(store, controller)
        store.emit(StoredPreferences(settingsRevision = 3))
        runCurrent()
        controller.failSettingsApply = true
        model.updateSettings(model.uiState.value.settings.copy(theme = ThemeMode.LIGHT, vibration = false))
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, store.current.settings.theme)
        assertFalse(store.current.settings.vibration)
        assertEquals(store.current.settings, model.uiState.value.settings)
        assertEquals(4L, store.current.settingsRevision)
        assertTrue(model.uiState.value.message.orEmpty().startsWith("Settings saved."))
    }

    @Test fun staleSettingsEmissionsCannotEraseRapidNewerToggleEdits() = runTest {
        val store = FakePreferences()
        val controller = FakeController()
        val model = model(store, controller)
        val initial = StoredPreferences(settingsRevision = 10)
        store.emit(initial)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.nextSettingsGate = gate
        model.updateSettings(model.uiState.value.settings.copy(theme = ThemeMode.LIGHT))
        runCurrent()
        model.updateSettings(model.uiState.value.settings.copy(reducedMotion = true))
        runCurrent()
        // A committed earlier write can be delivered after a newer optimistic edit.
        store.emit(initial.copy(settings = initial.settings.copy(theme = ThemeMode.LIGHT), settingsRevision = 11))
        runCurrent()
        assertTrue(model.uiState.value.settings.reducedMotion)
        model.updateSettings(model.uiState.value.settings.copy(vibration = false))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, store.current.settings.theme)
        assertTrue(store.current.settings.reducedMotion)
        assertFalse(store.current.settings.vibration)
        assertEquals(13L, store.current.settingsRevision)
        assertEquals(listOf(11L, 13L), store.settingsWrites)
        assertEquals(store.current.settings, model.uiState.value.settings)

        store.emit(initial) // an already-observed older settings revision is ignored
        runCurrent()
        assertEquals(ThemeMode.LIGHT, model.uiState.value.settings.theme)
        assertTrue(model.uiState.value.settings.reducedMotion)
        assertFalse(model.uiState.value.settings.vibration)
    }

    @Test fun failedStorageRestoresTheLastDurableSettingsAndReportsSaveFailure() = runTest {
        val store = FakePreferences()
        val controller = FakeController(connected = true)
        val model = model(store, controller)
        val initial = StoredPreferences(settings = UserSettings(defaultColor = "#ABCDEF"), settingsRevision = 8)
        store.emit(initial)
        runCurrent()
        store.failNextSettings = true
        model.updateSettings(model.uiState.value.settings.copy(reducedMotion = true))
        advanceUntilIdle()
        assertEquals(initial.settings, store.current.settings)
        assertEquals(initial.settings, model.uiState.value.settings)
        assertFalse(controller.commands.contains(CommandType.UPDATE_SETTINGS))
        assertEquals("Settings could not be saved. Try again.", model.uiState.value.message)
    }

    private class FakePreferences : PreferencesRepository {
        private val changes = MutableSharedFlow<StoredPreferences>(replay = 1, extraBufferCapacity = 16)
        override val preferences: Flow<StoredPreferences> = changes
        var current = StoredPreferences()
        val draftWrites = mutableListOf<ToastDraft>()
        val settingsWrites = mutableListOf<Long>()
        var nextSettingsGate: CompletableDeferred<Unit>? = null
        var failNextSettings = false

        suspend fun emit(value: StoredPreferences) { current = value; changes.emit(value) }
        override suspend fun saveDraft(draft: ToastDraft) {
            draftWrites += draft
            emit(current.copy(draft = draft))
        }
        override suspend fun saveSettings(settings: UserSettings, draft: ToastDraft, revision: Long) {
            settingsWrites += revision
            val gate = nextSettingsGate.also { nextSettingsGate = null }
            gate?.await()
            if (failNextSettings) { failNextSettings = false; throw IOException("simulated disk failure") }
            emit(current.copy(settings = settings, draft = draft, settingsRevision = revision))
        }
        override suspend fun savePreset(name: String, draft: ToastDraft, id: String?): Preset = error("Unused in this scenario")
        override suspend fun renamePreset(id: String, name: String) = error("Unused in this scenario")
        override suspend fun deletePreset(id: String) = error("Unused in this scenario")
        override suspend fun markPresetUsed(id: String) = error("Unused in this scenario")
    }

    private class FakeController(connected: Boolean = false) : ToastController {
        override val snapshots = MutableStateFlow(ModuleSnapshot(connected = connected))
        override val rootStatus = MutableStateFlow(RootStatus.AVAILABLE)
        override val diagnostics: StateFlow<List<String>> = MutableStateFlow(emptyList())
        val commands = mutableListOf<CommandType>()
        var rootRequests = 0
        var failSettingsApply = false
        override suspend fun command(type: CommandType, draft: ToastDraft, settings: UserSettings): OperationResult {
            commands += type
            if (type == CommandType.UPDATE_SETTINGS && failSettingsApply) throw IOException("simulated connection loss")
            return OperationResult(true, snapshot = snapshots.value)
        }
        override suspend fun connect(settings: UserSettings) = OperationResult(true, snapshot = snapshots.value)
        override suspend fun requestRoot(): OperationResult {
            rootRequests++
            rootStatus.value = RootStatus.GRANTED
            return OperationResult(true)
        }
        override fun closeTransport() = Unit
    }
}
