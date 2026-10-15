package dev.m4s4t0.microboy.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.m4s4t0.microboy.MicroBoyApp
import dev.m4s4t0.microboy.data.AppSettings
import dev.m4s4t0.microboy.ui.theme.MicroBoyTheme

/** Stand-alone settings, opened from the in-game menu. Changes apply live. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val s by MicroBoyApp.instance.settings.settings.collectAsState(AppSettings())
            MicroBoyTheme(s) { SettingsScreen(onBack = ::finish) }
        }
    }
}
