package com.s9encryptor.strongtoastextended.app.model

import android.os.SystemClock

data class UiState(
    val draft: ToastDraft = ToastDraft(),
    val timerMinutes: Int = 30,
    val settings: UserSettings = UserSettings(),
    val presets: List<Preset> = emptyList(),
    val module: ModuleSnapshot = ModuleSnapshot(),
    val root: RootStatus = RootStatus.UNKNOWN,
    val busy: Boolean = false,
    val message: String? = null,
    val diagnostics: List<String> = emptyList(),
    val nowElapsed: Long = SystemClock.elapsedRealtime(),
    val loaded: Boolean = false,
)
