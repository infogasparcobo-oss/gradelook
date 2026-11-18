package com.lumo.videoeditor

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Estilo inspirado en Evoto (colores medidos de una captura): tema oscuro casi negro, superficies gris
 * carbón, amarillo de acento y texto blanco. La tipografía es la sans del sistema (la de Evoto se ve
 * igual de limpia), con títulos en peso medio.
 */
object Palette {
    val background = Color(0xFF16181A)    // fondo de pantalla
    val surface = Color(0xFF222426)       // tarjetas, hojas y botones circulares
    val bar = Color(0xFF2D2D2F)           // barra inferior en forma de píldora
    val chip = Color(0xFF222426)
    val border = Color(0xFF3A3C3E)
    val text = Color(0xFFFFFFFF)          // texto principal
    val textSoft = Color(0xFFA5A5A5)      // texto secundario
    val accent = Color(0xFFFFE050)        // amarillo de acento
    val onAccent = Color(0xFF000000)      // texto sobre el amarillo
    val accentTint = Color(0xFF3A3C3E)    // fondo de elementos seleccionados
    val black = Color(0xFF000000)
    val scrim = Color(0x99000000)

    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentTint,
        onPrimaryContainer = text,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = accentTint,
        onSecondaryContainer = text,
        background = background,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = chip,
        onSurfaceVariant = textSoft,
        outline = border,
        outlineVariant = border,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = bar,
    )

    private val sans = FontFamily.SansSerif
    val typography = Typography(
        titleLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        labelLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    )
}
