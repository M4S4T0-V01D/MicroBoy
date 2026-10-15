package dev.m4s4t0.prism.ui.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.input.InputManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.m4s4t0.prism.PrismApp
import dev.m4s4t0.prism.data.AppSettings
import dev.m4s4t0.prism.data.FastForwardMode
import dev.m4s4t0.prism.data.Game
import dev.m4s4t0.prism.data.OrientationMode
import dev.m4s4t0.prism.data.RomResult
import dev.m4s4t0.prism.data.SaveTransfer
import dev.m4s4t0.prism.data.Storage
import dev.m4s4t0.prism.emulation.EmuStats
import dev.m4s4t0.prism.emulation.EmulatorSession
import dev.m4s4t0.prism.emulation.NativeCore
import dev.m4s4t0.prism.input.ControlId
import dev.m4s4t0.prism.input.ControlLayout
import dev.m4s4t0.prism.input.GamepadInput
import dev.m4s4t0.prism.input.PadAction
import dev.m4s4t0.prism.input.TouchControllerView
import dev.m4s4t0.prism.render.DisplayConfig
import dev.m4s4t0.prism.render.GameRenderer
import dev.m4s4t0.prism.ui.common.slotName
import dev.m4s4t0.prism.ui.saves.SlotMode
import dev.m4s4t0.prism.ui.settings.SettingsActivity
import dev.m4s4t0.prism.ui.theme.PrismTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GameActivity : ComponentActivity() {

    private val app get() = PrismApp.instance
    private val storage get() = app.storage

    lateinit var game: Game
        private set
    private var session: EmulatorSession? = null
    private val renderer = GameRenderer()
    private lateinit var glView: GLSurfaceView
    lateinit var controller: TouchControllerView
        private set
    private lateinit var gamepad: GamepadInput
    private val thumbBuffer: ByteBuffer = ByteBuffer.allocateDirect(NativeCore.FRAME_BYTES).order(ByteOrder.nativeOrder())

    // ---- state observed by the Compose UI (GameScreen) ----
    var settings by mutableStateOf(AppSettings())
        private set
    var menuOpen by mutableStateOf(false)
        private set
    var slotMode by mutableStateOf<SlotMode?>(null)
    var editing by mutableStateOf(false)
        private set
    var selectedControl by mutableStateOf<ControlId?>(null)
        private set
    var ffActive by mutableStateOf(false)
        private set
    var gamepadConnected by mutableStateOf(false)
        private set
    var stats by mutableStateOf(EmuStats())
        private set
    var message by mutableStateOf<String?>(null)
    var fatalError by mutableStateOf<String?>(null)
        private set
    var started by mutableStateOf(false)
        private set
    var slotsVersion by mutableIntStateOf(0)
        private set
    var layoutVersion by mutableIntStateOf(0)
        private set

    // Fast-forward sources
    private var ffToggled = false
    private var ffHeldTouch = false
    private var ffHeldPad = false

    // Pause sources other than the menu
    private var lifecyclePaused = false
    private var focusLost = false
    private var recordedPlayMs = 0L

    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private lateinit var inputManager: InputManager

    private var pendingTransferSlot = -1
    private val exportState = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = SaveTransfer.export(this@GameActivity, storage.stateFile(game.id, pendingTransferSlot), uri)
            message = if (ok) "Exported ${slotName(pendingTransferSlot)}" else "Export failed"
        }
    }
    private val importState = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val err = SaveTransfer.importState(this@GameActivity, uri, storage.stateFile(game.id, pendingTransferSlot))
            message = err ?: "Imported into ${slotName(pendingTransferSlot)}"
            slotsVersion++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_GAME_ID)
        val g = id?.let { app.library.get(it) }
        if (g == null) {
            finish()
            return
        }
        game = g
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        inputManager = getSystemService(INPUT_SERVICE) as InputManager

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyDisplay() }
        }
        controller = TouchControllerView(this).apply {
            listener = object : TouchControllerView.Listener {
                override fun onKeysChanged(mask: Int) {
                    session?.setTouchKeys(mask)
                }

                override fun onFastForward(pressed: Boolean) = onFastForwardInput(pressed, fromPad = false)
                override fun onMenu() = openMenu()
                override fun onLayoutEdited(layout: ControlLayout) {
                    layoutVersion++
                    saveLayout(layout)
                }
                override fun onSelectionChanged(id: ControlId?) {
                    selectedControl = id
                }
            }
        }
        gamepad = GamepadInput(
            onKeys = { session?.setPadKeys(it) },
            onFastForward = { onFastForwardInput(it, fromPad = true) },
            onAction = ::onPadAction,
        )
        gamepadConnected = GamepadInput.anyControllerConnected(inputManager)
        inputManager.registerInputDeviceListener(deviceListener, null)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    editing -> finishEditing()
                    slotMode != null -> slotMode = null
                    menuOpen -> closeMenu()
                    else -> openMenu()
                }
            }
        })

        setContent {
            PrismTheme(settings.theme, settings.dynamicColor) {
                GameScreen(this, glView, controller)
            }
        }

        val restoring = savedInstanceState != null
        lifecycleScope.launch {
            settings = app.settings.settings.first()
            applySettings()
            startSession(restoring)
            app.settings.settings.collect {
                settings = it
                applySettings()
            }
        }
        lifecycleScope.launch {
            while (true) {
                delay(500)
                session?.let { stats = it.stats }
            }
        }
        ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private suspend fun startSession(restoring: Boolean) {
        val rom = when (val r = app.library.readRom(game)) {
            is RomResult.Err -> {
                fatalError = r.error.message
                return
            }
            is RomResult.Ok -> r.value
        }
        val bios = storage.biosFile.takeIf { settings.useBios && it.exists() }?.path
        val s = EmulatorSession(
            rom = rom,
            savePath = storage.saveFile(game.id).path,
            biosPath = bios,
            skipBios = settings.skipBiosIntro || bios == null,
            onFrame = { glView.requestRender() },
            onStarted = { ok ->
                runOnUiThread {
                    if (ok) started = true
                    else fatalError = "The emulator couldn't start this ROM. The file may be damaged or not a GBA game."
                }
            },
        )
        session = s
        applySettings()
        s.setSystemPaused(lifecyclePaused || focusLost)
        s.start()

        // Pick a state to resume from: an explicit slot from the launcher, or
        // the auto-save (always after process death, otherwise if enabled).
        val requested = intent.getIntExtra(EXTRA_LOAD_SLOT, -1)
        intent.removeExtra(EXTRA_LOAD_SLOT)
        val auto = storage.stateFile(game.id, Storage.AUTO_SLOT)
        val slot = when {
            restoring && auto.exists() -> Storage.AUTO_SLOT
            requested >= 0 -> requested
            settings.autoLoadState && auto.exists() -> Storage.AUTO_SLOT
            else -> -1
        }
        if (slot >= 0) {
            val file = storage.stateFile(game.id, slot)
            val ok = s.call { NativeCore.nativeLoadState(file.path) }
            message = if (ok) "Resumed from ${slotName(slot)}" else "Couldn't load ${slotName(slot)}; starting fresh"
        }
    }

    // ------------------------------------------------------------ settings

    private fun isPortrait(): Boolean = glView.height > glView.width

    private fun applySettings() {
        val s = settings
        requestedOrientation = when (s.orientation) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        controller.opacity = s.controlsOpacity
        controller.globalScale = s.controlsScale
        controller.haptics = s.haptics
        controller.showControls = !(s.hideControlsWithGamepad && gamepadConnected)
        if (!editing) controller.layout = currentLayout()
        gamepad.mapping = s.gamepadMapping
        session?.let {
            it.fastForwardSpeed = s.fastForwardSpeed
            it.audioEnabled = s.audioEnabled
            it.muted = s.muted
            it.volume = s.volume
        }
        if (s.fastForwardMode == FastForwardMode.HOLD && ffToggled) {
            ffToggled = false
            updateFastForward()
        }
        applyDisplay()
    }

    private fun currentLayout() = if (isPortrait()) settings.layoutPortrait else settings.layoutLandscape

    private fun applyDisplay() {
        if (!::glView.isInitialized) return
        val w = glView.width.toFloat()
        val h = glView.height.toFloat()
        if (w <= 0 || h <= 0) return
        val cutout = ViewCompat.getRootWindowInsets(glView)?.getInsets(WindowInsetsCompat.Type.displayCutout())
        val area = if (h > w) {
            // Portrait: game across the top, controls below it.
            val top = (cutout?.top ?: 0).toFloat()
            RectF(0f, top, w, top + minOf(w / 1.5f, h * 0.45f))
        } else {
            RectF((cutout?.left ?: 0).toFloat(), 0f, w - (cutout?.right ?: 0), h)
        }
        val s = settings
        renderer.config = DisplayConfig(
            scaleMode = s.scaleMode,
            screenSize = s.screenSize,
            smoothing = s.smoothing,
            filter = s.filter,
            filterStrength = s.filterStrength,
            brightness = s.brightness,
            saturation = s.saturation,
            colorCorrection = s.colorCorrection,
            area = area,
        )
        if (!editing) controller.layout = currentLayout()
        glView.requestRender()
    }

    private fun saveLayout(layout: ControlLayout) {
        val portrait = isPortrait()
        lifecycleScope.launch {
            app.settings.update { if (portrait) it.copy(layoutPortrait = layout) else it.copy(layoutLandscape = layout) }
        }
    }

    // --------------------------------------------------------------- input

    private fun onFastForwardInput(pressed: Boolean, fromPad: Boolean) {
        if (settings.fastForwardMode == FastForwardMode.TOGGLE) {
            if (pressed) ffToggled = !ffToggled
        } else if (fromPad) {
            ffHeldPad = pressed
        } else {
            ffHeldTouch = pressed
        }
        updateFastForward()
    }

    fun toggleFastForward() {
        ffToggled = !ffToggled
        updateFastForward()
    }

    private fun updateFastForward() {
        val active = ffToggled || ffHeldTouch || ffHeldPad
        session?.fastForward = active
        ffActive = active
    }

    private fun onPadAction(action: PadAction) {
        when (action) {
            PadAction.MENU -> if (menuOpen) closeMenu() else openMenu()
            PadAction.QUICK_SAVE -> saveState(settings.defaultSlot)
            PadAction.QUICK_LOAD -> loadState(settings.defaultSlot)
            else -> Unit
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val inGame = !menuOpen && slotMode == null && !editing && fatalError == null
        if (inGame && gamepad.onKey(event)) return true
        if (!inGame && settings.gamepadMapping.actionFor(event.keyCode) == PadAction.MENU &&
            event.action == KeyEvent.ACTION_DOWN && menuOpen && slotMode == null
        ) {
            closeMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!menuOpen && !editing && gamepad.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
    }

    private fun refreshDevices() {
        val connected = GamepadInput.anyControllerConnected(inputManager)
        if (connected != gamepadConnected) {
            gamepadConnected = connected
            if (!connected) gamepad.reset()
            message = if (connected) "Controller connected" else "Controller disconnected"
            applySettings()
        }
    }

    // ---------------------------------------------------------------- menu

    fun openMenu() {
        if (fatalError != null) return
        menuOpen = true
        controller.releaseAll()
        gamepad.reset()
        session?.setUserPaused(true)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun closeMenu() {
        menuOpen = false
        slotMode = null
        session?.setUserPaused(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
    }

    fun startEditing() {
        menuOpen = false
        editing = true
        controller.editMode = true
        controller.showControls = true
    }

    fun finishEditing() {
        editing = false
        controller.editMode = false
        applySettings()
        closeMenu()
    }

    fun resetLayout() {
        val layout = if (isPortrait()) ControlLayout.defaultPortrait() else ControlLayout.defaultLandscape()
        controller.layout = layout
        saveLayout(layout)
    }

    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun reset() {
        session?.post { NativeCore.nativeReset() }
        message = "Game reset"
        closeMenu()
    }

    fun toggleMute() {
        lifecycleScope.launch { app.settings.update { it.copy(muted = !it.muted) } }
    }

    fun setFastForwardSpeed(speed: Int) {
        lifecycleScope.launch { app.settings.update { it.copy(fastForwardSpeed = speed) } }
    }

    fun exit() {
        finish()
    }

    // --------------------------------------------------------------- states

    /** Runs on the emulation thread: writes the state and its screenshot. */
    private fun saveStateOnEmuThread(slot: Int): Boolean {
        val file = storage.stateFile(game.id, slot)
        file.parentFile?.mkdirs()
        if (!NativeCore.nativeSaveState(file.path)) return false
        thumbBuffer.clear()
        NativeCore.nativeCopyFrame(thumbBuffer, true)
        renderer.invalidate()
        thumbBuffer.position(0)
        val bmp = Bitmap.createBitmap(NativeCore.WIDTH, NativeCore.HEIGHT, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(thumbBuffer)
        val png = storage.stateThumbnail(game.id, slot)
        val tmp = File(png.path + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(png)
        bmp.recycle()
        return true
    }

    fun saveState(slot: Int) {
        val s = session ?: return
        if (!started) return
        lifecycleScope.launch {
            val ok = runCatching { s.call { saveStateOnEmuThread(slot) } }.getOrDefault(false)
            message = if (ok) "Saved to ${slotName(slot)}" else "Couldn't save state"
            slotsVersion++
            if (slotMode == SlotMode.SAVE) closeMenu()
        }
    }

    fun loadState(slot: Int) {
        val s = session ?: return
        if (!started) return
        val file = storage.stateFile(game.id, slot)
        if (!file.exists()) {
            message = "${slotName(slot)} is empty"
            return
        }
        lifecycleScope.launch {
            val ok = s.call { NativeCore.nativeLoadState(file.path) }
            glView.requestRender()
            message = if (ok) "Loaded ${slotName(slot)}" else "That save state couldn't be loaded"
            if (ok && (menuOpen || slotMode != null)) closeMenu()
        }
    }

    fun deleteState(slot: Int) {
        storage.stateFile(game.id, slot).delete()
        storage.stateThumbnail(game.id, slot).delete()
        slotsVersion++
    }

    fun exportState(slot: Int) {
        pendingTransferSlot = slot
        exportState.launch("${game.displayTitle} - ${slotName(slot)}.state")
    }

    fun importState(slot: Int) {
        pendingTransferSlot = slot
        importState.launch(arrayOf("*/*"))
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        if (!::glView.isInitialized) return
        glView.onResume()
        renderer.invalidate()
        hideSystemBars()
        lifecyclePaused = false
        requestFocus()
        updatePause()
        if (!menuOpen) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        if (!::glView.isInitialized) return
        lifecyclePaused = true
        updatePause()
        controller.releaseAll()
        gamepad.reset()
        ffHeldPad = false
        ffHeldTouch = false
        updateFastForward()
        persistSession()
        abandonFocus()
        glView.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::glView.isInitialized) return
        runCatching { unregisterReceiver(noisyReceiver) }
        inputManager.unregisterInputDeviceListener(deviceListener)
        session?.stop()
        session = null
    }

    private fun updatePause() {
        session?.setSystemPaused(lifecyclePaused || focusLost)
    }

    /** Flushes the cartridge save, writes the auto-save state and records playtime. */
    private fun persistSession() {
        val s = session ?: return
        if (started) {
            if (settings.autoSaveState) s.post { saveStateOnEmuThread(Storage.AUTO_SLOT) }
            s.post { NativeCore.nativeFlushSave() }
        }
        val played = s.playedMs
        val delta = played - recordedPlayMs
        recordedPlayMs = played
        val id = game.id
        app.appScope.launch { app.library.recordSession(id, delta) }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    // Calls, alarms, other media: pause the game.
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        focusLost = true
                        updatePause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusLost = false
                        updatePause()
                    }
                }
            }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        focusLost = false
    }

    /** Headphones unplugged: pause instead of suddenly blasting the speaker. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!menuOpen) openMenu()
        }
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
        const val EXTRA_LOAD_SLOT = "load_slot"

        fun intent(context: Context, gameId: String, loadSlot: Int = -1): Intent =
            Intent(context, GameActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_LOAD_SLOT, loadSlot)
                .setData(Uri.parse("prism://game/$gameId"))
    }
}
