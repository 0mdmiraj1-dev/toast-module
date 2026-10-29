package com.s9encryptor.strongtoastextended.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import com.s9encryptor.strongtoastextended.app.model.ThemeMode
import com.s9encryptor.strongtoastextended.app.platform.NativeController
import com.s9encryptor.strongtoastextended.app.state.StrongToastViewModel
import com.s9encryptor.strongtoastextended.app.ui.StrongToastApp

class MainActivity : ComponentActivity() {
    private lateinit var model: StrongToastViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(StrongToastViewModel::class.java))
                return StrongToastViewModel(applicationContext, NativeController(applicationContext)) as T
            }
        })[StrongToastViewModel::class.java]
        setContent {
            val state by model.uiState.collectAsStateWithLifecycle()
            val dark = when (state.settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            StrongToastApp(state, model)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::model.isInitialized) model.onForeground()
    }

    override fun onStop() {
        if (::model.isInitialized) model.onBackground()
        super.onStop()
    }
}
