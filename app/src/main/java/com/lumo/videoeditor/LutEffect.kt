@file:OptIn(UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File

/** LUT 3D ya leída: [size]^3 colores RGB de 8 bits en el orden del formato .cube (el rojo cambia más rápido). */
class LutData(val name: String, val size: Int, val rgb: ByteArray)

/** Valores que lee el efecto en cada fotograma. [enabled] = false muestra el video sin LUT (comparar con el original). */
class LutHolder {
    @Volatile var lut: LutData? = null
    @Volatile var mix: Float = 1f
    @Volatile var enabled: Boolean = true
}

/** Lector de archivos .cube (Adobe/Resolve) de 3D. Las de 65 puntos se reducen a 33 (la textura no cabe en todos los móviles). */
object CubeLut {
    fun parse(name: String, text: String): LutData {
        var size = 0
        val dmin = floatArrayOf(0f, 0f, 0f)
        val dmax = floatArrayOf(1f, 1f, 1f)
        var values = FloatArray(0)
        var count = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val p = line.split(Regex("\\s+"))
            when {
                p[0] == "TITLE" -> {}
                p[0] == "LUT_1D_SIZE" -> throw IllegalArgumentException("Solo se admiten LUTs 3D")
                p[0] == "LUT_3D_SIZE" -> {
                    size = p.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Tamaño de LUT no válido")
                    if (size !in 2..129) throw IllegalArgumentException("Tamaño de LUT no admitido ($size)")
                    values = FloatArray(size * size * size * 3)
                }
                p[0] == "DOMAIN_MIN" && p.size >= 4 -> for (i in 0..2) dmin[i] = p[i + 1].toFloat()
                p[0] == "DOMAIN_MAX" && p.size >= 4 -> for (i in 0..2) dmax[i] = p[i + 1].toFloat()
                p[0][0].isDigit() || p[0][0] == '-' || p[0][0] == '.' -> {
                    if (size == 0) throw IllegalArgumentException("Falta LUT_3D_SIZE")
                    if (p.size < 3 || count + 3 > values.size) throw IllegalArgumentException("Datos de LUT incorrectos")
                    for (i in 0..2) values[count + i] = p[i].toFloat()
                    count += 3
                }
            }
        }
        if (size == 0 || count != values.size) throw IllegalArgumentException("La LUT está incompleta")
        // Reducir 65 -> 33 (cada 2 puntos) para que la textura (N*N x N) quepa en cualquier GPU.
        var step = 1
        var n = size
        if (size > 33 && (size - 1) % 32 == 0) { step = (size - 1) / 32; n = 33 }
        else if (size > 64) throw IllegalArgumentException("LUT demasiado grande ($size)")
        val out = ByteArray(n * n * n * 3)
        var o = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val src = (((b * step) * size + g * step) * size + r * step) * 3
            for (c in 0..2) {
                val v = ((values[src + c] - dmin[c]) / (dmax[c] - dmin[c])).coerceIn(0f, 1f)
                out[o++] = (v * 255f + 0.5f).toInt().toByte()
            }
        }
        return LutData(name, n, out)
    }
}

/** LUTs importadas por el usuario, guardadas en la carpeta privada de la app. */
object LutStore {
    private fun dir(c: Context) = File(c.filesDir, "luts").apply { mkdirs() }

    fun list(c: Context): List<String> =
        dir(c).listFiles { f -> f.extension == "cube" }?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    /** Copia el archivo elegido, lo valida y devuelve su nombre. */
    fun import(c: Context, uri: Uri): Result<String> = runCatching {
        val text = c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalArgumentException("No se pudo leer el archivo")
        val rawName = (uri.lastPathSegment ?: "LUT").substringAfterLast('/').substringAfterLast(':').removeSuffix(".cube")
        val name = rawName.replace(Regex("[^A-Za-z0-9 _\\-]"), "_").take(40).ifBlank { "LUT" }
        CubeLut.parse(name, text)   // si no es válida, lanza antes de guardar nada
        File(dir(c), "$name.cube").writeText(text)
        name
    }

    fun load(c: Context, name: String): LutData = CubeLut.parse(name, File(dir(c), "$name.cube").readText())

    fun delete(c: Context, name: String) { File(dir(c), "$name.cube").delete() }
}

/** Aplica una LUT 3D (conversión de color o look) sobre el video, antes de los demás ajustes. */
class LutEffect(private val holder: LutHolder) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LutShaderProgram(holder, useHdr)
}

private class LutShaderProgram(private val holder: LutHolder, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var texId = 0
    private var uploaded: LutData? = null

    init {
        try {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    /** La LUT se sube a la GPU como una tira de N cortes (azul) de N x N: textura (N*N) x N. */
    private fun upload(l: LutData) {
        if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        val n = l.size
        val px = IntArray(n * n * n)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val s = ((b * n + g) * n + r) * 3
            val c = (255 shl 24) or ((l.rgb[s].toInt() and 255) shl 16) or ((l.rgb[s + 1].toInt() and 255) shl 8) or (l.rgb[s + 2].toInt() and 255)
            px[g * (n * n) + b * n + r] = c
        }
        val bmp = Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        uploaded = l
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val l = holder.lut
            if (l != null && uploaded !== l) upload(l)
            val on = l != null && holder.enabled && texId != 0
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            // Sin LUT se enlaza la propia imagen de entrada (textura válida) y la mezcla es 0.
            program.setSamplerTexIdUniform("uLut", if (on) texId else inputTexId, 1)
            program.setFloatUniform("uSize", (l?.size ?: 2).toFloat())
            program.setFloatUniform("uMix", if (on) holder.mix else 0f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        // Interpolación trilineal a mano: bilineal dentro de cada corte azul (la textura) y mezcla entre dos cortes.
        const val FRAGMENT_SHADER = """
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform sampler2D uLut;
uniform float uSize;
uniform float uMix;

vec3 lutLookup(vec3 c) {
  float n = uSize;
  vec3 s = clamp(c, 0.0, 1.0) * (n - 1.0);
  float b0 = floor(s.b);
  float b1 = min(b0 + 1.0, n - 1.0);
  float fb = s.b - b0;
  float w = n * n;
  vec2 uv0 = vec2((b0 * n + s.r + 0.5) / w, (s.g + 0.5) / n);
  vec2 uv1 = vec2((b1 * n + s.r + 0.5) / w, (s.g + 0.5) / n);
  return mix(texture2D(uLut, uv0).rgb, texture2D(uLut, uv1).rgb, fb);
}

void main() {
  vec3 c = texture2D(uTexSampler, vTexSamplingCoord).rgb;
  gl_FragColor = vec4(mix(c, lutLookup(c), uMix), 1.0);
}
"""
    }
}
