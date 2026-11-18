@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import java.io.File
import kotlin.math.min

/** Marca de agua de creador: texto o logo propio, fija en una esquina o saltando de esquina cada pocos segundos. */
data class WatermarkSettings(
    val enabled: Boolean = false,
    val text: String = "",
    val useLogo: Boolean = false,
    /** true = cambia de esquina cada [periodSec] segundos; false = se queda en [corner]. */
    val dynamic: Boolean = true,
    /** 0 = arriba izquierda, 1 = arriba derecha, 2 = abajo izquierda, 3 = abajo derecha. */
    val corner: Int = 3,
    val periodSec: Int = 5,
    /** Ancho de la marca como fracción del ancho del video. */
    val size: Float = 0.22f,
    val opacity: Float = 0.85f,
    /** Tipo de letra: índice en [WatermarkLook.fonts]. */
    val font: Int = 0,
    /** Color del texto: índice en [WatermarkLook.colors]. */
    val color: Int = 0,
    /** Animación: 0 ninguna, 1 parpadeo, 2 pulso suave. */
    val anim: Int = 0,
    /** Velocidad de la animación: 0 lenta, 1 media, 2 rápida. */
    val animSpeed: Int = 1,
    /** true = recorre el video sin parar en forma de X (arriba izq. → abajo der. → arriba der. → abajo izq.); [periodSec] = segundos por tramo. */
    val glide: Boolean = false,
    /** Ruta del recorrido: 0 = X, 1 = diagonal ↘↖ de ida y vuelta, 2 = diagonal ↙↗ de ida y vuelta. */
    val glidePath: Int = 0,
)

object WatermarkLook {
    /** Nombre mostrado, familia de Android y estilo. */
    class Font(val label: String, val family: String, val style: Int)
    val fonts = listOf(
        Font("Moderna", "sans-serif", Typeface.BOLD),
        Font("Gruesa", "sans-serif-black", Typeface.NORMAL),
        Font("Condensada", "sans-serif-condensed", Typeface.BOLD),
        Font("Elegante", "serif", Typeface.BOLD_ITALIC),
        Font("Máquina", "monospace", Typeface.BOLD),
        Font("Manuscrita", "cursive", Typeface.BOLD),
        Font("Casual", "casual", Typeface.NORMAL),
    )
    class Swatch(val label: String, val argb: Int)
    val colors = listOf(
        Swatch("Blanco", Color.WHITE), Swatch("Amarillo", Color.rgb(255, 224, 80)), Swatch("Negro", Color.BLACK),
        Swatch("Rojo", Color.rgb(255, 80, 80)), Swatch("Celeste", Color.rgb(100, 210, 255)),
    )
    val animNames = listOf("Ninguna", "Parpadeo", "Pulso")
    val speedHz = floatArrayOf(0.5f, 1f, 2f)
}

object WatermarkStore {
    private fun prefs(c: Context) = c.getSharedPreferences("gradelook_watermark", Context.MODE_PRIVATE)

    fun load(c: Context): WatermarkSettings {
        val p = prefs(c)
        return WatermarkSettings(
            enabled = p.getBoolean("enabled", false), text = p.getString("text", "") ?: "",
            useLogo = p.getBoolean("logo", false), dynamic = p.getBoolean("dynamic", true),
            corner = p.getInt("corner", 3), periodSec = p.getInt("period", 5),
            size = p.getFloat("size", 0.22f), opacity = p.getFloat("opacity", 0.85f),
            font = p.getInt("font", 0), color = p.getInt("color", 0), anim = p.getInt("anim", 0), animSpeed = p.getInt("animSpeed", 1),
            glide = p.getBoolean("glide", false), glidePath = p.getInt("glidePath", 0),
        )
    }

    fun save(c: Context, s: WatermarkSettings) {
        prefs(c).edit().putBoolean("enabled", s.enabled).putString("text", s.text).putBoolean("logo", s.useLogo)
            .putBoolean("dynamic", s.dynamic).putInt("corner", s.corner).putInt("period", s.periodSec)
            .putFloat("size", s.size).putFloat("opacity", s.opacity)
            .putInt("font", s.font).putInt("color", s.color).putInt("anim", s.anim).putInt("animSpeed", s.animSpeed).putBoolean("glide", s.glide).putInt("glidePath", s.glidePath).apply()
    }

    fun logoFile(c: Context) = File(c.filesDir, "watermark_logo.png")

    /** Copia el logo elegido (reducido a 512 px como máximo) a la memoria de la app. */
    fun importLogo(c: Context, uri: Uri): Boolean = try {
        val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val k = min(1f, 512f / maxOf(bmp.width, bmp.height))
        val out = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp
        logoFile(c).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) { false }
}

/** Valores compartidos entre la interfaz y el hilo de render (vista previa y exportación). */
class WatermarkHolder {
    @Volatile var settings = WatermarkSettings()
    /** Solo con plan Pro; sin él la marca no se dibuja aunque esté activada. */
    @Volatile var pro = false
    @Volatile private var bitmap: Bitmap? = null

    val active: Boolean get() = settings.enabled && pro && bitmap != null
    fun bitmap(): Bitmap? = bitmap

    fun update(context: Context, s: WatermarkSettings, isPro: Boolean) {
        settings = s; pro = isPro
        bitmap = Watermark.render(context, s)
    }
}

object Watermark {
    private const val BASE_TEXT_PX = 120f

