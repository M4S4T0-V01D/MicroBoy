package dev.m4s4t0.microboy.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import dev.m4s4t0.microboy.BuildConfig

/** Feedback and beta-testing links. Everything goes to one inbox. */
object Feedback {
    const val EMAIL = "masato-void@protonmail.com"
    const val GUIDE_URL = "https://m4s4t0-v01d.github.io/MicroBoy/feedback.html"

    /** Opens the user's email app with a short template and device details filled in. */
    fun email(context: Context, topic: String = "Feedback") {
        val body = """
            |What happened, or what's your idea?
            |
            |
            |Steps to reproduce (for bugs):
            |1.
            |2.
            |3.
            |
            |What did you expect instead?
            |
            |
            |Game (if it matters):
            |
            |---
            |MicroBoy ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
            |Device: ${Build.MANUFACTURER} ${Build.MODEL}
            |Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        """.trimMargin()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "MicroBoy $topic (${BuildConfig.VERSION_NAME})")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No email app found. Write to $EMAIL", Toast.LENGTH_LONG).show()
        }
    }

    fun openGuide(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, GUIDE_URL, Toast.LENGTH_LONG).show()
        }
    }
}
