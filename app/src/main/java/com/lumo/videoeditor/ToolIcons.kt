package com.lumo.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Iconos de línea propios de cada herramienta, dibujados en una rejilla de 24x24.
 * [tool] es el nombre del valor de `Tool` (BRIGHTNESS, CONTRAST, ...).
 */
@androidx.compose.runtime.Composable
fun ToolIcon(tool: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val sw = 1.7f * s
        val line = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun DrawScope.l(x1: Float, y1: Float, x2: Float, y2: Float, a: Float = 1f) =
            drawLine(color.copy(alpha = a), p(x1, y1), p(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        fun DrawScope.circle(cx: Float, cy: Float, r: Float, fill: Boolean = false, a: Float = 1f) =
            drawCircle(color.copy(alpha = a), r * s, p(cx, cy), style = if (fill) Fill else line)
        fun DrawScope.rays(cx: Float, cy: Float, r1: Float, r2: Float, from: Int, to: Int, n: Int) {
            for (i in from until to) {
                val a = 2 * PI * i / n
                l(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat())
            }
        }

        when (tool) {
            "BRIGHTNESS" -> { circle(12f, 12f, 4f); rays(12f, 12f, 7.2f, 9.6f, 0, 8, 8) }
            "CONTRAST" -> {
                circle(12f, 12f, 8.5f)
                val half = Path().apply {
                    moveTo(12f * s, 3.5f * s); arcTo(androidx.compose.ui.geometry.Rect(p(3.5f, 3.5f), Size(17f * s, 17f * s)), -90f, 180f, false); close()
                }
                drawPath(half, color)
            }
            "HIGHLIGHTS" -> { circle(12f, 12f, 8.5f); l(7f, 8.5f, 17f, 8.5f); l(6f, 12f, 18f, 12f); l(7.5f, 15.5f, 16.5f, 15.5f, 0.35f) }
            "SHADOWS" -> { circle(12f, 12f, 8.5f); l(7.5f, 8.5f, 16.5f, 8.5f, 0.35f); l(6f, 12f, 18f, 12f); l(7f, 15.5f, 17f, 15.5f) }
            "SATURATION" -> {
                val d = Path().apply {
                    moveTo(12f * s, 3f * s)
                    cubicTo(12f * s, 3f * s, 5.5f * s, 10f * s, 5.5f * s, 14.5f * s)
                    arcTo(androidx.compose.ui.geometry.Rect(p(5.5f, 8.5f), Size(13f * s, 13f * s)), 180f, -180f, false)
                    cubicTo(18.5f * s, 10f * s, 12f * s, 3f * s, 12f * s, 3f * s)
                    close()
                }
                drawPath(d, color, style = line)
            }
            "VIBRANCE" -> {
                val d = Path().apply {
                    moveTo(10f * s, 4f * s)
                    cubicTo(10f * s, 4f * s, 4.5f * s, 10f * s, 4.5f * s, 14f * s)
                    arcTo(androidx.compose.ui.geometry.Rect(p(4.5f, 9f), Size(11f * s, 11f * s)), 180f, -180f, false)
                    cubicTo(15.5f * s, 10f * s, 10f * s, 4f * s, 10f * s, 4f * s)
                    close()
                }
                drawPath(d, color, style = line)
                l(19f, 4f, 19f, 9f); l(16.5f, 6.5f, 21.5f, 6.5f)
            }
            "TINT" -> { circle(12f, 12f, 8.5f); circle(12f, 12f, 3.6f, fill = true) }
            "HSL" -> { circle(9.5f, 9.5f, 5.3f); circle(14.5f, 9.5f, 5.3f); circle(12f, 14.5f, 5.3f) }
            "GRADE" -> {
                drawRoundRect(color, p(3.5f, 3.5f), Size(17f * s, 17f * s), CornerRadius(3f * s), style = line)
                l(7f, 9f, 17f, 9f, 1f); l(7f, 12f, 17f, 12f, 0.6f); l(7f, 15f, 17f, 15f, 0.3f)
            }
            "TEMPERATURE" -> {
                drawRoundRect(color, p(9.5f, 3.5f), Size(5f * s, 11f * s), CornerRadius(2.5f * s), style = line)
                circle(12f, 17.5f, 3.4f, fill = true); l(12f, 8f, 12f, 17f)
            }
            "GRAIN" -> {
                for (i in 0..2) for (j in 0..2) circle(6.5f + i * 5.5f, 6.5f + j * 5.5f, 1.3f, fill = true, a = if ((i + j) % 2 == 0) 1f else 0.5f)
            }
            "FADE" -> {
                drawRoundRect(color, p(3.5f, 3.5f), Size(17f * s, 17f * s), CornerRadius(3f * s), style = line)
                l(7f, 9f, 17f, 9f, 1f); l(7f, 12.5f, 17f, 12.5f, 0.55f); l(7f, 16f, 17f, 16f, 0.25f)
            }
            "SHARPEN" -> {
                val t = Path().apply { moveTo(12f * s, 4f * s); lineTo(20.5f * s, 19.5f * s); lineTo(3.5f * s, 19.5f * s); close() }
                drawPath(t, color, style = line)
                l(12f, 11f, 12f, 14.5f)
            }
            "TEXTURE" -> {
                for (k in 0..2) {
                    val y = 7f + k * 5f
                    val w = Path().apply {
                        moveTo(4f * s, y * s)
                        cubicTo(7f * s, (y - 3f) * s, 9f * s, (y - 3f) * s, 12f * s, y * s)
                        cubicTo(15f * s, (y + 3f) * s, 17f * s, (y + 3f) * s, 20f * s, y * s)
                    }
                    drawPath(w, color, style = line)
                }
            }
            "CLARITY" -> {
                val d = Path().apply { moveTo(12f * s, 3.5f * s); lineTo(20.5f * s, 12f * s); lineTo(12f * s, 20.5f * s); lineTo(3.5f * s, 12f * s); close() }
                drawPath(d, color, style = line)
                circle(12f, 12f, 2.6f, fill = true)
            }
            "LUT" -> {
                // Cubo: contorno hexagonal con las tres aristas que se juntan en el centro.
                val hex = Path().apply {
                    moveTo(12f * s, 3f * s); lineTo(19.5f * s, 7.5f * s); lineTo(19.5f * s, 16.5f * s)
                    lineTo(12f * s, 21f * s); lineTo(4.5f * s, 16.5f * s); lineTo(4.5f * s, 7.5f * s); close()
                }
                drawPath(hex, color, style = line)
                l(12f, 12f, 12f, 21f); l(12f, 12f, 4.5f, 7.5f); l(12f, 12f, 19.5f, 7.5f)
            }
            "DEFINITION" -> {
                // Destello de cuatro puntas: imagen "nítida y brillante".
                val st = Path().apply {
                    moveTo(12f * s, 3f * s); quadraticTo(13f * s, 11f * s, 21f * s, 12f * s)
                    quadraticTo(13f * s, 13f * s, 12f * s, 21f * s); quadraticTo(11f * s, 13f * s, 3f * s, 12f * s)
                    quadraticTo(11f * s, 11f * s, 12f * s, 3f * s); close()
                }
                drawPath(st, color, style = line)
            }
            "LIGHTEN" -> {
                circle(12f, 10f, 5.5f)
                l(9.5f, 18f, 14.5f, 18f); l(10.3f, 20.8f, 13.7f, 20.8f)
                l(12f, 2f, 12f, 3f)
            }
            "VIGNETTE" -> {
                drawRoundRect(color, p(3.5f, 4.5f), Size(17f * s, 15f * s), CornerRadius(3f * s), style = line)
                circle(12f, 12f, 4.6f, fill = true, a = 0.9f)
            }
            "WATERMARK" -> {
                // Marco de video con un sello en la esquina.
                drawRoundRect(color, p(3.5f, 5f), Size(17f * s, 14f * s), CornerRadius(3f * s), style = line)
                drawRoundRect(color, p(12f, 12.5f), Size(5.5f * s, 3.5f * s), CornerRadius(1f * s), style = Fill)
            }
            "LIGHT" -> {
                // Sol con rayos.
                circle(12f, 12f, 4f)
                l(12f, 3f, 12f, 5.5f); l(12f, 18.5f, 12f, 21f); l(3f, 12f, 5.5f, 12f); l(18.5f, 12f, 21f, 12f)
                l(5.6f, 5.6f, 7.4f, 7.4f); l(16.6f, 16.6f, 18.4f, 18.4f); l(5.6f, 18.4f, 7.4f, 16.6f); l(16.6f, 7.4f, 18.4f, 5.6f)
            }
            "TITLE" -> {
                // Una "T" de título.
                l(6f, 6.5f, 18f, 6.5f)
                l(12f, 6.5f, 12f, 18.5f)
                l(9.5f, 18.5f, 14.5f, 18.5f)
            }
            else -> circle(12f, 12f, 8f)
        }
    }
}
