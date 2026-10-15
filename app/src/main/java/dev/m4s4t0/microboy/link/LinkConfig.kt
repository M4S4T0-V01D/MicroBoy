package dev.m4s4t0.microboy.link

import android.content.Context
import dev.m4s4t0.microboy.BuildConfig
import java.util.UUID

/** Constants and identity for the online link cable. */
object LinkConfig {
    const val SERVER = "wss://microboy-link.masato-void.workers.dev"

    /**
     * Phones only see and link with the exact same MicroBoy build: both run
     * both consoles, so the emulator must behave identically on each.
     */
    val LINK_VERSION = "1/${BuildConfig.VERSION_CODE}"

    /** Quick messages players can send while choosing what to do together. */
    val QUICK_MESSAGES = listOf(
        "Let's trade!",
        "Want to battle?",
        "One moment…",
        "I'm switching games",
        "Ready!",
        "Thanks!",
        "Bye!",
    )

    /** A random per-install id. The server only ever stores a salted hash of it. */
    fun installId(context: Context): String {
        val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)
        return prefs.getString("install", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install", it).apply()
        }
    }
}