    /** Imagen de la marca (texto con sombra suave o el logo con su transparencia); null si no hay nada que dibujar. */
    fun render(context: Context, s: WatermarkSettings): Bitmap? {
        if (s.useLogo) {
            val f = WatermarkStore.logoFile(context)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { return it }
        }
        val text = s.text.trim()
        if (text.isEmpty()) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val c = WatermarkLook.colors[s.color.coerceIn(0, WatermarkLook.colors.size - 1)].argb
            color = c
            textSize = BASE_TEXT_PX
            val f = WatermarkLook.fonts[s.font.coerceIn(0, WatermarkLook.fonts.size - 1)]
            typeface = Typeface.create(Typeface.create(f.family, Typeface.NORMAL), f.style)
            // Sombra oscura (clara si el texto es negro) para que se lea sobre cualquier fondo.
            val shadow = if (c == Color.BLACK) Color.argb(190, 255, 255, 255) else Color.argb(170, 0, 0, 0)
            setShadowLayer(BASE_TEXT_PX * 0.08f, 0f, BASE_TEXT_PX * 0.04f, shadow)
        }
        val pad = (BASE_TEXT_PX * 0.3f).toInt()
        val w = paint.measureText(text).toInt() + pad * 2
        val h = (BASE_TEXT_PX * 1.25f).toInt() + pad
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, pad.toFloat(), pad / 2f + BASE_TEXT_PX, paint)
        return bmp
    }

    /** Efecto de Media3 listo para añadir a la cadena (vista previa o exportación); la marca va por encima de los filtros. */
    fun effect(holder: WatermarkHolder) = OverlayEffect(listOf(WatermarkOverlay(holder)))
}

/**
 * Superposición cuya posición depende del tiempo: en modo dinámico salta de esquina cada `periodSec` segundos
 * (arriba izq. → abajo der. → arriba der. → abajo izq.) con un fundido corto para que no "parpadee".
 */
class WatermarkOverlay(private val holder: WatermarkHolder) : BitmapOverlay() {
    private var frame = Size(1920, 1080)
    private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val order = intArrayOf(0, 3, 1, 2)

    override fun configure(videoSize: Size) { frame = videoSize }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = if (holder.active) holder.bitmap() ?: empty else empty

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val s = holder.settings
        val bmp = holder.bitmap()
        if (!holder.active || bmp == null) return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        val t = presentationTimeUs / 1_000_000.0
        var corner = 3
        var alpha = s.opacity
        // Posición como fracción (0..1) del recorrido; y=1 es arriba. Solo se usa en modo recorrido.
        var gx = 1f; var gy = 0f
        if (s.glide) {
            val period = s.periodSec.coerceAtLeast(2).toDouble()
            val xs: FloatArray; val ys: FloatArray
            if (s.glidePath == 1) {
                // Diagonal de ida y vuelta: ↘ → ↖ → ↘ …
                xs = floatArrayOf(1f, 0f); ys = floatArrayOf(0f, 1f)
            } else if (s.glidePath == 2) {
                // La otra diagonal: ↙ → ↗ → ↙ …
                xs = floatArrayOf(0f, 1f); ys = floatArrayOf(0f, 1f)
            } else {
                // Tramos: ↖→↘, ↘→↗, ↗→↙, ↙→↖ (las dos diagonales forman la X).
                xs = floatArrayOf(0f, 1f, 1f, 0f); ys = floatArrayOf(1f, 0f, 1f, 0f)
            }
            val n = xs.size
            val leg = ((t / period).toInt()).mod(n)
            val k = ((t % period) / period).toFloat()
            val nx = (leg + 1) % n
            gx = xs[leg] + (xs[nx] - xs[leg]) * k
            gy = ys[leg] + (ys[nx] - ys[leg]) * k
        } else if (s.dynamic) {
            val period = s.periodSec.coerceAtLeast(2)
            corner = order[((t / period).toInt()).mod(4)]
            val f = t % period
            val fade = 0.35
            alpha *= minOf(1.0, f / fade, (period - f) / fade).coerceIn(0.0, 1.0).toFloat()
        } else corner = s.corner
        if (s.anim != 0) {
            val hz = WatermarkLook.speedHz[s.animSpeed.coerceIn(0, 2)]
            val ph = (t * hz) % 1.0
            alpha *= if (s.anim == 1) {
                // Parpadeo: encendido la mitad del ciclo, con un borde corto para que no sea un corte seco.
                val on = if (ph < 0.5) minOf(1.0, ph / 0.06) else maxOf(0.0, 1.0 - (ph - 0.5) / 0.06)
                on.toFloat()
            } else {
                // Pulso: sube y baja de forma suave entre 35 % y 100 %.
                (0.35 + 0.65 * (0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * ph))).toFloat()
            }
        }
        if (!s.glide) {
            gx = if (corner == 1 || corner == 3) 1f else 0f
            gy = if (corner == 0 || corner == 1) 1f else 0f
        }
        val marginPx = frame.width * 0.035f
        val mx = 2f * marginPx / frame.width
        val my = 2f * marginPx / frame.height
        // El ancla de la marca sigue a la posición, así nunca se sale del cuadro.
        val bgX = (-1f + mx) + (2f - 2f * mx) * gx
        val bgY = (-1f + my) + (2f - 2f * my) * gy
        val scale = (s.size * frame.width) / bmp.width
        return StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(-1f + 2f * gx, -1f + 2f * gy)
            .setBackgroundFrameAnchor(bgX, bgY)
            .setScale(scale, scale)
            .setAlphaScale(alpha)
            .build()
    }
}
