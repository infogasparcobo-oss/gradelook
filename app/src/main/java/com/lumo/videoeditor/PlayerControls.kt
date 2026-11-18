package com.lumo.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Barra de reproducción propia, siempre abajo del video: línea de tiempo con tiempos y, debajo,
 * atrasar 5 s, reproducir/pausar y adelantar 5 s. Sustituye a los controles flotantes del centro de PlayerView.
 */
@Composable
fun PlayerBar(player: Player, modifier: Modifier = Modifier, speed: Float = 1f, clipDurations: List<Long> = emptyList(), onSpeed: (Float) -> Unit = {}) {
    // Con varias tomas (lista de reproducción) la línea de tiempo y los tiempos cubren el video completo.
    val durs by androidx.compose.runtime.rememberUpdatedState(clipDurations)
    fun multi() = durs.size > 1
    fun globalPos(): Long = if (multi()) durs.take(player.currentMediaItemIndex).sum() + player.currentPosition else player.currentPosition
    fun totalDur(): Long = if (multi()) durs.sum() else player.duration.coerceAtLeast(0L)
    fun seekGlobal(ms: Long) {
        if (!multi()) { player.seekTo(ms); return }
        var rem = ms.coerceIn(0L, (durs.sum() - 1).coerceAtLeast(0L)); var i = 0
        while (i < durs.size - 1 && rem >= durs[i]) { rem -= durs[i]; i++ }
        player.seekTo(i, rem)
    }
    var speedMenu by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(playbackState: Int) { dur = totalDur() }
        }
        player.addListener(l)
        playing = player.isPlaying
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) {
        while (true) {
            if (!dragging) { pos = globalPos(); dur = totalDur() }
            delay(120)
        }
    }

    fun seekBy(ms: Long) {
        val target = (globalPos() + ms).coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)
        seekGlobal(target); pos = target
    }

    // Una sola fila: atrasar 5 s, reproducir/pausar, adelantar 5 s y la línea de tiempo con sus tiempos.
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 6.dp).padding(top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkipButton(forward = false) { seekBy(-5_000) }
        Canvas(
            Modifier.size(42.dp).clip(CircleShape).background(Palette.accent)
                .clickable { if (player.isPlaying) player.pause() else player.play() },
        ) {
            val s = size.minDimension
            if (playing) {
                val w = s * 0.10f; val h = s * 0.34f
                drawRect(Palette.onAccent, Offset(s * 0.5f - w * 1.7f, s * 0.5f - h / 2), androidx.compose.ui.geometry.Size(w, h))
                drawRect(Palette.onAccent, Offset(s * 0.5f + w * 0.7f, s * 0.5f - h / 2), androidx.compose.ui.geometry.Size(w, h))
            } else {
                val p = Path().apply {
                    moveTo(s * 0.40f, s * 0.30f); lineTo(s * 0.72f, s * 0.50f); lineTo(s * 0.40f, s * 0.70f); close()
                }
                drawPath(p, Palette.onAccent)
            }
        }
        SkipButton(forward = true) { seekBy(5_000) }
        Text(fmt(if (dragging) (dragFrac * dur).toLong() else pos), fontSize = 11.sp, color = Palette.text, modifier = Modifier.padding(start = 4.dp))
        Slider(
            value = if (dragging) dragFrac else if (dur > 0) (pos / dur.toFloat()).coerceIn(0f, 1f) else 0f,
            onValueChange = { dragging = true; dragFrac = it },
            onValueChangeFinished = { seekGlobal((dragFrac * dur).toLong()); pos = (dragFrac * dur).toLong(); dragging = false },
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent, activeTrackColor = Palette.accent,
                inactiveTrackColor = Color(0x55FFFFFF),
            ),
        )
        Text(fmt(dur), fontSize = 11.sp, color = Palette.text, modifier = Modifier.padding(end = 4.dp))
        // Velocidad: toca para elegir de 0.25x a 3x (con pasos finos de 0.1 entre 0.5x y 1.5x).
        androidx.compose.foundation.layout.Box {
            Text(
                (if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"), fontSize = 12.sp,
                color = if (speed != 1f) Palette.onAccent else Palette.text,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .background(if (speed != 1f) Palette.accent else Color(0xE61A1C1E))
                    .border(1.dp, if (speed != 1f) Palette.accent else Color(0x66FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable { speedMenu = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
            androidx.compose.material3.DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                listOf(0.25f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 2f, 3f).forEach { s ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x", color = if (s == speed) Palette.accent else Palette.text) },
                        onClick = { onSpeed(s); speedMenu = false },
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 4.dp))
    }
}

@Composable
private fun SkipButton(forward: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val s = size.minDimension
            fun tri(cx: Float) = Path().apply {
                if (forward) { moveTo(cx - s * 0.16f, s * 0.28f); lineTo(cx + s * 0.16f, s * 0.5f); lineTo(cx - s * 0.16f, s * 0.72f) }
                else { moveTo(cx + s * 0.16f, s * 0.28f); lineTo(cx - s * 0.16f, s * 0.5f); lineTo(cx + s * 0.16f, s * 0.72f) }
                close()
            }
            drawPath(tri(s * 0.34f), Palette.text)
            drawPath(tri(s * 0.68f), Palette.text)
        }
    }
}

private fun fmt(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}
