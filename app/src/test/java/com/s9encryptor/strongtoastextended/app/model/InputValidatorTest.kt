package com.s9encryptor.strongtoastextended.app.model

import org.junit.Assert.*
import org.junit.Test

class InputValidatorTest {
    @Test fun hexInputNormalizesCaseAndOptionalPrefix() {
        assertEquals("#0BFC03", InputValidator.normalizeHex("0bfc03"))
        assertEquals("#0BFC03", InputValidator.normalizeHex(" #0bFC03 "))
        assertEquals(0xFF0BFC03.toInt(), InputValidator.colorArgb("0BFC03"))
        assertEquals(0x800BFC03.toInt(), InputValidator.colorArgb("#800bfc03"))
    }

    @Test fun malformedColorsAreRejectedBeforeExecution() {
        listOf("", "#", "#FFF", "#12345G", "#123456789", "#00;echo bad", "##0BFC03").forEach {
            assertThrows(IllegalArgumentException::class.java) { InputValidator.normalizeHex(it) }
        }
    }

    @Test fun unicodeAndTrailingSpaceAreNeverUsedAsAHiddenLayoutWorkaround() {
        listOf("S9_Encryptor", "S9_Encryptor ", "@S9_Encryptor", "S9 😀 👨‍👩‍👧‍👦 বাংলাদেশ").forEach { text ->
            FontStyle.entries.forEach { style ->
                listOf(false, true).forEach { rainbow ->
                    val normalized = InputValidator.normalizeDraft(ToastDraft(text = text, style = style, rainbow = rainbow))
                    assertEquals(text, normalized.text)
                    assertEquals(style, normalized.style)
                    assertEquals(rainbow, normalized.rainbow)
                }
            }
        }
    }

    @Test fun durationAndUnicodeLimitsUseTheSupportedDomain() {
        assertNull(InputValidator.validateDraft(ToastDraft(durationSeconds = 1)))
        assertNull(InputValidator.validateDraft(ToastDraft(durationSeconds = 3600)))
        assertNotNull(InputValidator.validateDraft(ToastDraft(durationSeconds = 0)))
        assertNotNull(InputValidator.validateDraft(ToastDraft(durationSeconds = 3601)))
        assertNull(InputValidator.validateDraft(ToastDraft(text = "😀".repeat(InputValidator.MAX_TEXT_CODEPOINTS))))
        assertNotNull(InputValidator.validateDraft(ToastDraft(text = "😀".repeat(InputValidator.MAX_TEXT_CODEPOINTS + 1))))
        assertNotNull(InputValidator.validateDraft(ToastDraft(text = " \t\n")))
    }

    @Test fun presetNamesAreBoundedButMessagesRemainUntrimmed() {
        assertEquals("Work", InputValidator.normalizePresetName(" Work "))
        assertThrows(IllegalArgumentException::class.java) { InputValidator.normalizePresetName(" ") }
        assertThrows(IllegalArgumentException::class.java) {
            InputValidator.normalizePresetName("😀".repeat(InputValidator.MAX_PRESET_NAME_CODEPOINTS + 1))
        }
    }

    @Test fun displayedTimerUsesOriginalMonotonicDeadlineAfterReconnection() {
        val state = ModuleSnapshot(
            connected = true, moduleActive = true, active = true, mode = ToastMode.TIMER,
            countdownEndElapsed = 1_900_000, countdownDurationMillis = 1_800_000,
            expiresAtElapsed = 1_930_000,
        )
        assertEquals(60_000L, state.remainingMillis(1_840_000))
        assertFalse(state.visibilityKnown)
        assertEquals(60_000L, state.copy(visibilityKnown = true, visible = true).remainingMillis(1_840_000))
        assertEquals(60_000L, state.copy(visibilityKnown = false, visible = false).remainingMillis(1_840_000))
        assertEquals(45_000L, state.copy(revision = 20).remainingMillis(1_855_000))
        assertEquals(1_800_000L, state.copy(revision = 20).countdownDurationMillis)
        val ui = UiState(module = state, nowElapsed = 1_840_000)
        val editedSetup = ui.copy(timerMinutes = 1, draft = ToastDraft(durationSeconds = 60))
        assertEquals(1_800_000L, editedSetup.module.countdownDurationMillis)
        assertEquals(60_000L, editedSetup.module.remainingMillis(editedSetup.nowElapsed))
        assertEquals(0L, state.remainingMillis(1_900_001))
        assertEquals(20_000L, state.copy(alerting = true).remainingMillis(1_910_000))
        assertEquals(0L, state.copy(connected = false).remainingMillis(1_840_000))
        assertEquals(0L, state.copy(active = false).remainingMillis(1_840_000))
    }
}
