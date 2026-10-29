package com.s9encryptor.strongtoastextended.app.model

import kotlinx.coroutines.flow.StateFlow

enum class FontStyle { NORMAL, BOLD, ITALIC }
enum class IconChoice { HYPERCEILER, APP }
enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class ToastMode { SOLID, RGB, TIMER }
enum class RootStatus { UNKNOWN, AVAILABLE, REQUESTING, GRANTED, DENIED, UNAVAILABLE, ERROR }
enum class CommandType {
    START_STATUS, UPDATE_STATUS, STOP_STATUS, START_RGB, START_TIMER, STOP_TIMER,
    GET_CURRENT_STATE, UPDATE_SETTINGS
}

data class ToastDraft(
    val text: String = "S9_Encryptor",
    val colorHex: String = "#0BFC03",
    val style: FontStyle = FontStyle.NORMAL,
    val durationSeconds: Int = 1800,
    val rainbow: Boolean = false,
    val icon: IconChoice = IconChoice.HYPERCEILER,
)

data class UserSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val defaultColor: String = "#0BFC03",
    val defaultStyle: FontStyle = FontStyle.NORMAL,
    val defaultDurationSeconds: Int = 1800,
    val defaultIcon: IconChoice = IconChoice.HYPERCEILER,
    val autoRestore: Boolean = true,
    val reducedMotion: Boolean = false,
    val vibration: Boolean = true,
    val shake: Boolean = true,
    val experimentalEffects: Boolean = true,
)

data class Preset(val id: String, val name: String, val draft: ToastDraft)

data class ModuleSnapshot(
    val connected: Boolean = false,
    val moduleActive: Boolean = false,
    val nativeReady: Boolean = false,
    val active: Boolean = false,
    val visible: Boolean = false,
    val visibilityKnown: Boolean = false,
    val mode: ToastMode = ToastMode.SOLID,
    val text: String = "",
    val colorHex: String = "#0BFC03",
    val style: FontStyle = FontStyle.NORMAL,
    val icon: IconChoice = IconChoice.HYPERCEILER,
    val requestId: String = "",
    val sessionId: String = "",
    val revision: Long = 0,
    val expiresAtElapsed: Long = 0,
    val countdownEndElapsed: Long = 0,
    val countdownDurationMillis: Long = 0,
    val alerting: Boolean = false,
    val panelOpen: Boolean = false,
    val lastError: String = "",
    val capabilities: Map<String, Boolean> = emptyMap(),
) {
    /** SystemUI deadlines are authoritative; UI ticks only derive a display value. */
    fun remainingMillis(nowElapsed: Long): Long = if (connected && active) {
        val end = if (mode == ToastMode.TIMER && !alerting && countdownEndElapsed > 0L) {
            countdownEndElapsed
        } else expiresAtElapsed
        (end - nowElapsed).coerceAtLeast(0L)
    } else 0L
}

data class OperationResult(
    val success: Boolean,
    val message: String = "",
    val snapshot: ModuleSnapshot? = null,
)

interface ToastController {
    val snapshots: StateFlow<ModuleSnapshot>
    val rootStatus: StateFlow<RootStatus>
    val diagnostics: StateFlow<List<String>>
    suspend fun command(type: CommandType, draft: ToastDraft, settings: UserSettings): OperationResult
    suspend fun connect(settings: UserSettings): OperationResult
    suspend fun requestRoot(): OperationResult
    fun closeTransport()
    fun dispose() { closeTransport() }
}
