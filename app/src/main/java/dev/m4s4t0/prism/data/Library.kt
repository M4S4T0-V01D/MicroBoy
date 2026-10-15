package dev.m4s4t0.prism.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Game(
    /** SHA-1 of the ROM; stable even if the file is moved or renamed. */
    val id: String,
    val uri: String,
    val fileName: String,
    val title: String,
    val gameCode: String,
    val size: Long,
    val addedAt: Long,
    val lastPlayed: Long = 0,
    val playtimeMs: Long = 0,
    val imported: Boolean = false,
) {
    val displayTitle: String
        get() = fileName.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { title.ifEmpty { "Untitled" } }
}

data class AddResult(val added: Int, val duplicates: Int, val failures: List<Pair<String, RomError>>)

/**
 * The game library, persisted as JSON in app storage. Writes are atomic
 * (temp file + rename) so a crash can never leave a half-written library.
 */
class LibraryRepository(private val context: Context, private val storage: Storage) {
    private val file = File(context.filesDir, "library.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val mutex = Mutex()
    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    fun get(id: String): Game? = _games.value.firstOrNull { it.id == id }

    fun lastPlayed(): Game? = _games.value.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }

    private fun load(): List<Game> = runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(Game.serializer()), file.readText()) else emptyList()
    }.getOrElse {
        Log.e(TAG, "Library unreadable, starting empty", it)
        emptyList()
    }

    private fun persist(list: List<Game>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Game.serializer()), list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private suspend fun mutate(block: (List<Game>) -> List<Game>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_games.value)
            persist(next)
            _games.value = next
        }
    }

    suspend fun update(id: String, transform: (Game) -> Game) = mutate { list -> list.map { if (it.id == id) transform(it) else it } }

    suspend fun remove(id: String, deleteData: Boolean) {
        val game = get(id)
        mutate { list -> list.filterNot { it.id == id } }
        if (game != null) {
            if (!game.imported) releasePermission(Uri.parse(game.uri))
            if (deleteData) storage.deleteGameData(id) else File(storage.romsDir, "$id.gba").delete()
        }
    }

    /** Adds individually picked files. */
    suspend fun addFiles(uris: List<Uri>, importCopy: Boolean): AddResult = withContext(Dispatchers.IO) {
        var added = 0
        var dupes = 0
        val failures = mutableListOf<Pair<String, RomError>>()
        for (uri in uris) {
            val name = RomFile.displayName(context, uri)
            when (val r = RomFile.inspect(context, uri)) {
                is RomResult.Err -> failures += name to r.error
                is RomResult.Ok -> {
                    if (addInspected(uri, r.value, importCopy)) added++ else dupes++
                }
            }
        }
        AddResult(added, dupes, failures)
    }

    /** Recursively scans a picked folder for .gba files. */
    suspend fun addFolder(tree: Uri, importCopy: Boolean): AddResult = withContext(Dispatchers.IO) {
        takePermission(tree)
        val found = mutableListOf<Uri>()
        scan(tree, DocumentsContract.getTreeDocumentId(tree), found, depth = 0)
        addFiles(found, importCopy)
    }

    private fun scan(tree: Uri, docId: String, out: MutableList<Uri>, depth: Int) {
        if (depth > 6) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scan(tree, id, out, depth + 1)
                    } else if (RomFile.hasSupportedExtension(name)) {
                        out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Folder scan failed", it) }
    }

    private suspend fun addInspected(uri: Uri, info: RomInfo, importCopy: Boolean): Boolean {
        val existing = get(info.sha1)
        var storedUri = uri
        var imported = false
        if (importCopy) {
            val dest = File(storage.romsDir, "${info.sha1}.gba")
            context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            storedUri = Uri.fromFile(dest)
            imported = true
        } else {
            takePermission(uri)
        }
        if (existing != null) {
            // Same ROM again: refresh its location (e.g. after the user moved the file).
            update(info.sha1) { it.copy(uri = storedUri.toString(), fileName = info.fileName, imported = imported) }
            return false
        }
        mutate {
            it + Game(
                id = info.sha1,
                uri = storedUri.toString(),
                fileName = info.fileName,
                title = info.title,
                gameCode = info.gameCode,
                size = info.size,
                addedAt = System.currentTimeMillis(),
                imported = imported,
            )
        }
        return true
    }

    /** Points an existing entry at a newly picked file, after checking it is the same ROM. */
    suspend fun relocate(id: String, uri: Uri): RomError? = withContext(Dispatchers.IO) {
        when (val r = RomFile.inspect(context, uri)) {
            is RomResult.Err -> r.error
            is RomResult.Ok -> if (r.value.sha1 != id) {
                RomError.Io("that file is a different game (contents don't match)")
            } else {
                takePermission(uri)
                update(id) { it.copy(uri = uri.toString(), fileName = r.value.fileName, imported = false) }
                null
            }
        }
    }

    suspend fun readRom(game: Game): RomResult<ByteArray> = withContext(Dispatchers.IO) {
        RomFile.readAll(context, Uri.parse(game.uri))
    }

    suspend fun recordSession(id: String, playedMs: Long) = update(id) {
        it.copy(lastPlayed = System.currentTimeMillis(), playtimeMs = it.playtimeMs + playedMs)
    }

    private fun takePermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "Could not persist permission for $uri", it) }
    }

    private fun releasePermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private const val TAG = "Library"
    }
}
