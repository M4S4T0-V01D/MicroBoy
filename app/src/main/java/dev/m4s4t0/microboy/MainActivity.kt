package dev.m4s4t0.microboy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.m4s4t0.microboy.data.AppSettings
import dev.m4s4t0.microboy.ui.library.GameInfoScreen
import dev.m4s4t0.microboy.ui.library.LibraryScreen
import dev.m4s4t0.microboy.ui.saves.SaveManagerScreen
import dev.m4s4t0.microboy.ui.settings.SettingsScreen
import dev.m4s4t0.microboy.ui.theme.MicroBoyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by MicroBoyApp.instance.settings.settings.collectAsState(AppSettings())
            MicroBoyTheme(settings) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(
                            onOpenInfo = { nav.navigate("info/$it") },
                            onOpenSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable("info/{id}") { entry ->
                        val id = entry.arguments?.getString("id")!!
                        GameInfoScreen(id, onBack = { nav.popBackStack() }, onOpenSaves = { nav.navigate("saves/$id") })
                    }
                    composable("saves/{id}") { entry ->
                        SaveManagerScreen(entry.arguments?.getString("id")!!, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
