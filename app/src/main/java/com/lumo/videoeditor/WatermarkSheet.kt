package com.lumo.videoeditor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fila de chips con desplazamiento horizontal. */
@Composable
internal fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp, maxLines = 1) })
}

/**
 * Panel de la marca de agua. No tapa el video: se coloca en la zona de herramientas (como los deslizadores)
 * y todo se ve y se puede reproducir mientras se ajusta. Tres pestañas cortas en vez de una lista larga.
 */
@Composable
fun WatermarkPanel(settings: WatermarkSettings, onChange: (WatermarkSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (WatermarkStore.importLogo(context, uri)) onChange(settings.copy(useLogo = true, enabled = true))
            else Toast.makeText(context, "No se pudo leer esa imagen", Toast.LENGTH_LONG).show()
        }
    }
    val hasLogo = WatermarkStore.logoFile(context).exists()

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Cabecera: interruptor + pestañas + Listo.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = settings.enabled, onCheckedChange = { on ->
                if (on && !settings.useLogo && settings.text.isBlank()) {
                    Toast.makeText(context, "Escribe tu nombre o elige un logo", Toast.LENGTH_SHORT).show()
                    tab = 0
                }
                onChange(settings.copy(enabled = on))
            })
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Contenido", "Estilo", "Movimiento").forEachIndexed { i, n ->
                    TextButton(onClick = { tab = i }) {
                        Text(n, fontSize = 13.sp, color = if (tab == i) Palette.accent else Palette.textSoft,
                            fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            TextButton(onClick = onClose) { Text("✓", fontSize = 24.sp, color = Palette.accent) }
        }

        when (tab) {
            0 -> {
                ChipRow {
                    Chip("Texto", !settings.useLogo) { onChange(settings.copy(useLogo = false)) }
                    Chip("Logo", settings.useLogo) { if (hasLogo) onChange(settings.copy(useLogo = true)) else pickLogo.launch(arrayOf("image/*")) }
                }
                if (!settings.useLogo) {
                    OutlinedTextField(
                        value = settings.text,
                        // Al escribir algo la marca se activa sola: no hay que buscar el interruptor.
                        onValueChange = { t -> val v = t.take(40); onChange(settings.copy(text = v, enabled = settings.enabled || v.isNotBlank())) },
                        singleLine = true, label = { Text("Tu nombre o @usuario") }, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedButton(onClick = { pickLogo.launch(arrayOf("image/*")) }, border = BorderStroke(1.dp, Palette.border)) {
                        Text(if (hasLogo) "Cambiar logo" else "Elegir logo (PNG con fondo transparente)")
                    }
                }
            }
            1 -> {
                if (!settings.useLogo) {
                    ChipRow {
                        WatermarkLook.fonts.forEachIndexed { i, f ->
                            val fam = when (f.family) {
                                "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive
                                else -> FontFamily.SansSerif
                            }
                            FilterChip(selected = settings.font == i, onClick = { onChange(settings.copy(font = i)) }, label = {
                                Text(f.label, fontFamily = fam, fontSize = 13.sp, fontWeight = if (f.family == "sans-serif-black") FontWeight.Black else FontWeight.Bold)
                            })
                        }
                    }
                    ChipRow {
                        WatermarkLook.colors.forEachIndexed { i, c -> Chip(c.label, settings.color == i) { onChange(settings.copy(color = i)) } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(64.dp))
                    Slider(value = settings.size, onValueChange = { onChange(settings.copy(size = it)) }, valueRange = 0.10f..0.45f, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacidad", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(64.dp))
                    Slider(value = settings.opacity, onValueChange = { onChange(settings.copy(opacity = it)) }, valueRange = 0.30f..1f, modifier = Modifier.weight(1f))
                }
            }
            else -> {
                // Un solo selector de movimiento: 0 saltos, 1 fija, 2 recorrido en X, 3 diagonal ↘↖, 4 diagonal ↙↗.
                val mode = when {
                    settings.glide -> 2 + settings.glidePath
                    settings.dynamic -> 0
                    else -> 1
                }
                fun setMode(m: Int) = onChange(
                    when (m) {
                        0 -> settings.copy(glide = false, dynamic = true)
                        1 -> settings.copy(glide = false, dynamic = false)
                        else -> settings.copy(glide = true, glidePath = m - 2)
                    },
                )
                ChipRow {
                    listOf("Saltos", "Fija", "Recorrido en X", "Diagonal ↘↖", "Diagonal ↙↗").forEachIndexed { i, n -> Chip(n, mode == i) { setMode(i) } }
                }
                when {
                    mode == 1 -> ChipRow {
                        listOf("↖ Arriba izq." to 0, "↗ Arriba der." to 1, "↙ Abajo izq." to 2, "↘ Abajo der." to 3).forEach { (l, c) ->
                            Chip(l, settings.corner == c) { onChange(settings.copy(corner = c)) }
                        }
                    }
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (mode == 0) "Cambia cada" else "Tarda por tramo", fontSize = 12.sp, color = Palette.textSoft)
                        listOf(3, 5, 8).forEach { sec -> Chip("$sec s", settings.periodSec == sec) { onChange(settings.copy(periodSec = sec)) } }
                    }
                }
                ChipRow {
                    WatermarkLook.animNames.forEachIndexed { i, n -> Chip(if (i == 0) "Sin animación" else n, settings.anim == i) { onChange(settings.copy(anim = i)) } }
                    if (settings.anim != 0) {
                        listOf("Lenta", "Media", "Rápida").forEachIndexed { i, n -> Chip(n, settings.animSpeed == i) { onChange(settings.copy(animSpeed = i)) } }
                    }
                }
            }
        }
    }
}
