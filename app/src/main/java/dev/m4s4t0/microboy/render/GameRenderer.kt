package dev.m4s4t0.microboy.render

import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import dev.m4s4t0.microboy.data.ScaleMode
import dev.m4s4t0.microboy.data.ScreenFilter
import dev.m4s4t0.microboy.emulation.NativeCore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Display options the renderer reads every frame (written from the UI thread). */
data class DisplayConfig(
    val scaleMode: ScaleMode = ScaleMode.FIT,
    val screenSize: Float = 1f,
    val smoothing: Boolean = false,
    val filter: ScreenFilter = ScreenFilter.NONE,
    val filterStrength: Float = 0.5f,
    val brightness: Float = 1f,
    val saturation: Float = 1f,
    val colorCorrection: Boolean = false,
    /** Area of the surface (in px) the game should be fitted into. */
    val area: RectF? = null,
)

/**
 * Draws the newest emulator frame as a single textured quad. Frames are pulled
 * from the native front buffer into one preallocated direct buffer, so the
 * steady state does no allocation at all.
 */
class GameRenderer : GLSurfaceView.Renderer {
    @Volatile var config = DisplayConfig()

    private val frame: ByteBuffer = ByteBuffer.allocateDirect(NativeCore.FRAME_BYTES).order(ByteOrder.nativeOrder())
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program = 0
    private var texture = 0
    private var surfaceW = 1
    private var surfaceH = 1
    private var needsFullUpload = true
    private var lastSmoothing: Boolean? = null
    private val dst = RectF()
    private val verts = FloatArray(16)

    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uSrcSize = 0
    private var uDstSize = 0
    private var uFilter = 0
    private var uStrength = 0
    private var uBrightness = 0
    private var uSaturation = 0
    private var uColorCorrect = 0

