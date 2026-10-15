package dev.m4s4t0.prism.data

import android.content.Context
import java.io.File

/** Information about one save-state slot on disk. */
data class StateSlot(val slot: Int, val file: File, val thumbnail: File, val modified: Long) {
    val exists: Boolean get() = file.exists()
    val isAuto: Boolean get() = slot == Storage.AUTO_SLOT
}

/**
 * Locations of everything the app writes. All files live in app-private storage;
 * ROMs stay where the user keeps them unless they explicitly import a copy.
 */
class Storage(context: Context) {
    private val root: File = context.filesDir

    val savesDir = File(root, "saves").apply { mkdirs() }
    val statesDir = File(root, "states").apply { mkdirs() }
    val coversDir = File(root, "covers").apply { mkdirs() }
    val romsDir = File(root, "roms").apply { mkdirs() }
    val biosFile = File(File(root, "bios").apply { mkdirs() }, "gba_bios.bin")

    /** Cartridge (in-game) save: SRAM / Flash / EEPROM contents. */
    fun saveFile(gameId: String) = File(savesDir, "$gameId.sav")

    fun stateDir(gameId: String) = File(statesDir, gameId)

    fun stateFile(gameId: String, slot: Int) = File(stateDir(gameId), "slot$slot.state")

    fun stateThumbnail(gameId: String, slot: Int) = File(stateDir(gameId), "slot$slot.png")

    fun stateSlot(gameId: String, slot: Int): StateSlot {
        val f = stateFile(gameId, slot)
        return StateSlot(slot, f, stateThumbnail(gameId, slot), if (f.exists()) f.lastModified() else 0L)
    }

    fun stateSlots(gameId: String): List<StateSlot> = (AUTO_SLOT..MAX_SLOT).map { stateSlot(gameId, it) }

    fun coverFile(gameId: String) = File(coversDir, "$gameId.png")

    fun deleteGameData(gameId: String) {
        saveFile(gameId).delete()
        stateDir(gameId).deleteRecursively()
        coverFile(gameId).delete()
        File(romsDir, "$gameId.gba").delete()
    }

    companion object {
        const val AUTO_SLOT = 0
        const val MAX_SLOT = 9
    }
}
