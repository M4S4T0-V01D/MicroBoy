package dev.m4s4t0.microboy.data

import dev.m4s4t0.microboy.ui.theme.UiShadows
import dev.m4s4t0.microboy.ui.theme.UiCorners
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.m4s4t0.microboy.input.ControlLayout
import dev.m4s4t0.microboy.input.GamepadMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import dev.m4s4t0.microboy.ui.theme.Palettes
import dev.m4s4t0.microboy.ui.theme.ThemeSeeds

enum class FastForwardMode { HOLD, TOGGLE }
enum class ScaleMode { FIT, INTEGER, STRETCH }
enum class ScreenFilter { NONE, SCANLINES, LCD_GRID, CRT }
enum class OrientationMode { AUTO, PORTRAIT, LANDSCAPE }

/** Every user preference, persisted with Jetpack DataStore. */
data class AppSettings(
    // Fast-forward. 0 means "as fast as possible".
    val fastForwardSpeed: Int = 3,
    val fastForwardMode: FastForwardMode = FastForwardMode.HOLD,
    // Slow motion speed (0.5 = half speed) and seconds of rewind history (0 = off).
    val slowMotionSpeed: Float = 0.5f,
    val rewindSeconds: Int = 20,
    // Display
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val screenSize: Float = 1.0f,
    val smoothing: Boolean = false,
    val filter: ScreenFilter = ScreenFilter.NONE,
    val filterStrength: Float = 0.5f,
    val brightness: Float = 1.0f,
    val saturation: Float = 1.0f,
    val colorCorrection: Boolean = false,
    val orientation: OrientationMode = OrientationMode.AUTO,
    // Controls
    val controlsOpacity: Float = 0.55f,
    val controlsScale: Float = 1.0f,
    val haptics: Boolean = true,
    // Cartridge hardware: rumble motor, and the solar sensor's light source
    // (-1 = phone light sensor, 0..10 = fixed sun level).
    val rumble: Boolean = true,
    val sunLevel: Int = -1,
    val hideControlsWithGamepad: Boolean = true,
    val layoutPortrait: ControlLayout = ControlLayout.defaultPortrait(),
    val layoutLandscape: ControlLayout = ControlLayout.defaultLandscape(),
    val gamepadMapping: GamepadMapping = GamepadMapping.default(),
    // Audio
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val audioEnabled: Boolean = true,
    // Saves
    val autoSaveState: Boolean = true,
    val autoLoadState: Boolean = true,
    val defaultSlot: Int = 1,
    // BIOS
    val useBios: Boolean = false,
    val skipBiosIntro: Boolean = true,
    // Overlays
    val showFps: Boolean = false,
    val showStats: Boolean = false,
    // Appearance / library
    val themeId: String = "midnight",
    val customTheme: ThemeSeeds = Palettes.defaultCustom,
    val downloadBoxArt: Boolean = false,
    val importRoms: Boolean = false,
    // Online link: the nickname other players see, whether to appear in the
    // lobby and show the current game, and players blocked by their lobby id.
    val linkNick: String = "",
    // App look: corner style and shadows (default rounded, no shadows).
    val uiCorners: UiCorners = UiCorners.ROUNDED,
    val uiShadows: UiShadows = UiShadows.NONE,
    val linkOnline: Boolean = false,
    val linkVisible: Boolean = true,
    val linkShareGame: Boolean = true,
    val linkBlocked: Set<String> = emptySet(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val ffSpeed = intPreferencesKey("ff_speed")
        val ffMode = stringPreferencesKey("ff_mode")
        val slowSpeed = floatPreferencesKey("slow_speed")
        val rewindSeconds = intPreferencesKey("rewind_seconds")
        val scaleMode = stringPreferencesKey("scale_mode")
        val screenSize = floatPreferencesKey("screen_size")
        val smoothing = booleanPreferencesKey("smoothing")
        val filter = stringPreferencesKey("filter")
        val filterStrength = floatPreferencesKey("filter_strength")
        val brightness = floatPreferencesKey("brightness")
        val saturation = floatPreferencesKey("saturation")
        val colorCorrection = booleanPreferencesKey("color_correction")
        val orientation = stringPreferencesKey("orientation")
        val controlsOpacity = floatPreferencesKey("controls_opacity")
        val controlsScale = floatPreferencesKey("controls_scale")
        val haptics = booleanPreferencesKey("haptics")
        val rumble = booleanPreferencesKey("rumble")
        val sunLevel = intPreferencesKey("sun_level")
        val hideWithPad = booleanPreferencesKey("hide_with_gamepad")
        val layoutPortrait = stringPreferencesKey("layout_portrait")
        val layoutLandscape = stringPreferencesKey("layout_landscape")
        val gamepadMapping = stringPreferencesKey("gamepad_mapping")
        val volume = floatPreferencesKey("volume")
        val muted = booleanPreferencesKey("muted")
        val audioEnabled = booleanPreferencesKey("audio_enabled")
        val autoSave = booleanPreferencesKey("auto_save_state")
        val autoLoad = booleanPreferencesKey("auto_load_state")
        val defaultSlot = intPreferencesKey("default_slot")
        val useBios = booleanPreferencesKey("use_bios")
        val skipBios = booleanPreferencesKey("skip_bios_intro")
        val showFps = booleanPreferencesKey("show_fps")
        val showStats = booleanPreferencesKey("show_stats")
        val themeId = stringPreferencesKey("theme_id")
        val customTheme = stringPreferencesKey("custom_theme")
        val boxArt = booleanPreferencesKey("download_box_art")
        val importRoms = booleanPreferencesKey("import_roms")
        val linkNick = stringPreferencesKey("link_nick")
        val uiCorners = stringPreferencesKey("ui_corners")
        val uiShadows = stringPreferencesKey("ui_shadows")
        val linkOnline = booleanPreferencesKey("link_online")
        val linkVisible = booleanPreferencesKey("link_visible")
        val linkShareGame = booleanPreferencesKey("link_share_game")
        val linkBlocked = stringSetPreferencesKey("link_blocked")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::fromPrefs)

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            fastForwardSpeed = p[K.ffSpeed] ?: d.fastForwardSpeed,
            fastForwardMode = enumOr(p[K.ffMode], d.fastForwardMode),
            slowMotionSpeed = p[K.slowSpeed] ?: d.slowMotionSpeed,
            rewindSeconds = p[K.rewindSeconds] ?: d.rewindSeconds,
            scaleMode = enumOr(p[K.scaleMode], d.scaleMode),
            screenSize = p[K.screenSize] ?: d.screenSize,
            smoothing = p[K.smoothing] ?: d.smoothing,
            filter = enumOr(p[K.filter], d.filter),
            filterStrength = p[K.filterStrength] ?: d.filterStrength,
            brightness = p[K.brightness] ?: d.brightness,
            saturation = p[K.saturation] ?: d.saturation,
            colorCorrection = p[K.colorCorrection] ?: d.colorCorrection,
            orientation = enumOr(p[K.orientation], d.orientation),
            controlsOpacity = p[K.controlsOpacity] ?: d.controlsOpacity,
            controlsScale = p[K.controlsScale] ?: d.controlsScale,
            haptics = p[K.haptics] ?: d.haptics,
            rumble = p[K.rumble] ?: d.rumble,
            sunLevel = p[K.sunLevel] ?: d.sunLevel,
            hideControlsWithGamepad = p[K.hideWithPad] ?: d.hideControlsWithGamepad,
            layoutPortrait = p[K.layoutPortrait]?.let(ControlLayout::decode)?.withDefaults(d.layoutPortrait) ?: d.layoutPortrait,
            layoutLandscape = p[K.layoutLandscape]?.let(ControlLayout::decode)?.withDefaults(d.layoutLandscape) ?: d.layoutLandscape,
            gamepadMapping = p[K.gamepadMapping]?.let(GamepadMapping::decode) ?: d.gamepadMapping,
            volume = p[K.volume] ?: d.volume,
            muted = p[K.muted] ?: d.muted,
            audioEnabled = p[K.audioEnabled] ?: d.audioEnabled,
            autoSaveState = p[K.autoSave] ?: d.autoSaveState,
            autoLoadState = p[K.autoLoad] ?: d.autoLoadState,
            defaultSlot = p[K.defaultSlot] ?: d.defaultSlot,
            useBios = p[K.useBios] ?: d.useBios,
            skipBiosIntro = p[K.skipBios] ?: d.skipBiosIntro,
            showFps = p[K.showFps] ?: d.showFps,
            showStats = p[K.showStats] ?: d.showStats,
            themeId = p[K.themeId] ?: d.themeId,
            customTheme = p[K.customTheme]?.let { runCatching { json.decodeFromString(ThemeSeeds.serializer(), it) }.getOrNull() } ?: d.customTheme,
            downloadBoxArt = p[K.boxArt] ?: d.downloadBoxArt,
            importRoms = p[K.importRoms] ?: d.importRoms,
            linkNick = p[K.linkNick] ?: d.linkNick,
            uiCorners = enumOr(p[K.uiCorners], d.uiCorners),
            uiShadows = enumOr(p[K.uiShadows], d.uiShadows),
            linkOnline = p[K.linkOnline] ?: d.linkOnline,
            linkVisible = p[K.linkVisible] ?: d.linkVisible,
            linkShareGame = p[K.linkShareGame] ?: d.linkShareGame,
            linkBlocked = p[K.linkBlocked] ?: d.linkBlocked,
        )
    }

    /** Applies [transform] to the current settings and persists every field. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(fromPrefs(p))
            p[K.ffSpeed] = s.fastForwardSpeed
            p[K.ffMode] = s.fastForwardMode.name
            p[K.slowSpeed] = s.slowMotionSpeed
            p[K.rewindSeconds] = s.rewindSeconds
            p[K.scaleMode] = s.scaleMode.name
            p[K.screenSize] = s.screenSize
            p[K.smoothing] = s.smoothing
            p[K.filter] = s.filter.name
            p[K.filterStrength] = s.filterStrength
            p[K.brightness] = s.brightness
            p[K.saturation] = s.saturation
            p[K.colorCorrection] = s.colorCorrection
            p[K.orientation] = s.orientation.name
            p[K.controlsOpacity] = s.controlsOpacity
            p[K.controlsScale] = s.controlsScale
            p[K.haptics] = s.haptics
            p[K.rumble] = s.rumble
            p[K.sunLevel] = s.sunLevel
            p[K.hideWithPad] = s.hideControlsWithGamepad
            p[K.layoutPortrait] = s.layoutPortrait.encode()
            p[K.layoutLandscape] = s.layoutLandscape.encode()
            p[K.gamepadMapping] = s.gamepadMapping.encode()
            p[K.volume] = s.volume
            p[K.muted] = s.muted
            p[K.audioEnabled] = s.audioEnabled
            p[K.autoSave] = s.autoSaveState
            p[K.autoLoad] = s.autoLoadState
            p[K.defaultSlot] = s.defaultSlot
            p[K.useBios] = s.useBios
            p[K.skipBios] = s.skipBiosIntro
            p[K.showFps] = s.showFps
            p[K.showStats] = s.showStats
            p[K.themeId] = s.themeId
            p[K.customTheme] = json.encodeToString(ThemeSeeds.serializer(), s.customTheme)
            p[K.boxArt] = s.downloadBoxArt
            p[K.importRoms] = s.importRoms
            p[K.linkNick] = s.linkNick
            p[K.uiCorners] = s.uiCorners.name
            p[K.uiShadows] = s.uiShadows.name
            p[K.linkOnline] = s.linkOnline
            p[K.linkVisible] = s.linkVisible
            p[K.linkShareGame] = s.linkShareGame
            p[K.linkBlocked] = s.linkBlocked
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
