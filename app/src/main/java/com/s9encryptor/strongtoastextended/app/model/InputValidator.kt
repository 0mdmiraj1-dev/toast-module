package com.s9encryptor.strongtoastextended.app.model

import java.util.Locale

/** Shared app-side validation. The SystemUI command receiver validates again. */
object InputValidator {
    const val MAX_TEXT_CODEPOINTS = 8192
    const val MAX_PRESETS = 50
    const val MAX_PRESET_NAME_CODEPOINTS = 64
    private val hex = Regex("^[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")

    fun normalizeHex(value: String): String {
        val digits = value.trim().removePrefix("#")
        require(hex.matches(digits)) { "Enter a HEX color with 6 or 8 digits." }
        return "#" + digits.uppercase(Locale.ROOT)
    }

    fun colorArgb(value: String): Int {
        val digits = normalizeHex(value).drop(1)
        return (if (digits.length == 6) "FF$digits" else digits).toLong(16).toInt()
    }

    fun validateDraft(draft: ToastDraft): String? = when {
        draft.text.isBlank() -> "Enter a status message."
        draft.text.codePointCount(0, draft.text.length) > MAX_TEXT_CODEPOINTS ->
            "Keep the status within $MAX_TEXT_CODEPOINTS characters."
        draft.durationSeconds !in 1..3600 -> "Choose a duration between 1 second and 60 minutes."
        runCatching { normalizeHex(draft.colorHex) }.isFailure -> "Enter a valid HEX color."
        else -> null
    }

    fun normalizeDraft(draft: ToastDraft): ToastDraft {
        validateDraft(draft)?.let { throw IllegalArgumentException(it) }
        // Deliberately preserve the text, including trailing whitespace and Unicode.
        return draft.copy(colorHex = normalizeHex(draft.colorHex))
    }

    fun validateSettings(settings: UserSettings): String? = when {
        runCatching { normalizeHex(settings.defaultColor) }.isFailure -> "Enter a valid default HEX color."
        settings.defaultDurationSeconds !in 1..3600 -> "Default duration must be between 1 second and 60 minutes."
        else -> null
    }

    fun normalizePresetName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty()) { "Give the preset a name." }
        require(name.codePointCount(0, name.length) <= MAX_PRESET_NAME_CODEPOINTS) {
            "Keep the preset name within $MAX_PRESET_NAME_CODEPOINTS characters."
        }
        return name
    }
}
