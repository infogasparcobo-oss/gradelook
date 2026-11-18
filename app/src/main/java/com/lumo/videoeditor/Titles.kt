@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lumo.videoeditor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.min
import kotlin.math.pow

/** Un título: texto con estilo, tramo de tiempo (en la línea de tiempo del video final) y animaciones de entrada y salida. */
data class TitleItem(
    val id: Long,
    val text: String = "Título",
    val startMs: Long = 0L,
    val endMs: Long = 3000L,
    /** Índice en [TitleLook.fonts]. */
    val font: Int = 1,
    /** Índice en [TitleLook.colors]. */
    val color: Int = 0,
    /** Fondo: índice en [TitleLook.backgrounds]. */
    val bg: Int = 0,
    /** Alto del texto como fracción del ancho del video. */
    val size: Float = 0.075f,
    /** Posición del centro: 0 = borde izquierdo/superior, 1 = borde derecho/inferior. Se arrastra sobre el video. */
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    /** Animación al entrar y al salir: índice en [TitleLook.anims]. */
    val animIn: Int = 1,
    val animOut: Int = 1,
    /** Duración del efecto de aparición y de desaparición, en ms. */
    val animMs: Long = 500L,
    /** Contorno alrededor de las letras (negro; blanco si el texto es negro). */
    val outline: Boolean = false,
    val caps: Boolean = false,
)

/** Estilo completo de un toque (tipo de letra, color, fondo, contorno, mayúsculas y tamaño). */
class TitleStyle(val name: String, val font: Int, val color: Int, val bg: Int, val outline: Boolean, val caps: Boolean, val size: Float)

object TitleLook {
    const val MAX = 6
    const val ANIM_MS = 450L

    val fonts = listOf(
        WatermarkLook.Font("Moderna", "sans-serif", Typeface.BOLD),
        WatermarkLook.Font("Gruesa", "sans-serif-black", Typeface.NORMAL),
        WatermarkLook.Font("Media", "sans-serif-medium", Typeface.NORMAL),
        WatermarkLook.Font("Fina", "sans-serif-light", Typeface.NORMAL),
        WatermarkLook.Font("Delgada", "sans-serif-thin", Typeface.NORMAL),
        WatermarkLook.Font("Condensada", "sans-serif-condensed", Typeface.BOLD),
        WatermarkLook.Font("Elegante", "serif", Typeface.BOLD_ITALIC),
        WatermarkLook.Font("Clásica", "serif", Typeface.BOLD),
        WatermarkLook.Font("Máquina", "monospace", Typeface.BOLD),
        WatermarkLook.Font("Manuscrita", "cursive", Typeface.BOLD),
        WatermarkLook.Font("Casual", "casual", Typeface.NORMAL),
    )

    class Swatch(val label: String, val argb: Int)
    val colors = listOf(
        Swatch("Blanco", Color.WHITE), Swatch("Negro", Color.BLACK), Swatch("Amarillo", Color.rgb(255, 224, 60)),
        Swatch("Naranja", Color.rgb(255, 150, 40)), Swatch("Rojo", Color.rgb(240, 60, 60)), Swatch("Rosa", Color.rgb(255, 110, 180)),
        Swatch("Morado", Color.rgb(170, 100, 255)), Swatch("Azul", Color.rgb(60, 110, 255)), Swatch("Celeste", Color.rgb(100, 210, 255)),
        Swatch("Verde", Color.rgb(60, 200, 110)), Swatch("Lima", Color.rgb(190, 255, 70)), Swatch("Dorado", Color.rgb(235, 190, 90)),
    )

    class Bg(val label: String, val argb: Int)
    val backgrounds = listOf(
        Bg("Sin fondo", 0), Bg("Caja oscura", Color.argb(190, 0, 0, 0)), Bg("Caja clara", Color.argb(225, 255, 255, 255)),
        Bg("Caja amarilla", Color.rgb(255, 224, 60)), Bg("Caja roja", Color.rgb(230, 50, 60)), Bg("Caja azul", Color.rgb(40, 90, 230)),
    )
    val anims = listOf("Ninguna", "Fundido", "Subir", "Bajar", "Izquierda", "Derecha", "Zoom", "Rebote")
    /** Duraciones del efecto: rápida, normal y lenta. */
    val animSpeeds = listOf("Rápido" to 300L, "Normal" to 500L, "Lento" to 900L)

