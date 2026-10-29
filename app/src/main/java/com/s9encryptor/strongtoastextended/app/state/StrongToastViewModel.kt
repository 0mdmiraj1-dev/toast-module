package com.s9encryptor.strongtoastextended.app.state

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.s9encryptor.strongtoastextended.app.data.PreferencesStore
import com.s9encryptor.strongtoastextended.app.data.PreferencesRepository
import com.s9encryptor.strongtoastextended.app.model.*
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class StrongToastViewModel internal constructor(
    private val store: PreferencesRepository,
    private val controller: ToastController,
    private val clock: () -> Long,
) : ViewModel() {
    constructor(context: Context, controller: ToastController) : this(
        PreferencesStore(context.applicationContext), controller, SystemClock::elapsedRealtime,
    )

    private val mutableUiState = MutableStateFlow(UiState(nowElapsed = clock()))
    val uiState: StateFlow<UiState> = mutableUiState.asStateFlow()
    private var foreground = false
    private var ticker: Job? = null
    private var draftSave: Job? = null
    private var operation: Job? = null
    private var reconnect: Job? = null
    private var backgroundClose: Job? = null
    private var operationSerial = 0L
    private var firstPreferences = true
    private var draftTouched = false
    private val settingsWriteMutex = Mutex()
    private var settingsEditRevision = 0L
    private var durableSettingsRevision = -1L
    private var pendingSettingsRevision: Long? = null
    private var durableSettings = UserSettings()

    init {
        viewModelScope.launch {
            store.preferences.collect { stored ->
                val freshSettings = stored.settingsRevision >= durableSettingsRevision
                if (freshSettings) {
                    durableSettingsRevision = stored.settingsRevision
                    durableSettings = stored.settings
                }
                settingsEditRevision = maxOf(settingsEditRevision, stored.settingsRevision)
                if (pendingSettingsRevision?.let { stored.settingsRevision >= it } == true) {
                    pendingSettingsRevision = null
                }
                mutableUiState.update { current ->
                    current.copy(
                        settings = if (freshSettings && pendingSettingsRevision == null) stored.settings else current.settings,
                        presets = stored.presets,
                        draft = if (firstPreferences && !draftTouched) stored.draft else current.draft,
                        timerMinutes = if (firstPreferences) {
                            (stored.settings.defaultDurationSeconds / 60).coerceIn(1, 60)
                        } else current.timerMinutes,
                        loaded = true,
                    )
                }
                firstPreferences = false
            }
        }
        viewModelScope.launch {
            controller.snapshots.collect { snapshot ->
                mutableUiState.update { it.copy(module = snapshot, nowElapsed = clock()) }
            }
        }
        viewModelScope.launch {
            controller.rootStatus.collect { status -> mutableUiState.update { it.copy(root = status) } }
        }
        viewModelScope.launch {
            controller.diagnostics.collect { lines ->
                mutableUiState.update { it.copy(diagnostics = lines.takeLast(200)) }
            }
        }
    }

    private fun editDraft(change: (ToastDraft) -> ToastDraft) {
        draftTouched = true
        mutableUiState.update { it.copy(draft = change(it.draft)) }
        persistDraftSoon()
    }

    private fun requirePreferences(): Boolean {
        if (uiState.value.loaded) return true
        showMessage("Preferences are still loading. Try again in a moment.")
        return false
    }

    private fun persistDraftSoon() {
        draftSave?.cancel()
        val draft = uiState.value.draft
        draftSave = viewModelScope.launch {
            delay(300)
            try {
                store.saveDraft(draft)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("The draft could not be saved. Try again.")
            }
        }
    }

    fun updateText(value: String) {
        if (!requirePreferences()) return
        if (value.codePointCount(0, value.length) > InputValidator.MAX_TEXT_CODEPOINTS) {
            showMessage("Keep the status within ${InputValidator.MAX_TEXT_CODEPOINTS} characters.")
            return
        }
        editDraft { it.copy(text = value) }
    }
    fun updateColor(value: String) {
        if (!requirePreferences()) return
        if (value.length > 16) {
            showMessage("Enter a HEX color with 6 or 8 digits.")
            return
        }
        editDraft { it.copy(colorHex = value) }
    }
    fun updateStyle(value: FontStyle) { if (requirePreferences()) editDraft { it.copy(style = value) } }
    fun updateRainbow(value: Boolean) { if (requirePreferences()) editDraft { it.copy(rainbow = value) } }
    fun updateDuration(seconds: Int) { if (requirePreferences()) editDraft { it.copy(durationSeconds = seconds.coerceIn(1, 3600)) } }
    fun updateIcon(value: IconChoice) { if (requirePreferences()) editDraft { it.copy(icon = value) } }
    fun setTimerMinutes(value: Int) {
        if (requirePreferences()) mutableUiState.update { it.copy(timerMinutes = value.coerceIn(1, 60)) }
    }

    private fun validDraft(draft: ToastDraft): ToastDraft? {
        val error = InputValidator.validateDraft(draft)
        if (error != null) {
            showMessage(error)
            return null
        }
        return InputValidator.normalizeDraft(draft)
    }

    fun startStatus() {
        if (!requirePreferences()) return
        val current = uiState.value
        if (current.draft.rainbow && !current.settings.experimentalEffects) {
            showMessage("Enable text effects in Settings to use Rainbow RGB.")
            return
        }
        val draft = validDraft(current.draft) ?: return
        editDraft { draft }
        perform(if (draft.rainbow) CommandType.START_RGB else CommandType.START_STATUS, draft)
    }

    fun updateStatus() {
        if (!requirePreferences()) return
        val draft = validDraft(uiState.value.draft) ?: return
        if (draft.rainbow && !uiState.value.settings.experimentalEffects) {
            showMessage("Enable text effects in Settings to use Rainbow RGB.")
            return
        }
        editDraft { draft }
        perform(CommandType.UPDATE_STATUS, draft)
    }

    fun stop() {
        val command = if (uiState.value.module.mode == ToastMode.TIMER) CommandType.STOP_TIMER else CommandType.STOP_STATUS
        perform(command, uiState.value.draft, interrupt = true)
    }

    fun startTimer() {
        if (!requirePreferences()) return
        val current = uiState.value
        if (!current.settings.experimentalEffects) {
            showMessage("Enable text effects in Settings to use the countdown timer.")
            return
        }
        val seconds = current.timerMinutes.coerceIn(1, 60) * 60
        val draft = current.draft.copy(
            text = String.format(Locale.US, "Remaining %02d:00", current.timerMinutes),
            durationSeconds = seconds,
            rainbow = false,
        )
        val normalized = validDraft(draft) ?: return
        perform(CommandType.START_TIMER, normalized)
    }

    fun resetTimer() {
        if (!requirePreferences()) return
        mutableUiState.update {
            it.copy(timerMinutes = (it.settings.defaultDurationSeconds / 60).coerceIn(1, 60))
        }
        if (uiState.value.module.mode == ToastMode.TIMER && uiState.value.module.active) {
            perform(CommandType.STOP_TIMER, uiState.value.draft, interrupt = true)
        }
    }

    fun savePreset(name: String, id: String? = null) {
        if (!requirePreferences()) return
        val draft = validDraft(uiState.value.draft) ?: return
        localOperation("Preset saved.") { store.savePreset(name, draft, id) }
    }

    fun loadPreset(preset: Preset) {
        if (!requirePreferences()) return
        val actual = uiState.value.presets.firstOrNull { it.id == preset.id }
        if (actual == null) {
            showMessage("That preset is no longer available.")
            return
        }
        editDraft { actual.draft }
    }

    fun activatePreset(preset: Preset) {
        if (!requirePreferences()) return
        val actual = uiState.value.presets.firstOrNull { it.id == preset.id }
        if (actual == null) {
            showMessage("That preset is no longer available.")
            return
        }
        val draft = validDraft(actual.draft) ?: return
        if (draft.rainbow && !uiState.value.settings.experimentalEffects) {
            showMessage("Enable text effects in Settings to use this preset.")
            return
        }
        editDraft { draft }
        perform(if (draft.rainbow) CommandType.START_RGB else CommandType.START_STATUS, draft) {
            store.markPresetUsed(actual.id)
        }
    }

    fun renamePreset(id: String, name: String) {
        if (!requirePreferences()) return
        localOperation("Preset renamed.") { store.renamePreset(id, name) }
    }

    fun deletePreset(id: String) {
        if (!requirePreferences()) return
        localOperation("Preset deleted.") { store.deletePreset(id) }
    }

    fun updateSettings(settings: UserSettings) {
        if (!requirePreferences()) return
        InputValidator.validateSettings(settings)?.let { showMessage(it); return }
        val normalized = settings.copy(defaultColor = InputValidator.normalizeHex(settings.defaultColor))
        val previous = uiState.value.settings
        val draft = uiState.value.draft.let {
            it.copy(
                colorHex = if (it.colorHex == previous.defaultColor) normalized.defaultColor else it.colorHex,
                style = if (it.style == previous.defaultStyle) normalized.defaultStyle else it.style,
                durationSeconds = if (it.durationSeconds == previous.defaultDurationSeconds) normalized.defaultDurationSeconds else it.durationSeconds,
                icon = if (it.icon == previous.defaultIcon) normalized.defaultIcon else it.icon,
            )
        }
        draftSave?.cancel()
        val revision = ++settingsEditRevision
        pendingSettingsRevision = revision
        // Apply theme/control changes immediately; DataStore remains the durable source.
        mutableUiState.update { it.copy(settings = normalized, draft = draft) }
        viewModelScope.launch {
            try {
                val saved = settingsWriteMutex.withLock {
                    // A newer UI edit can supersede a write that has not started.
                    if (revision != settingsEditRevision) return@withLock false
                    store.saveSettings(normalized, draft, revision)
                    if (revision >= durableSettingsRevision) {
                        durableSettingsRevision = revision
                        durableSettings = normalized
                    }
                    true
                }
                if (!saved || revision != settingsEditRevision) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == settingsEditRevision) {
                    pendingSettingsRevision = null
                    mutableUiState.update { it.copy(settings = durableSettings) }
                    showMessage("Settings could not be saved. Try again.")
                }
                return@launch
            }
            // Storage has succeeded. A disconnected module must not roll it back.
            if (uiState.value.module.connected) {
                try {
                    val result = controller.command(CommandType.UPDATE_SETTINGS, draft, normalized)
                    if (revision == settingsEditRevision) acceptResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (revision == settingsEditRevision) {
                        showMessage("Settings saved. The module could not apply them yet; reconnect to try again.")
                    }
                }
            }
        }
    }

    fun refresh() {
        perform(CommandType.GET_CURRENT_STATE, uiState.value.draft)
    }

    fun grantRoot() {
        if (uiState.value.busy) return
        performRootRequest()
    }

    private fun performRootRequest() {
        val serial = ++operationSerial
        operation = viewModelScope.launch {
            mutableUiState.update { it.copy(busy = true) }
            try {
                val result = withTimeout(60_000L) { controller.requestRoot() }
                acceptResult(result)
                if (result.success) acceptResult(controller.connect(uiState.value.settings))
            } catch (_: TimeoutCancellationException) {
                showMessage("Root approval timed out. Try again when you are ready.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("Root access could not be completed. Open Magisk and try again.")
            } finally {
                if (serial == operationSerial) mutableUiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun perform(
        type: CommandType,
        draft: ToastDraft,
        interrupt: Boolean = false,
        onSuccess: (suspend () -> Unit)? = null,
    ) {
        if (!interrupt && uiState.value.busy) return
        if (interrupt) operation?.cancel()
        val serial = ++operationSerial
        val settings = uiState.value.settings
        operation = viewModelScope.launch {
            mutableUiState.update { it.copy(busy = true) }
            try {
                val result = withTimeout(60_000L) { controller.command(type, draft, settings) }
                if (serial != operationSerial) return@launch
                acceptResult(result)
                if (result.success) onSuccess?.invoke()
            } catch (_: TimeoutCancellationException) {
                if (serial == operationSerial) showMessage("The module took too long to reply. Refresh its status and try again.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (serial == operationSerial) showMessage("The action could not be completed. Check the connection and try again.")
            } finally {
                if (serial == operationSerial) mutableUiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun acceptResult(result: OperationResult) {
        // Only acknowledged SystemUI snapshots may change the active status.
        result.snapshot?.let { snapshot -> mutableUiState.update { it.copy(module = snapshot) } }
        if (result.message.isNotBlank()) showMessage(result.message)
        else if (!result.success) showMessage("The module did not confirm this action. Check its status and try again.")
    }

    private fun localOperation(successMessage: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
                showMessage(successMessage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                showMessage(invalid.message ?: "Check the preset details.")
            } catch (_: Exception) {
                showMessage("The preset change could not be saved. Try again.")
            }
        }
    }

    fun clearMessage() { mutableUiState.update { it.copy(message = null) } }
    private fun showMessage(value: String) { mutableUiState.update { it.copy(message = value) } }

    fun onForeground() {
        if (foreground) return
        foreground = true
        backgroundClose?.cancel()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (foreground) {
                mutableUiState.update { it.copy(nowElapsed = clock()) }
                delay(250)
            }
        }
        reconnect?.cancel()
        reconnect = viewModelScope.launch {
            try {
                // This subscribes/queries the module. It is not a repeating root poll.
                val loadedSettings = uiState.first { it.loaded }.settings
                controller.connect(loadedSettings).snapshot?.let { snapshot ->
                    mutableUiState.update { it.copy(module = snapshot) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The compact connection indicator shows unavailability; no launch-time dialog loop.
            }
        }
    }

    fun onBackground() {
        foreground = false
        ticker?.cancel()
        reconnect?.cancel()
        // Persist the last edit before cancelling UI work; SystemUI owns ongoing timers.
        draftSave?.cancel()
        if (uiState.value.loaded || draftTouched) {
            val lastDraft = uiState.value.draft
            viewModelScope.launch { runCatching { store.saveDraft(lastDraft) } }
        }
        backgroundClose?.cancel()
        backgroundClose = viewModelScope.launch {
            delay(1_000)
            // An external Magisk approval surface can briefly background the Activity.
            // Let that explicit permission request finish before closing its transport.
            withTimeoutOrNull(60_000) { controller.rootStatus.first { it != RootStatus.REQUESTING } }
            if (!foreground) controller.closeTransport()
        }
    }

    override fun onCleared() {
        foreground = false
        controller.dispose()
        super.onCleared()
    }

    class Factory(private val context: Context, private val controller: ToastController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(StrongToastViewModel::class.java))
            return StrongToastViewModel(context.applicationContext, controller) as T
        }
    }
}
