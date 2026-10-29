package com.s9encryptor.strongtoastextended.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.s9encryptor.strongtoastextended.app.model.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.strongToastStore by preferencesDataStore(name = "strongtoast_native_preferences")

data class StoredPreferences(
    val settings: UserSettings = UserSettings(),
    val draft: ToastDraft = ToastDraft(),
    /** Most recently saved/activated presets come first. */
    val presets: List<Preset> = emptyList(),
    val settingsRevision: Long = 0L,
)

interface PreferencesRepository {
    val preferences: Flow<StoredPreferences>
    suspend fun saveDraft(draft: ToastDraft)
    suspend fun saveSettings(settings: UserSettings, draft: ToastDraft, revision: Long)
    suspend fun savePreset(name: String, draft: ToastDraft, id: String? = null): Preset
    suspend fun renamePreset(id: String, name: String)
    suspend fun deletePreset(id: String)
    suspend fun markPresetUsed(id: String)
}

/** Preferences and drafts only. Active status is never reconstructed from this file. */
class PreferencesStore(context: Context) : PreferencesRepository {
    private val store = context.applicationContext.strongToastStore
    private val document = stringPreferencesKey("document_v1")

    override val preferences: Flow<StoredPreferences> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values -> PreferencesCodec.decode(values[document]) }
        .flowOn(Dispatchers.IO)

    private suspend fun update(transform: (StoredPreferences) -> StoredPreferences) {
        store.edit { values ->
            val next = transform(PreferencesCodec.decode(values[document]))
            values[document] = PreferencesCodec.encode(next)
        }
    }

    override suspend fun saveDraft(draft: ToastDraft) = update { it.copy(draft = draft) }

    override suspend fun saveSettings(settings: UserSettings, draft: ToastDraft, revision: Long) = update {
        require(revision >= 0L) { "Invalid settings revision." }
        if (revision < it.settingsRevision) it else it.copy(
            settings = settings.copy(defaultColor = InputValidator.normalizeHex(settings.defaultColor)),
            draft = draft,
            settingsRevision = revision,
        )
    }

    override suspend fun savePreset(name: String, draft: ToastDraft, id: String?): Preset {
        val normalizedName = InputValidator.normalizePresetName(name)
        val normalizedDraft = InputValidator.normalizeDraft(draft)
        val preset = Preset(id ?: UUID.randomUUID().toString(), normalizedName, normalizedDraft)
        update { previous ->
            if (id != null) require(previous.presets.any { it.id == id }) { "That preset is no longer available." }
            require(previous.presets.any { it.id == preset.id } || previous.presets.size < InputValidator.MAX_PRESETS) {
                "You can save up to ${InputValidator.MAX_PRESETS} presets."
            }
            previous.copy(presets = listOf(preset) + previous.presets.filterNot { it.id == preset.id })
        }
        return preset
    }

    override suspend fun renamePreset(id: String, name: String) {
        val normalized = InputValidator.normalizePresetName(name)
        update { previous ->
            require(previous.presets.any { it.id == id }) { "That preset is no longer available." }
            previous.copy(presets = previous.presets.map { if (it.id == id) it.copy(name = normalized) else it })
        }
    }

    override suspend fun deletePreset(id: String) = update { previous ->
        previous.copy(presets = previous.presets.filterNot { it.id == id })
    }

    override suspend fun markPresetUsed(id: String) = update { previous ->
        val preset = previous.presets.firstOrNull { it.id == id } ?: return@update previous
        previous.copy(presets = listOf(preset) + previous.presets.filterNot { it.id == id })
    }
}

/** Bounded and forward-tolerant decoding keeps a corrupt preference from blocking launch. */
internal object PreferencesCodec {
    private const val MAX_DOCUMENT_CHARS = 2_000_000
    private val validId = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun encode(value: StoredPreferences): String = JSONObject().apply {
        put("version", 1)
        put("settingsRevision", value.settingsRevision)
        put("settings", settingsJson(value.settings))
        put("draft", draftJson(value.draft))
        put("presets", JSONArray().apply {
            value.presets.take(InputValidator.MAX_PRESETS).forEach { preset ->
                put(JSONObject().put("id", preset.id).put("name", preset.name).put("draft", draftJson(preset.draft)))
            }
        })
    }.toString()