    /** Estilos listos: con un toque cambian todo el aspecto del título. */
    val styles = listOf(
        TitleStyle("Clásico", 0, 0, 0, false, false, 0.075f),
        TitleStyle("Impacto", 1, 2, 0, true, true, 0.09f),
        TitleStyle("Cine", 3, 0, 0, false, true, 0.05f),
        TitleStyle("Caja", 0, 1, 3, false, false, 0.065f),
        TitleStyle("Elegante", 6, 11, 0, false, false, 0.08f),
        TitleStyle("Manuscrito", 9, 0, 0, false, false, 0.10f),
        TitleStyle("Retro", 8, 10, 1, false, false, 0.06f),
        TitleStyle("Rojo", 5, 0, 4, false, true, 0.07f),
    )
}

/** Títulos compartidos entre la interfaz y el hilo de dibujo. En la vista previa el reloj lo da el reproductor ([live]). */
class TitleHolder {
    @Volatile var titles: List<TitleItem> = emptyList()
    @Volatile var bitmaps: List<Bitmap?> = emptyList()
    /** true = el tiempo viene de [liveMs] (vista previa); false = del fotograma que se codifica (exportación). */
    @Volatile var live = false
    @Volatile var liveMs = 0L
    /** Mientras se edita, los títulos los dibuja la interfaz encima del video (se mueven con el dedo); el video no los repite. */
    @Volatile var suppress = false

    val active: Boolean get() = bitmaps.any { it != null }

    fun update(list: List<TitleItem>) {
        val l = list.take(TitleLook.MAX)
        // Solo se vuelve a dibujar la imagen de un título si cambió algo de su aspecto (no al moverlo o cambiar tiempos).
        val old = titles; val oldB = bitmaps
        bitmaps = l.mapIndexed { i, t ->
            val o = old.getOrNull(i)
            if (o != null && o.id == t.id && o.text == t.text && o.font == t.font && o.color == t.color && o.bg == t.bg &&
                o.outline == t.outline && o.caps == t.caps) oldB.getOrNull(i) else Titles.render(t)
        }
        titles = l
    }
}

/** Lugar de un título dentro del cuadro: escala de su imagen y centro (fracciones del ancho y del alto, desde arriba a la izquierda). */
class TitleBox(val scale: Float, val cx: Float, val cy: Float)

object Titles {
    private const val BASE_PX = 100f

