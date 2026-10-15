package dev.m4s4t0.microboy.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Export / import of save states and cartridge saves through the Storage Access Framework. */
object SaveTransfer {
    private const val MAX_STATE = 2 * 1024 * 1024
    private const val MAX_SAV = 256 * 1024

    suspend fun export(context: Context, src: File, dest: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(dest, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            true
        }.getOrDefault(false)
    }

    private fun readLimited(context: Context, src: Uri, limit: Int): ByteArray? =
        context.contentResolver.openInputStream(src)?.use { input ->
            val bytes = input.readNBytesCompat(limit + 1)
            if (bytes.size > limit) null else bytes
        }

    /** Copies an exported mGBA save state into [dest]. Returns an error message or null. */
    suspend fun importState(context: Context, src: Uri, dest: File): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { readLimited(context, src, MAX_STATE) }.getOrNull()
            ?: return@withContext "The file couldn't be read or is too large to be a save state."
        // GBA states start with versionMagic = 0x01000000 + version (little-endian).
        if (bytes.size < 0x400 || bytes[3] != 0x01.toByte() || bytes[2] != 0.toByte()) {
            return@withContext "This file isn't a GBA save state from MicroBoy or mGBA."
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) return@withContext "Couldn't write the save state."
        File(dest.path.removeSuffix(".state") + ".png").delete()
        null
    }

    /** Reads a raw .sav (SRAM/Flash/EEPROM dump). */
    suspend fun readSav(context: Context, src: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { readLimited(context, src, MAX_SAV) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Writes a raw .sav for a game that isn't currently running. */
    suspend fun writeSav(dest: File, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(dest.path + ".tmp")
            tmp.writeBytes(data)
            if (dest.exists()) File(dest.path + ".bak").let { bak -> bak.delete(); dest.copyTo(bak) }
            tmp.renameTo(dest)
        }.getOrDefault(false)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < n) {
            val r = read(buf, 0, minOf(buf.size, n - total))
            if (r < 0) break
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }
}
