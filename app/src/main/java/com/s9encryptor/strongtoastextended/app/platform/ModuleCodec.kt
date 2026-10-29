package com.s9encryptor.strongtoastextended.app.platform

import android.os.Bundle
import android.os.SystemClock
import com.s9encryptor.strongtoastextended.app.model.*
import com.s9encryptor.strongtoastextended.bridge.BridgeProtocol

/** Only bounded primitives cross the app/SystemUI boundary. */
object ModuleCodec {
    fun settingsPayload(settings: UserSettings): Bundle {
        InputValidator.validateSettings(settings)?.let { throw IllegalArgumentException(it) }
        return Bundle().apply {
            putBoolean("autoRestore", settings.autoRestore)
            putBoolean("reducedMotion", settings.reducedMotion)
            putBoolean("vibration", settings.vibration)
            putBoolean("shake", settings.shake)
            putBoolean("experimentalEffects", settings.experimentalEffects)
        }
    }

    /** Icon resources must already have been resolved through PackageManager, not shell commands. */
    fun commandPayload(
        draft: ToastDraft,
        settings: UserSettings,
        iconPackage: String = BridgeProtocol.APP_ID,
        iconName: String = "ic_strongtoast_native",
        iconFormat: String = "png",
    ): Bundle {
        val normalized = InputValidator.normalizeDraft(draft)
        return settingsPayload(settings).apply {
            putString("text", normalized.text)
            putInt("color", InputValidator.colorArgb(normalized.colorHex))
            putString("style", normalized.style.name)
            putInt("durationSeconds", normalized.durationSeconds)
            putBoolean("rainbow", normalized.rainbow)
            putString("iconPackage", iconPackage)
            putString("iconName", iconName)
            putString("iconFormat", iconFormat)
        }
    }

    fun decode(bundle: Bundle): ModuleSnapshot {
        require(int(bundle, "protocol", 0) == BridgeProtocol.VERSION) { "Unsupported module protocol" }
        val connected = boolean(bundle, "connected")
        val moduleActive = connected && boolean(bundle, "moduleActive")
        val now = SystemClock.elapsedRealtime()
        val reportedExpiry = long(bundle, "expiresAtElapsed")
        val expiry = reportedExpiry.takeIf { it in 0..(now + 3_635_000L) } ?: 0L
        val countdownEnd = long(bundle, "countdownEndElapsed").coerceIn(0, expiry)
        val visibilityKnown = connected && boolean(bundle, "visibilityKnown")
        val color = runCatching { InputValidator.normalizeHex(string(bundle, "colorHex", 16, "#0BFC03")) }
            .getOrDefault("#0BFC03")
        return ModuleSnapshot(
            connected = connected,
            moduleActive = moduleActive,
            nativeReady = moduleActive && boolean(bundle, "nativeReady"),
            active = moduleActive && boolean(bundle, "active") && expiry > now,
            visible = visibilityKnown && boolean(bundle, "visible"),
            visibilityKnown = visibilityKnown,
            mode = enumValue(string(bundle, "mode", 16), ToastMode.SOLID),
            text = text(bundle),
            colorHex = color,
            style = enumValue(string(bundle, "style", 16), FontStyle.NORMAL),
            icon = enumValue(string(bundle, "icon", 24), IconChoice.APP),
            requestId = string(bundle, "lastRequestId", 128).ifEmpty { string(bundle, "requestId", 128) },
            sessionId = string(bundle, "sessionId", 128),
            revision = long(bundle, "revision").coerceAtLeast(0),
            expiresAtElapsed = expiry,
            countdownEndElapsed = countdownEnd,
            countdownDurationMillis = long(bundle, "countdownDurationMillis").coerceIn(0, 3_600_000),
            alerting = moduleActive && boolean(bundle, "alerting"),
            panelOpen = moduleActive && boolean(bundle, "panelOpen"),
            lastError = friendlyMessage(string(bundle, "lastError", 512)),
            capabilities = capabilities(bundle),
        )
    }

    internal fun bootId(bundle: Bundle): String = string(bundle, "bootId", 128)

    internal fun replyMatches(bundle: Bundle, requestId: String): Boolean =
        int(bundle, "protocol", 0) == BridgeProtocol.VERSION && string(bundle, "requestId", 128) == requestId

    internal fun replySuccess(bundle: Bundle, resultCode: Int): Boolean =
        (resultCode == 0 || resultCode == 2) && boolean(bundle, "success")

    internal fun replyMessage(bundle: Bundle): String = friendlyMessage(string(bundle, "message", 512))

    internal fun friendlyMessage(value: String): String {
        val text = value.lineSequence().firstOrNull().orEmpty().take(240).trim()
        val technical = listOf("Exception", "StackTrace", "java.", "android.", "su -c", "app_process", "/system/")
        return if (technical.any { text.contains(it, ignoreCase = true) }) {
            "A SystemUI feature is unavailable. Check Advanced diagnostics and try again."
        } else text
    }

    private fun capabilities(bundle: Bundle): Map<String, Boolean> = runCatching {
        val values = bundle.getBundle("capabilities") ?: return emptyMap()
        values.keySet().take(64).mapNotNull { key ->
            if (key.length > 64 || !key.matches(Regex("[A-Za-z0-9_.:-]+"))) null
            else (values.get(key) as? Boolean)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private inline fun <reified T : Enum<T>> enumValue(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    @Suppress("DEPRECATION")
    private fun text(bundle: Bundle): String {
        val value = bundle.get("text") as? String ?: return ""
        require(value.length <= InputValidator.MAX_TEXT_CODEPOINTS * 2 &&
            value.codePointCount(0, value.length) <= InputValidator.MAX_TEXT_CODEPOINTS) {
            "Module text exceeds its protocol limit"
        }
        return value
    }

    @Suppress("DEPRECATION")
    private fun string(bundle: Bundle, key: String, max: Int, fallback: String = ""): String =
        (bundle.get(key) as? String)?.takeIf { it.length <= max } ?: fallback

    @Suppress("DEPRECATION")
    private fun boolean(bundle: Bundle, key: String): Boolean = bundle.get(key) as? Boolean ?: false

    @Suppress("DEPRECATION")
    private fun int(bundle: Bundle, key: String, fallback: Int): Int = bundle.get(key) as? Int ?: fallback

    @Suppress("DEPRECATION")
    private fun long(bundle: Bundle, key: String): Long = bundle.get(key) as? Long ?: 0L
}
