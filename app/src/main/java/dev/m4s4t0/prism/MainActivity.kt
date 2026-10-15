package dev.m4s4t0.prism

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.m4s4t0.prism.data.AppSettings
import dev.m4s4t0.prism.ui.library.GameInfoScreen
import dev.m4s4t0.prism.ui.library.LibraryScreen
import dev.m4s4t0.prism.ui.saves.SaveManagerScreen
import dev.m4s4t0.prism.ui.settings.SettingsScreen
import dev.m4s4t0.prism.ui.theme.PrismTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by PrismApp.instance.settings.settings.collectAsState(AppSettings())
            PrismTheme(settings.theme, settings.dynamicColor) {
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