    fun decode(value: String?): StoredPreferences {
        if (value.isNullOrBlank() || value.length > MAX_DOCUMENT_CHARS) return StoredPreferences()
        return runCatching {
            val json = JSONObject(value)
            val settings = readSettings(json.optJSONObject("settings") ?: JSONObject())
            val draft = readDraft(json.optJSONObject("draft") ?: JSONObject(), defaultDraft(settings))
            val list = json.optJSONArray("presets") ?: JSONArray()
            val seenIds = hashSetOf<String>()
            val presets = buildList {
                for (index in 0 until minOf(list.length(), InputValidator.MAX_PRESETS)) {
                    val item = list.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (!validId.matches(id) || !seenIds.add(id)) continue
                    val name = runCatching { InputValidator.normalizePresetName(item.optString("name")) }.getOrNull() ?: continue
                    val presetDraft = readDraft(item.optJSONObject("draft") ?: continue, defaultDraft(settings))
                    if (InputValidator.validateDraft(presetDraft) != null) continue
                    add(Preset(id, name, presetDraft.copy(colorHex = InputValidator.normalizeHex(presetDraft.colorHex))))
                }
            }
            StoredPreferences(settings, draft, presets, json.optLong("settingsRevision", 0L).coerceAtLeast(0L))
        }.getOrDefault(StoredPreferences())
    }

    private fun defaultDraft(settings: UserSettings) = ToastDraft(
        colorHex = settings.defaultColor, style = settings.defaultStyle,
        durationSeconds = settings.defaultDurationSeconds, icon = settings.defaultIcon,
    )

    private fun draftJson(value: ToastDraft) = JSONObject().apply {
        put("text", value.text)
        put("color", value.colorHex)
        put("style", value.style.name)
        put("duration", value.durationSeconds)
        put("rainbow", value.rainbow)
        put("icon", value.icon.name)
    }

    private fun readDraft(json: JSONObject, fallback: ToastDraft): ToastDraft {
        val text = json.optString("text", fallback.text)
        // Do not trim text: the trailing-space regression must remain observable.
        val boundedText = if (text.codePointCount(0, text.length) <= InputValidator.MAX_TEXT_CODEPOINTS) text else fallback.text
        return ToastDraft(
            text = boundedText,
            colorHex = json.optString("color", fallback.colorHex).takeIf { it.length <= 16 } ?: fallback.colorHex,
            style = enumValue(json.optString("style"), fallback.style),
            durationSeconds = json.optInt("duration", fallback.durationSeconds).coerceIn(1, 3600),
            rainbow = json.optBoolean("rainbow", fallback.rainbow),
            icon = enumValue(json.optString("icon"), fallback.icon),
        )
    }

    private fun settingsJson(value: UserSettings) = JSONObject().apply {
        put("theme", value.theme.name)
        put("defaultColor", value.defaultColor)
        put("defaultStyle", value.defaultStyle.name)
        put("defaultDuration", value.defaultDurationSeconds)
        put("defaultIcon", value.defaultIcon.name)
        put("autoRestore", value.autoRestore)
        put("reducedMotion", value.reducedMotion)
        put("vibration", value.vibration)
        put("shake", value.shake)
        put("experimentalEffects", value.experimentalEffects)
    }

    private fun readSettings(json: JSONObject): UserSettings {
        val fallback = UserSettings()
        return UserSettings(
            theme = enumValue(json.optString("theme"), fallback.theme),
            defaultColor = runCatching { InputValidator.normalizeHex(json.optString("defaultColor", fallback.defaultColor)) }.getOrDefault(fallback.defaultColor),
            defaultStyle = enumValue(json.optString("defaultStyle"), fallback.defaultStyle),
            defaultDurationSeconds = json.optInt("defaultDuration", fallback.defaultDurationSeconds).coerceIn(1, 3600),
            defaultIcon = enumValue(json.optString("defaultIcon"), fallback.defaultIcon),
            autoRestore = json.optBoolean("autoRestore", fallback.autoRestore),
            reducedMotion = json.optBoolean("reducedMotion", fallback.reducedMotion),
            vibration = json.optBoolean("vibration", fallback.vibration),
            shake = json.optBoolean("shake", fallback.shake),
            experimentalEffects = json.optBoolean("experimentalEffects", fallback.experimentalEffects),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
