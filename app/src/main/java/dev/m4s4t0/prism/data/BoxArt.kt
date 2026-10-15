package dev.m4s4t0.prism.data

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional box-art lookup from the libretro-thumbnails database, matched by the
 * ROM's file name (No-Intro naming works best). Only used when the user enables
 * "Download box art"; covers can also be picked manually.
 */
object BoxArt {
    private const val BASE = "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/"

    private fun thumbnailName(fileName: String): String =
        fileName.substringBeforeLast('.').replace(Regex("[&*/:`<>?\\\\|\"]"), "_")

    /** Downloads the cover to [dest]. Returns true on success. */
    suspend fun fetch(fileName: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        val name = URLEncoder.encode(thumbnailName(fileName), "UTF-8").replace("+", "%20")
        val conn = (URL("$BASE$name.png").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) return@withContext false
            val tmp = File(dest.path + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            // Make sure we actually got an image before replacing anything.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            val ok = bounds.outWidth > 0
            if (ok) tmp.renameTo(dest) else tmp.delete()
            ok
        } catch (e: Exception) {
            Log.i("BoxArt", "No box art for $fileName: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }
}