    /** Last drawn game rectangle in surface pixels (for layout decisions). */
    @Volatile var gameRect = RectF()
        private set

    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uSrcSize = GLES20.glGetUniformLocation(program, "uSrcSize")
        uDstSize = GLES20.glGetUniformLocation(program, "uDstSize")
        uFilter = GLES20.glGetUniformLocation(program, "uFilter")
        uStrength = GLES20.glGetUniformLocation(program, "uStrength")
        uBrightness = GLES20.glGetUniformLocation(program, "uBrightness")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uColorCorrect = GLES20.glGetUniformLocation(program, "uColorCorrect")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, NativeCore.WIDTH, NativeCore.HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        lastSmoothing = null
        needsFullUpload = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val cfg = config
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (lastSmoothing != cfg.smoothing) {
            val f = if (cfg.smoothing) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, f)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, f)
            lastSmoothing = cfg.smoothing
        }
        if (NativeCore.nativeCopyFrame(frame, needsFullUpload)) {
            frame.position(0)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, 0, NativeCore.WIDTH, NativeCore.HEIGHT,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame,
            )
            needsFullUpload = false
        }

        computeRect(cfg, dst)
        if (dst != gameRect) gameRect = RectF(dst)
        // Clip-space quad (x, y, u, v) for the destination rectangle.
        val l = dst.left / surfaceW * 2f - 1f
        val r = dst.right / surfaceW * 2f - 1f
        val t = 1f - dst.top / surfaceH * 2f
        val b = 1f - dst.bottom / surfaceH * 2f
        quad.clear()
        verts[0] = l; verts[1] = b; verts[2] = 0f; verts[3] = 1f
        verts[4] = r; verts[5] = b; verts[6] = 1f; verts[7] = 1f
        verts[8] = l; verts[9] = t; verts[10] = 0f; verts[11] = 0f
        verts[12] = r; verts[13] = t; verts[14] = 1f; verts[15] = 0f
        quad.put(verts)
        quad.position(0)

        GLES20.glUseProgram(program)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform2f(uSrcSize, NativeCore.WIDTH.toFloat(), NativeCore.HEIGHT.toFloat())
        GLES20.glUniform2f(uDstSize, dst.width(), dst.height())
        GLES20.glUniform1i(uFilter, cfg.filter.ordinal)
        GLES20.glUniform1f(uStrength, cfg.filterStrength)
        GLES20.glUniform1f(uBrightness, cfg.brightness)
        GLES20.glUniform1f(uSaturation, cfg.saturation)
        GLES20.glUniform1i(uColorCorrect, if (cfg.colorCorrection) 1 else 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Re-uploads the frame on the next draw even if it hasn't changed (e.g. after surface loss). */
    fun invalidate() {
        needsFullUpload = true
    }

    private fun computeRect(cfg: DisplayConfig, out: RectF) {
        val area = cfg.area
        val ax = area?.left ?: 0f
        val ay = area?.top ?: 0f
        val aw = area?.width() ?: surfaceW.toFloat()
        val ah = area?.height() ?: surfaceH.toFloat()
        val w: Float
        val h: Float
        when (cfg.scaleMode) {
            ScaleMode.STRETCH -> {
                w = aw * cfg.screenSize
                h = ah * cfg.screenSize
            }
            ScaleMode.INTEGER -> {
                val s = max(1f, floor(min(aw / NativeCore.WIDTH, ah / NativeCore.HEIGHT) * cfg.screenSize))
                w = NativeCore.WIDTH * s
                h = NativeCore.HEIGHT * s
            }
            ScaleMode.FIT -> {
                val s = min(aw / NativeCore.WIDTH, ah / NativeCore.HEIGHT) * cfg.screenSize
                w = NativeCore.WIDTH * s
                h = NativeCore.HEIGHT * s
            }
        }
        // Snap to whole pixels so integer scaling stays perfectly crisp.
        val left = floor(ax + (aw - w) / 2f)
        val top = floor(ay + (ah - h) / 2f)
        out.set(left, top, left + floor(w), top + floor(h))
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "Shader error: " + GLES20.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "Link error: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        private const val TAG = "GameRenderer"

        private const val VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // uFilter: 0 none, 1 scanlines, 2 LCD grid, 3 CRT (matches ScreenFilter order).
        private const val FRAGMENT = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uSrcSize;
            uniform vec2 uDstSize;
            uniform int uFilter;
            uniform float uStrength;
            uniform float uBrightness;
            uniform float uSaturation;
            uniform int uColorCorrect;

            void main() {
                vec2 uv = vUv;
                float vignette = 1.0;
                if (uFilter == 3) {
                    vec2 cc = uv * 2.0 - 1.0;
                    cc *= 1.0 + 0.06 * uStrength * dot(cc.yx, cc.yx);
                    uv = cc * 0.5 + 0.5;
                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                        return;
                    }
                    vec2 v = uv * (1.0 - uv);
                    vignette = clamp(pow(v.x * v.y * 16.0, 0.25 * uStrength), 0.0, 1.0);
                }
                vec3 c = texture2D(uTex, uv).rgb;

                if (uColorCorrect == 1) {
                    // Approximation of the GBA's washed-out reflective LCD
                    // (after pokefan531's public-domain gba-color matrix).
                    vec3 lin = pow(c, vec3(2.2));
                    lin = mat3(0.82, 0.125, 0.195,
                               0.24, 0.665, 0.075,
                              -0.06, 0.21, 0.73) * lin;
                    c = pow(clamp(lin * 0.94, 0.0, 1.0), vec3(1.0 / 2.2));
                }

                float l = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(l), c, uSaturation) * uBrightness;

                vec2 pix = uv * uSrcSize;
                float scale = uDstSize.y / uSrcSize.y;
                if ((uFilter == 1 || uFilter == 3) && scale >= 2.0) {
                    float s = sin(fract(pix.y) * 3.14159265);
                    c *= mix(1.0, 0.25 + 0.75 * s, uStrength);
                }
                if (uFilter == 2 && scale >= 2.0) {
                    vec2 f = fract(pix);
                    float edge = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y));
                    float g = smoothstep(0.0, 0.18, edge);
                    c *= mix(1.0, 0.55 + 0.45 * g, uStrength);
                }
                if (uFilter == 3) {
                    float m = mod(floor(gl_FragCoord.x), 3.0);
                    vec3 mask = m < 1.0 ? vec3(1.0, 0.7, 0.7) : (m < 2.0 ? vec3(0.7, 1.0, 0.7) : vec3(0.7, 0.7, 1.0));
                    c *= mix(vec3(1.0), mask, 0.5 * uStrength);
                    c *= 1.0 + 0.25 * uStrength;
                    c *= vignette;
                }
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """
    }
}
