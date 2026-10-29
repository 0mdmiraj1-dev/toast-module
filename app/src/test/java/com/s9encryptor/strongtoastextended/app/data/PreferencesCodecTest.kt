package com.s9encryptor.strongtoastextended.app.data

import com.s9encryptor.strongtoastextended.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreferencesCodecTest {
    @Test fun preferencesAndUnicodePresetRoundTripWithoutInventingAnActiveSession() {
        val draft = ToastDraft(
            text = "S9_Encryptor 😀 ", colorHex = "#ABCDEF", style = FontStyle.ITALIC,
            durationSeconds = 3600, rainbow = true, icon = IconChoice.APP,
        )
        val settings = UserSettings(
            theme = ThemeMode.LIGHT, defaultStyle = FontStyle.BOLD, defaultDurationSeconds = 600,
            defaultIcon = IconChoice.APP, autoRestore = false, reducedMotion = true,
            vibration = false, shake = false, experimentalEffects = false,
        )
        val original = StoredPreferences(settings, draft, listOf(Preset("sample-id", "Evening", draft)), settingsRevision = 12)
        val decoded = PreferencesCodec.decode(PreferencesCodec.encode(original))
        assertEquals(original, decoded)
        // Durable preferences deliberately contain no module-active/deadline state.
        val json = JSONObject(PreferencesCodec.encode(original))
        assertFalse(json.has("active"))
        assertFalse(json.has("expiresAtElapsed"))
    }

    @Test fun corruptionFallsBackToLaunchableDefaults() {
        assertEquals(StoredPreferences(), PreferencesCodec.decode("{broken"))
        assertEquals(StoredPreferences(), PreferencesCodec.decode(null))
        assertEquals(StoredPreferences(), PreferencesCodec.decode("x".repeat(2_000_001)))
    }

    @Test fun unknownEnumsAndInvalidStoredDefaultsAreRecovered() {
        val value = JSONObject().put("settings", JSONObject()
            .put("theme", "FUTURE_THEME").put("defaultColor", "not-a-color")
            .put("defaultStyle", "UNKNOWN").put("defaultDuration", -100))
        val result = PreferencesCodec.decode(value.toString())
        assertEquals(ThemeMode.DARK, result.settings.theme)
        assertEquals("#0BFC03", result.settings.defaultColor)
        assertEquals(FontStyle.NORMAL, result.settings.defaultStyle)
        assertEquals(1, result.settings.defaultDurationSeconds)
    }

    @Test fun duplicateOrInvalidPresetIdsAreDropped() {
        val item = JSONObject().put("id", "same-id").put("name", "Safe")
            .put("draft", JSONObject().put("text", "Unicode 😀 ").put("color", "#0bfc03"))
        val json = JSONObject().put("presets", JSONArray().put(item).put(item)
            .put(JSONObject().put("id", "../invalid").put("name", "No").put("draft", JSONObject())))
        val result = PreferencesCodec.decode(json.toString())
        assertEquals(1, result.presets.size)
        assertEquals("Unicode 😀 ", result.presets.single().draft.text)
        assertEquals("#0BFC03", result.presets.single().draft.colorHex)
    }
}
