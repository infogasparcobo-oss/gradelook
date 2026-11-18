@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Miniaturas de filtros: el mismo shader de ajustes que usa el video, dibujado en una superficie fuera de pantalla
 * sobre un fotograma del video actual. Así la miniatura es exactamente lo que verá el usuario al aplicar el filtro.
 */
object FilterThumbs {
    const val SIZE = 176

    /** Fotograma cuadrado (recorte central) de [uri] hacia la mitad del video, ya reducido a [SIZE]. */
    fun frame(context: Context, uri: Uri, durationMs: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val at = (if (durationMs > 0) durationMs / 3 else 1000L) * 1000L
            val src = r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val side = min(src.width, src.height)
            val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    /** Dibuja [frame] con cada conjunto de ajustes; la lista de salida sigue el orden de [snaps]. Null si falla EGL. */
    fun render(frame: Bitmap, snaps: List<AdjustmentSnapshot>): List<Bitmap>? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, n, 0) || n[0] == 0) return null
        val ctx = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (ctx == EGL14.EGL_NO_CONTEXT) return null
        val w = frame.width; val h = frame.height
        val surf = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE), 0)
        val out = ArrayList<Bitmap>(snaps.size)
        var program: GlProgram? = null
        val tex = IntArray(1)
        try {
            if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, frame, 0)
            program = GlProgram(AdjustmentsShaderProgram.VERTEX_SHADER, AdjustmentsShaderProgram.FRAGMENT_SHADER)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            val buf = ByteBuffer.allocateDirect(w * h * 4)
            for (s in snaps) {
                GLES20.glViewport(0, 0, w, h)
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", tex[0], 0)
                setAdjustmentUniforms(program, s, 1f / w, 1f / h, 0f)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                buf.rewind()
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                buf.rewind()
                out.add(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buf) })
            }
            return out
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { program?.delete() }
            runCatching { GLES20.glDeleteTextures(1, tex, 0) }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surf != null) EGL14.eglDestroySurface(display, surf)
            EGL14.eglDestroyContext(display, ctx)
        }
    }
}