    /** Estado de un título en el instante [t] (ms): visibilidad, escala y desplazamiento (fracción del cuadro; +x derecha, +y abajo). */
    class Anim(val alpha: Float, val scale: Float, val dx: Float, val dy: Float)

    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }

    /** null si el título no está visible en [t]. Es la misma cuenta para el video y para la edición. */
    fun anim(item: TitleItem, t: Long): Anim? {
        if (t < item.startMs || t > item.endMs) return null
        val d = item.animMs.coerceIn(100L, ((item.endMs - item.startMs) / 2).coerceAtLeast(100L)).toFloat()
        val pIn = ((t - item.startMs) / d).coerceIn(0f, 1f)
        val pOut = ((item.endMs - t) / d).coerceIn(0f, 1f)
        var a = 1f; var s = 1f; var dx = 0f; var dy = 0f
        val mag = 0.18f
        fun go(kind: Int, p: Float, exit: Boolean) {
            if (p >= 1f || kind == 0) return
            val e = (1f - p) * (1f - p)
            val k = if (exit) -1f else 1f
            when (kind) {
                1 -> a *= p
                2 -> { a *= p; dy += k * e * mag }      // sube: entra desde abajo y sale hacia arriba
                3 -> { a *= p; dy -= k * e * mag }      // baja: entra desde arriba y sale hacia abajo
                4 -> { a *= p; dx -= k * e * mag }      // entra desde la izquierda y sale hacia la derecha
                5 -> { a *= p; dx += k * e * mag }      // entra desde la derecha y sale hacia la izquierda
                6 -> { a *= p; s *= 0.4f + 0.6f * p }
                7 -> { a *= minOf(1f, p * 3f); s *= if (exit) 0.3f + 0.7f * p else 0.3f + 0.7f * easeOutBack(p) }
            }
        }
        go(item.animIn, pIn, false)
        go(item.animOut, pOut, true)
        return Anim(a, s, dx, dy)
    }

    /** Misma regla para el video y para la vista de edición, así lo que se ve al editar es lo que se exporta. */
    fun place(item: TitleItem, bmpW: Int, bmpH: Int, frameW: Float, frameH: Float): TitleBox {
        val scale = min(item.size * frameW / BASE_PX, 0.94f * frameW / bmpW)
        val halfW = min(0.5f, bmpW * scale / 2f / frameW)
        val halfH = min(0.5f, bmpH * scale / 2f / frameH)
        val cx = (0.05f + 0.9f * item.posX).coerceIn(halfW, 1f - halfW)
        val cy = (0.05f + 0.9f * item.posY).coerceIn(halfH, 1f - halfH)
        return TitleBox(scale, cx, cy)
    }

    /** Imagen del título (varias líneas con \n, tipo de letra, color, contorno y fondo opcional); null si no hay texto. */
    fun render(t: TitleItem): Bitmap? {
        val raw = if (t.caps) t.text.uppercase() else t.text
        val lines = raw.split("\n").map { it.trimEnd() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null
        val c = TitleLook.colors[t.color.coerceIn(0, TitleLook.colors.size - 1)].argb
        val bg = TitleLook.backgrounds[t.bg.coerceIn(0, TitleLook.backgrounds.size - 1)].argb
        val f = TitleLook.fonts[t.font.coerceIn(0, TitleLook.fonts.size - 1)]
        val tf = Typeface.create(Typeface.create(f.family, Typeface.NORMAL), f.style)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c
            textSize = BASE_PX
            typeface = tf
            textAlign = Paint.Align.CENTER
            // Sin fondo ni contorno: sombra para que se lea sobre cualquier imagen (clara si el texto es negro).
            if (bg == 0 && !t.outline) setShadowLayer(BASE_PX * 0.09f, 0f, BASE_PX * 0.045f, if (c == Color.BLACK) Color.argb(190, 255, 255, 255) else Color.argb(180, 0, 0, 0))
        }
        val lineH = BASE_PX * 1.25f
        val pad = if (bg != 0) BASE_PX * 0.35f else BASE_PX * 0.3f
        val textW = lines.maxOf { paint.measureText(it) }
        val w = (textW + pad * 2).toInt().coerceAtLeast(2)
        val h = (lineH * lines.size + pad * 2 - BASE_PX * 0.25f).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        if (bg != 0) cv.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), BASE_PX * 0.3f, BASE_PX * 0.3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val stroke = if (t.outline) Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = BASE_PX * 0.16f; strokeJoin = Paint.Join.ROUND
            color = if (c == Color.BLACK) Color.WHITE else Color.BLACK
            textSize = BASE_PX; typeface = tf; textAlign = Paint.Align.CENTER
        } else null
        lines.forEachIndexed { i, s ->
            val y = pad + BASE_PX * 0.85f + lineH * i - BASE_PX * 0.05f
            stroke?.let { cv.drawText(s, w / 2f, y, it) }
            cv.drawText(s, w / 2f, y, paint)
        }
        return bmp
    }

    /** Un solo efecto con [TitleLook.MAX] casillas fijas: cada una dibuja el título de su índice (o nada). */
    fun effect(holder: TitleHolder) = OverlayEffect(List(TitleLook.MAX) { TitleOverlay(holder, it) })
}

class TitleOverlay(private val holder: TitleHolder, private val index: Int) : BitmapOverlay() {
    private var frame = Size(1920, 1080)
    private val empty: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun configure(videoSize: Size) { frame = videoSize }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = holder.bitmaps.getOrNull(index) ?: empty

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        if (holder.suppress) return hidden
        val item = holder.titles.getOrNull(index) ?: return hidden
        val bmp = holder.bitmaps.getOrNull(index) ?: return hidden
        val t = if (holder.live) holder.liveMs else presentationTimeUs / 1000
        val an = Titles.anim(item, t) ?: return hidden

        val box = Titles.place(item, bmp.width, bmp.height, frame.width.toFloat(), frame.height.toFloat())
        return StaticOverlaySettings.Builder()
            .setOverlayFrameAnchor(0f, 0f)
            .setBackgroundFrameAnchor(2f * (box.cx + an.dx) - 1f, 1f - 2f * (box.cy + an.dy))
            .setScale(box.scale * an.scale, box.scale * an.scale)
            .setAlphaScale(an.alpha)
            .build()
    }
}
