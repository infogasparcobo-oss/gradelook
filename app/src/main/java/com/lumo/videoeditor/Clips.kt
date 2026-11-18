package com.lumo.videoeditor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Una toma del video final: archivo, tamaño, duración y el tramo elegido (endMs = 0 significa "hasta el final"). */
data class Clip(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
) {
    /** Duración del tramo elegido, sin contar la velocidad. */
    val effDurationMs: Long get() = ((if (endMs in 1 until durationMs) endMs else durationMs) - startMs).coerceAtLeast(300L)

    fun mediaItem(): MediaItem = MediaItem.Builder().setUri(uri).apply {
        if (startMs > 0L || endMs > 0L) {
            setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
                    .apply { if (endMs > 0L) setEndPositionMs(endMs) }.build(),
            )
        }
    }.build()
}

private fun fmtDur(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

/**
 * Lista de tomas del video: añadir, borrar, reordenar y recortar cada una. Las tomas se unen en este orden y comparten
 * el mismo filtro, LUT y velocidad. El tamaño de salida es el de la primera toma; las demás se ajustan a él.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsSheet(
    clips: List<Clip>,
    current: Int,
    onAdd: () -> Unit,
    onTrim: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Tomas del video", style = MaterialTheme.typography.titleMedium)
            Text(
                "Se unen en este orden y comparten filtro, LUT y velocidad. La primera toma fija el tamaño del video.",
                fontSize = 12.sp, color = Palette.textSoft,
            )
            clips.forEachIndexed { i, c ->
                ClipRow(
                    index = i, clip = c, selected = i == current, isLast = i == clips.lastIndex, canDelete = clips.size > 1,
                    onTrim = { onTrim(i) }, onUp = { onMove(i, -1) }, onDown = { onMove(i, 1) }, onDelete = { onDelete(i) },
                )
            }
            val total = clips.sumOf { it.effDurationMs }
            Text("Total: ${fmtDur(total)} · ${clips.size} ${if (clips.size == 1) "toma" else "tomas"}", fontSize = 13.sp, color = Palette.accent)
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("＋ Añadir toma") }
        }
    }
}

@Composable
private fun ClipRow(
    index: Int, clip: Clip, selected: Boolean, isLast: Boolean, canDelete: Boolean,
    onTrim: () -> Unit, onUp: () -> Unit, onDown: () -> Unit, onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(clip.uri, clip.startMs) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(clip.uri, clip.startMs) {
        thumb = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, clip.uri)
                    r.getFrameAtTime(clip.startMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        val s = 200f / maxOf(b.width, b.height)
                        if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
                    }
                } finally { r.release() }
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Palette.surface)
            .border(1.dp, if (selected) Palette.accent else Palette.border, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(56.dp, 56.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().height(56.dp), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            Text("Toma ${index + 1}", fontSize = 14.sp, color = Palette.text)
            Text(
                "${fmtDur(clip.effDurationMs)} · ${minOf(clip.width, clip.height)}p",
                fontSize = 12.sp, color = Palette.textSoft, maxLines = 1,
            )
        }
        RowIcon("✂", "Recortar toma ${index + 1}", true, onTrim)
        RowIcon("↑", "Subir", index > 0, onUp)
        RowIcon("↓", "Bajar", !isLast, onDown)
        RowIcon("✕", "Quitar toma ${index + 1}", canDelete, onDelete)
    }
}

@Composable
private fun RowIcon(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(symbol, fontSize = 17.sp, color = if (enabled) Palette.text else Color(0x44FFFFFF)) }
}
