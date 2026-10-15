package dev.m4s4t0.prism.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.m4s4t0.prism.PrismApp
import dev.m4s4t0.prism.data.AppSettings
import dev.m4s4t0.prism.ui.theme.PrismTheme

/** Stand-alone settings, opened from the in-game menu. Changes apply live. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s by PrismApp.instance.settings.settings.collectAsState(AppSettings())
            PrismTheme(s.theme, s.dynamicColor) { SettingsScreen(onBack = ::finish) }
        }
    }
}
