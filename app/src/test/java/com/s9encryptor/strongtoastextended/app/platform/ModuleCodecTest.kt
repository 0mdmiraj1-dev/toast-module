package com.s9encryptor.strongtoastextended.app.platform

import android.os.Bundle
import android.os.SystemClock
import com.s9encryptor.strongtoastextended.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ModuleCodecTest {
    @Test fun commandKeepsUnicodeWhitespaceAndValidatedStyleColorAndSettings() {
        val draft = ToastDraft("S9_Encryptor 😀 ", "0bfc03", FontStyle.BOLD, 3600, true)
        val payload = ModuleCodec.commandPayload(draft, UserSettings(reducedMotion = true, vibration = false))
        assertEquals(draft.text, payload.getString("text"))
        assertEquals(0xFF0BFC03.toInt(), payload.getInt("color"))
        assertEquals("BOLD", payload.getString("style"))
        assertEquals(3600, payload.getInt("durationSeconds"))
        assertTrue(payload.getBoolean("rainbow"))
        assertTrue(payload.getBoolean("reducedMotion"))
        assertFalse(payload.getBoolean("vibration"))
        assertEquals("ic_strongtoast_native", payload.getString("iconName"))
    }

    @Test fun visibilityRequiresAnExplicitKnownReadingRatherThanRootOrDeliverySuccess() {
        val payload = snapshot().apply {
            putBoolean("visible", true)
            putBoolean("visibilityKnown", false)
        }
        val state = ModuleCodec.decode(payload)
        assertTrue(state.active)
        assertFalse(state.visibilityKnown)
        assertFalse(state.visible)
        payload.putBoolean("visibilityKnown", true)
        assertTrue(ModuleCodec.decode(payload).visible)
        payload.putBoolean("connected", false)
        val disconnected = ModuleCodec.decode(payload)
        assertFalse(disconnected.moduleActive)
        assertFalse(disconnected.nativeReady)
        assertFalse(disconnected.active)
        assertFalse(disconnected.visible)
    }

    @Test fun timerReconnectPreservesOriginalDeadlineAndDuration() {
        val now = SystemClock.elapsedRealtime()
        val bundle = snapshot().apply {
            putString("mode", "TIMER")
            putLong("countdownEndElapsed", now + 60_000)
            putLong("countdownDurationMillis", 1_800_000)
            putLong("expiresAtElapsed", now + 90_000)
        }
        val state = ModuleCodec.decode(bundle)
        assertEquals(1_800_000, state.countdownDurationMillis)
        assertEquals(60_000, state.remainingMillis(now))
        assertEquals(45_000, state.remainingMillis(now + 15_000))
        bundle.putBoolean("alerting", true)
        assertEquals(90_000, ModuleCodec.decode(bundle).remainingMillis(now))
    }

    @Test fun expiredOrUnboundedClockClaimsCannotCreateAnActiveStatus() {
        val bundle = snapshot().apply { putLong("expiresAtElapsed", 0L) }
        assertFalse(ModuleCodec.decode(bundle).active)
        bundle.putLong("expiresAtElapsed", Long.MAX_VALUE)
        assertFalse(ModuleCodec.decode(bundle).active)
    }

    @Test fun malformedEnumsAndTechnicalMessagesStaySafeAndBounded() {
        val bundle = snapshot().apply {
            putString("mode", "INVALID")
            putString("style", "INVALID")
            putString("icon", "INVALID")
            putString("colorHex", ";not a color")
            putString("lastError", "java.lang.IllegalStateException: /system/bin/\nsecret stack")
            putBundle("capabilities", Bundle().apply {
                putBoolean("watchdog", true)
                putString("notBoolean", "payload")
            })
        }
        val state = ModuleCodec.decode(bundle)
        assertEquals(ToastMode.SOLID, state.mode)
        assertEquals(FontStyle.NORMAL, state.style)
        assertEquals(IconChoice.APP, state.icon)
        assertEquals("#0BFC03", state.colorHex)
        assertFalse(state.lastError.contains("java."))
        assertEquals(mapOf("watchdog" to true), state.capabilities)
        bundle.putString("text", "x".repeat(8193))
        assertThrows(IllegalArgumentException::class.java) { ModuleCodec.decode(bundle) }
    }

    private fun snapshot() = Bundle().apply {
        putInt("protocol", 1)
        putBoolean("success", true)
        putBoolean("connected", true)
        putBoolean("moduleActive", true)
        putBoolean("nativeReady", true)
        putBoolean("active", true)
        putLong("expiresAtElapsed", SystemClock.elapsedRealtime() + 120_000)
        putString("text", "S9_Encryptor")
    }
}
