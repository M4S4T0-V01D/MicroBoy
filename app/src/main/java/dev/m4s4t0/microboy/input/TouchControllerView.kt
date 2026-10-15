package dev.m4s4t0.microboy.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import dev.m4s4t0.microboy.emulation.GbaKey
import dev.m4s4t0.microboy.ui.theme.ControllerColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * On-screen controls drawn on a Canvas. Handles any number of simultaneous
 * pointers: a pointer that starts on the D-pad stays bound to it (so the thumb
 * can roll between directions), other pointers re-evaluate the buttons under
 * them on every move (so a thumb can slide from B to A).
 *
 * In edit mode, controls can be dragged around and pinch-resized.
 */
class TouchControllerView(context: Context) : View(context) {

    interface Listener {
        fun onKeysChanged(mask: Int)
        fun onFastForward(pressed: Boolean)
        fun onMenu()
        fun onLayoutEdited(layout: ControlLayout)
        fun onSelectionChanged(id: ControlId?)
    }

    var listener: Listener? = null

    var layout: ControlLayout = ControlLayout.defaultPortrait()
        set(value) {
            field = value
            invalidate()
        }

    var opacity = 0.55f
        set(value) {
            field = value
            invalidate()
        }

    var globalScale = 1f
        set(value) {
            field = value
            invalidate()
        }

    var haptics = true

    /** Theme-derived colors (body, face buttons, pressed highlight). */
    var colors: ControllerColors = ControllerColors(0xFF1E1B2E.toInt(), 0xFF5A3F7A.toInt(), 0xFFB39DFF.toInt(), 0xFFFFFFFF.toInt())
        set(value) {
            field = value
            invalidate()
        }

    /** When false only the menu button is drawn (external controller in use). */
    var showControls = true
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    var editMode = false
        set(value) {
            field = value
            releaseAll()
            selected = null
            invalidate()
        }

    var selected: ControlId? = null
        private set(value) {
            field = value
            listener?.onSelectionChanged(value)
        }

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val tmpRect = RectF()

    // Pointer tracking
    private val pointerOnDpad = HashSet<Int>()
    private val pointerPos = HashMap<Int, FloatArray>()
    private var keyMask = 0
    private var ffPressed = false
    private var menuPressed = false
    private var dpadMask = 0

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Edit-mode drag state
    private var dragId = -1
    private var dragOffX = 0f
    private var dragOffY = 0f
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f

    fun bounds(id: ControlId, out: RectF): RectF {
        val p = layout[id]
        val w = id.baseWidthDp * density * p.scale * globalScale
        val h = id.baseHeightDp * density * p.scale * globalScale
        val cx = p.x * width
        val cy = p.y * height
        out.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        return out
    }

