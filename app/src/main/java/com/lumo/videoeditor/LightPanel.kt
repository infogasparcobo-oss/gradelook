package com.lumo.videoeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Colocar el sol: se toca o se arrastra sobre el video (mismo cuadro que ocupa la imagen) y el efecto de luz se mueve ahí.
 * Un círculo amarillo marca dónde está.
 */
@Composable
fun LightPickLayer(settings: LightSettings, aspect: Float, onChange: (LightSettings) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val latest by rememberUpdatedState(settings)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
        val dw = if (aspect > cw / ch) cw else ch * aspect
        val dh = if (aspect > cw / ch) cw / aspect else ch
        fun put(px: Float, py: Float) = onChange(latest.copy(enabled = true, x = (px / dw).coerceIn(-0.1f, 1.1f), y = (1f - py / dh).coerceIn(-0.1f, 1.15f)))
        Box(
            Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() })
                .pointerInput(Unit) { detectTapGestures { o -> put(o.x, o.y) } }
                .pointerInput(Unit) { detectDragGestures(onDragStart = { o -> put(o.x, o.y) }) { c, _ -> c.consume(); put(c.position.x, c.position.y) } },
        ) {
            val r = 20f * density.density
            Box(
                Modifier.offset { IntOffset((settings.x * dw - r).roundToInt(), ((1f - settings.y) * dh - r).roundToInt()) }
                    .size(40.dp).border(BorderStroke(2.dp, Palette.accent), CircleShape).background(Color(0x33FFE060), CircleShape),
            )
        }
    }
}

/** Panel de luz. Arriba: interruptor y efectos listos; abajo, ajustes finos. Tocar el video coloca el sol. */
@Composable
fun LightPanel(settings: LightSettings, onChange: (LightSettings) -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(228.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.enabled, onCheckedChange = { onChange(settings.copy(enabled = it)) })
            Row(Modifier.weight(1f).padding(start = 6.dp)) {
                ChipRow {
                    LightLook.presets.forEach { p ->
                        // Un efecto listo cambia posición, color y fuerza; el movimiento se conserva como lo tenías.
                        Chip(p.name, settings.name == p.name) { onChange(p.s.copy(anim = settings.anim)) }
                    }
                }
            }
            TextButton(onClick = onClose) { Text("✓", fontSize = 24.sp, color = Palette.accent) }
        }
        Text("Toca o arrastra sobre el video para colocar el sol.", fontSize = 12.sp, color = Palette.textSoft)
        SliderRow("Intensidad", settings.intensity) { onChange(settings.copy(enabled = true, intensity = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Rayos", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(78.dp))
            Slider(value = settings.rays, onValueChange = { onChange(settings.copy(enabled = true, rays = it)) }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
            Text("Halo", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.padding(start = 8.dp))
            Slider(value = settings.glow, onValueChange = { onChange(settings.copy(enabled = true, glow = it)) }, valueRange = 0f..1f, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LightLook.colors.forEachIndexed { i, c ->
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(c.argb))
                        .border(BorderStroke(if (settings.color == i) 3.dp else 1.dp, if (settings.color == i) Palette.accent else Palette.border), CircleShape)
                        .clickable { onChange(settings.copy(enabled = true, color = i)) },
                )
            }
            Chip("Movimiento", settings.anim) { onChange(settings.copy(anim = !settings.anim)) }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(78.dp))
        Slider(value = value, onValueChange = onValue, valueRange = 0f..1f, modifier = Modifier.weight(1f))
    }
}
