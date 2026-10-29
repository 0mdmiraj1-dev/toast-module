package com.s9encryptor.strongtoastextended.app.data

import android.content.Context
import com.s9encryptor.strongtoastextended.app.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreferencesStoreTest {
    @Test fun realDataStorePreservesPresetEditsOrderingAndDeletionAcrossRepositoryRecreation() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = PreferencesStore(context)
        val original = ToastDraft(text = "@S9_Encryptor 😀 ", colorHex = "0bfc03", style = FontStyle.BOLD, durationSeconds = 3599, rainbow = true, icon = IconChoice.APP)
        val focus = store.savePreset(" Focus ", original)
        val normalized = original.copy(colorHex = "#0BFC03")
        assertEquals(normalized, store.preferences.first().presets.single().draft)
        val second = store.savePreset("Second", ToastDraft())
        assertEquals(second.id, store.preferences.first().presets.first().id)
        store.renamePreset(focus.id, "Deep focus")
        store.markPresetUsed(focus.id)
        val recreated = PreferencesStore(context)
        assertEquals(focus.id, recreated.preferences.first().presets.first().id)
        assertEquals("Deep focus", recreated.preferences.first().presets.first().name)
        val edited = normalized.copy(text = "Changed 😀 ", rainbow = false, durationSeconds = 60)
        recreated.savePreset("Edited", edited, focus.id)
        assertEquals(2, store.preferences.first().presets.size)
        assertEquals(edited, store.preferences.first().presets.first().draft)
        recreated.deletePreset(focus.id)
        assertEquals(listOf(second), store.preferences.first().presets)
        recreated.deletePreset(second.id)
        assertTrue(store.preferences.first().presets.isEmpty())
    }
}