    private fun isShown(id: ControlId): Boolean =
        layout[id].visible && (showControls || id == ControlId.MENU)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        for (id in ControlId.entries) {
            if (!isShown(id) && !editMode) continue
            val r = bounds(id, tmpRect)
            val pressed = isPressed(id)
            val baseAlpha = if (editMode && !layout[id].visible) 0.15f else opacity
            val a = (255 * baseAlpha).toInt().coerceIn(0, 255)
            val isFace = id == ControlId.A || id == ControlId.B
            fill.color = when {
                pressed -> colors.accent
                isFace -> colors.button
                else -> colors.body
            }
            fill.alpha = if (pressed) (a * 0.9f).toInt().coerceAtMost(255) else (a * if (isFace) 0.8f else 0.65f).toInt()
            stroke.color = if (pressed) colors.accent else colors.label
            stroke.alpha = (a * 0.85f).toInt()
            text.color = colors.label
            text.alpha = (a * 1.4f).toInt().coerceAtMost(255)
            when (id) {
                ControlId.DPAD -> drawDpad(canvas, r, a)
                ControlId.A, ControlId.B -> {
                    val rad = min(r.width(), r.height()) / 2
                    canvas.drawCircle(r.centerX(), r.centerY(), rad, fill)
                    canvas.drawCircle(r.centerX(), r.centerY(), rad, stroke)
                    label(canvas, r, id.label, rad * 0.9f)
                }
                ControlId.MENU -> {
                    val rad = min(r.width(), r.height()) / 2
                    canvas.drawCircle(r.centerX(), r.centerY(), rad, fill)
                    canvas.drawCircle(r.centerX(), r.centerY(), rad, stroke)
                    val lw = rad * 0.9f
                    stroke.alpha = a
                    for (i in -1..1) {
                        val y = r.centerY() + i * rad * 0.32f
                        canvas.drawLine(r.centerX() - lw / 2, y, r.centerX() + lw / 2, y, stroke)
                    }
                }
                else -> {
                    val rad = r.height() / 2
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.drawRoundRect(r, rad, rad, stroke)
                    val label = if (id == ControlId.FAST_FORWARD) "▶▶" else id.label
                    label(canvas, r, label, r.height() * if (id == ControlId.L || id == ControlId.R) 0.5f else 0.42f)
                }
            }
            if (editMode && selected == id) {
                val sel = Paint(stroke).apply { color = colors.accent; strokeWidth = 3f * density; alpha = 255 }
                tmpRect.inset(-6 * density, -6 * density)
                canvas.drawRoundRect(tmpRect, 8 * density, 8 * density, sel)
            }
        }
    }

    private fun label(canvas: Canvas, r: RectF, s: String, size: Float) {
        text.textSize = size
        canvas.drawText(s, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawDpad(canvas: Canvas, r: RectF, alpha: Int) {
        val cx = r.centerX()
        val cy = r.centerY()
        val half = min(r.width(), r.height()) / 2
        val arm = half * 0.36f
        path.reset()
        path.moveTo(cx - arm, cy - half)
        path.lineTo(cx + arm, cy - half)
        path.lineTo(cx + arm, cy - arm)
        path.lineTo(cx + half, cy - arm)
        path.lineTo(cx + half, cy + arm)
        path.lineTo(cx + arm, cy + arm)
        path.lineTo(cx + arm, cy + half)
        path.lineTo(cx - arm, cy + half)
        path.lineTo(cx - arm, cy + arm)
        path.lineTo(cx - half, cy + arm)
        path.lineTo(cx - half, cy - arm)
        path.lineTo(cx - arm, cy - arm)
        path.close()
        fill.color = colors.body
        fill.alpha = (alpha * 0.65f).toInt()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        // Highlight pressed directions.
        fill.color = colors.accent
        fill.alpha = (alpha * 0.9f).toInt().coerceAtMost(255)
        if (dpadMask and GbaKey.UP.mask != 0) canvas.drawRect(cx - arm, cy - half, cx + arm, cy - arm, fill)
        if (dpadMask and GbaKey.DOWN.mask != 0) canvas.drawRect(cx - arm, cy + arm, cx + arm, cy + half, fill)
        if (dpadMask and GbaKey.LEFT.mask != 0) canvas.drawRect(cx - half, cy - arm, cx - arm, cy + arm, fill)
        if (dpadMask and GbaKey.RIGHT.mask != 0) canvas.drawRect(cx + arm, cy - arm, cx + half, cy + arm, fill)
        fill.color = colors.label
        fill.alpha = (alpha * 0.25f).toInt()
        canvas.drawCircle(cx, cy, arm * 0.45f, fill)
    }

    private fun isPressed(id: ControlId): Boolean = when (id) {
        ControlId.DPAD -> dpadMask != 0
        ControlId.A -> keyMask and GbaKey.A.mask != 0
        ControlId.B -> keyMask and GbaKey.B.mask != 0
        ControlId.L -> keyMask and GbaKey.L.mask != 0
        ControlId.R -> keyMask and GbaKey.R.mask != 0
        ControlId.START -> keyMask and GbaKey.START.mask != 0
        ControlId.SELECT -> keyMask and GbaKey.SELECT.mask != 0
        ControlId.FAST_FORWARD -> ffPressed
        ControlId.MENU -> menuPressed
    }

    // ----------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return onEditTouch(e)
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = e.getPointerId(idx)
                val x = e.getX(idx)
                val y = e.getY(idx)
                pointerPos[id] = floatArrayOf(x, y)
                if (showControls && layout[ControlId.DPAD].visible && hitDpad(x, y)) pointerOnDpad += id
                if (isShown(ControlId.MENU) && hit(ControlId.MENU, x, y, 1.1f)) menuPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    pointerPos[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(idx)
                val pos = pointerPos.remove(id)
                pointerOnDpad -= id
                if (menuPressed && pos != null && hit(ControlId.MENU, pos[0], pos[1], 1.3f)) {
                    menuPressed = false
                    listener?.onMenu()
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) menuPressed = false
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseAll()
                return true
            }
        }
        recompute()
        return true
    }

    private fun hit(id: ControlId, x: Float, y: Float, slop: Float): Boolean {
        val r = bounds(id, tmpRect)
        val dx = (r.width() * (slop - 1f)) / 2
        val dy = (r.height() * (slop - 1f)) / 2
        return x >= r.left - dx && x <= r.right + dx && y >= r.top - dy && y <= r.bottom + dy
    }

    private fun hitDpad(x: Float, y: Float): Boolean {
        val r = bounds(ControlId.DPAD, tmpRect)
        return hypot(x - r.centerX(), y - r.centerY()) <= min(r.width(), r.height()) / 2 * 1.2f
    }

    private fun dpadDirection(x: Float, y: Float): Int {
        val r = bounds(ControlId.DPAD, tmpRect)
        val dx = x - r.centerX()
        val dy = y - r.centerY()
        val dead = min(r.width(), r.height()) / 2 * 0.18f
        if (hypot(dx, dy) < dead) return 0
        // 8 sectors of 45°, 0° = right, counter-clockwise (screen y is flipped).
        val deg = (Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble())) + 360.0) % 360.0
        val sector = ((deg + 22.5) / 45.0).toInt() % 8
        return when (sector) {
            0 -> GbaKey.RIGHT.mask
            1 -> GbaKey.RIGHT.mask or GbaKey.UP.mask
            2 -> GbaKey.UP.mask
            3 -> GbaKey.UP.mask or GbaKey.LEFT.mask
            4 -> GbaKey.LEFT.mask
            5 -> GbaKey.LEFT.mask or GbaKey.DOWN.mask
            6 -> GbaKey.DOWN.mask
            else -> GbaKey.DOWN.mask or GbaKey.RIGHT.mask
        }
    }

    private fun recompute() {
        var mask = 0
        var dpad = 0
        var ff = false
        for ((id, pos) in pointerPos) {
            val x = pos[0]
            val y = pos[1]
            if (id in pointerOnDpad) {
                dpad = dpad or dpadDirection(x, y)
                continue
            }
            if (!showControls) continue
            if (isShown(ControlId.A) && hit(ControlId.A, x, y, 1.25f)) mask = mask or GbaKey.A.mask
            if (isShown(ControlId.B) && hit(ControlId.B, x, y, 1.25f)) mask = mask or GbaKey.B.mask
            if (isShown(ControlId.L) && hit(ControlId.L, x, y, 1.3f)) mask = mask or GbaKey.L.mask
            if (isShown(ControlId.R) && hit(ControlId.R, x, y, 1.3f)) mask = mask or GbaKey.R.mask
            if (isShown(ControlId.START) && hit(ControlId.START, x, y, 1.4f)) mask = mask or GbaKey.START.mask
            if (isShown(ControlId.SELECT) && hit(ControlId.SELECT, x, y, 1.4f)) mask = mask or GbaKey.SELECT.mask
            if (isShown(ControlId.FAST_FORWARD) && hit(ControlId.FAST_FORWARD, x, y, 1.3f)) ff = true
        }
        mask = mask or dpad
        val newlyPressed = (mask and keyMask.inv()) != 0 || (ff && !ffPressed)
        if (newlyPressed && haptics) buzz()
        val changed = mask != keyMask || dpad != dpadMask || ff != ffPressed
        if (mask != keyMask) listener?.onKeysChanged(mask)
        if (ff != ffPressed) listener?.onFastForward(ff)
        keyMask = mask
        dpadMask = dpad
        ffPressed = ff
        if (changed) invalidate()
    }

    fun releaseAll() {
        pointerPos.clear()
        pointerOnDpad.clear()
        menuPressed = false
        if (keyMask != 0) listener?.onKeysChanged(0)
        if (ffPressed) listener?.onFastForward(false)
        keyMask = 0
        dpadMask = 0
        ffPressed = false
        invalidate()
    }

    private fun buzz() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            v.vibrate(VibrationEffect.createOneShot(12, 120))
        }
    }

    // ------------------------------------------------------------ edit mode

    private fun controlAt(x: Float, y: Float): ControlId? =
        ControlId.entries.reversed().firstOrNull { hit(it, x, y, 1.15f) }

    private fun onEditTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x
                val y = e.y
                val id = controlAt(x, y)
                selected = id
                if (id != null) {
                    val p = layout[id]
                    dragId = e.getPointerId(0)
                    dragOffX = p.x * width - x
                    dragOffY = p.y * height - y
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && selected != null) {
                    pinchStartDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchStartScale = layout[selected!!].scale
                    dragId = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = selected ?: return true
                if (e.pointerCount >= 2 && pinchStartDist > 0) {
                    val d = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    val scale = (pinchStartScale * d / pinchStartDist).coerceIn(0.5f, 2.5f)
                    layout = layout.with(id, layout[id].copy(scale = scale))
                } else if (dragId >= 0) {
                    val i = e.findPointerIndex(dragId)
                    if (i >= 0) {
                        val nx = ((e.getX(i) + dragOffX) / width).coerceIn(0.02f, 0.98f)
                        val ny = ((e.getY(i) + dragOffY) / height).coerceIn(0.02f, 0.98f)
                        // Light snapping to the center line helps symmetric layouts.
                        val sx = if (abs(nx - 0.5f) < 0.012f) 0.5f else nx
                        layout = layout.with(id, layout[id].copy(x = sx, y = ny))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragId = -1
                pinchStartDist = 0f
                listener?.onLayoutEdited(layout)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinchStartDist = 0f
            }
        }
        return true
    }

    /** Applies a change to the selected control from the edit toolbar. */
    fun updateSelected(transform: (ControlPlacement) -> ControlPlacement) {
        val id = selected ?: return
        layout = layout.with(id, transform(layout[id]))
        listener?.onLayoutEdited(layout)
    }
}
