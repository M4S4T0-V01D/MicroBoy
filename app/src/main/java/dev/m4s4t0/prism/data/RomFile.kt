package dev.m4s4t0.prism.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest

/** Parsed GBA cartridge header plus the ROM's content hash. */
data class RomInfo(
    val fileName: String,
    val title: String,
    val gameCode: String,
    val size: Long,
    val sha1: String,
)

sealed class RomError(val message: String) {
    data object Missing : RomError("The file could not be found. It may have been moved, renamed or deleted.")
    data object PermissionLost : RomError("Prism no longer has permission to read this file.")
    data object TooLarge : RomError("The file is larger than 32 MB, which no GBA cartridge is.")
    data object NotGba : RomError("This doesn't look like a Game Boy Advance ROM.")
    class Unsupported(ext: String) : RomError("“.$ext” files are not supported. Choose a .gba file.")
    class Io(detail: String) : RomError("Couldn't read the file: $detail")
}

sealed class RomResult<out T> {
    data class Ok<T>(val value: T) : RomResult<T>()
    data class Err(val error: RomError) : RomResult<Nothing>()
}

object RomFile {
    const val MAX_SIZE = 32L * 1024 * 1024
    private const val HEADER_SIZE = 0xC0
    val EXTENSIONS = setOf("gba", "agb")

    fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "unknown.gba"
    }

    fun hasSupportedExtension(name: String) = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** Validates a header the same way mGBA does (entry branch / fixed byte 0xB2). */
    fun isGbaHeader(h: ByteArray): Boolean =
        h.size >= HEADER_SIZE && (h[0xB2] == 0x96.toByte() || h[3] == 0xEA.toByte())

    private fun headerString(h: ByteArray, from: Int, len: Int): String =
        String(h, from, len, Charsets.US_ASCII).trimEnd('\u0000', ' ').filter { it in ' '..'~' }

    /** Reads, validates and hashes a ROM without keeping it in memory. */
    fun inspect(context: Context, uri: Uri): RomResult<RomInfo> {
        val name = displayName(context, uri)
        if (!hasSupportedExtension(name)) {
            return RomResult.Err(RomError.Unsupported(name.substringAfterLast('.', "?")))
        }
        return open(context, uri) { input ->
            val digest = MessageDigest.getInstance("SHA-1")
            val header = ByteArray(HEADER_SIZE)
            var headerFill = 0
            var total = 0L
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (headerFill < HEADER_SIZE) {
                    val c = minOf(n, HEADER_SIZE - headerFill)
                    System.arraycopy(buf, 0, header, headerFill, c)
                    headerFill += c
                }
                digest.update(buf, 0, n)
                total += n
                if (total > MAX_SIZE) return@open RomResult.Err(RomError.TooLarge)
            }
            if (headerFill < HEADER_SIZE || !isGbaHeader(header)) return@open RomResult.Err(RomError.NotGba)
            RomResult.Ok(
                RomInfo(
                    fileName = name,
                    title = headerString(header, 0xA0, 12),
                    gameCode = headerString(header, 0xAC, 4),
                    size = total,
                    sha1 = digest.digest().joinToString("") { "%02x".format(it) },
                )
            )
        }
    }

    /** Loads the whole ROM into memory for the emulator. */
    fun readAll(context: Context, uri: Uri): RomResult<ByteArray> = open(context, uri) { input ->
        val bytes = input.readBytes()
        when {
            bytes.size > MAX_SIZE -> RomResult.Err(RomError.TooLarge)
            !isGbaHeader(bytes) -> RomResult.Err(RomError.NotGba)
            else -> RomResult.Ok(bytes)
        }
    }

    private fun <T> open(context: Context, uri: Uri, block: (InputStream) -> RomResult<T>): RomResult<T> = try {
        val input = context.contentResolver.openInputStream(uri) ?: return RomResult.Err(RomError.Missing)
        input.buffered().use(block)
    } catch (e: FileNotFoundException) {
        RomResult.Err(RomError.Missing)
    } catch (e: SecurityException) {
        RomResult.Err(RomError.PermissionLost)
    } catch (e: Exception) {
        RomResult.Err(RomError.Io(e.message ?: e.javaClass.simpleName))
    }
}
