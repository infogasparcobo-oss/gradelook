package com.lumo.videoeditor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/** Un video se puede reencuadrar a 9:16 solo si es más ancho que 9:16 (p. ej. tomas horizontales de dron). */
fun canReframe916(w: Int, h: Int) = h > 0 && w.toFloat() / h > 9f / 16f + 0.01f

/**
 * Recorte: tramo del video (inicio/fin) y reencuadre a 9:16 con la ventana desplazable. Los cambios se aplican al pulsar
 * "Listo" y valen tanto para la vista previa como para la exportación. [endMs] = 0 significa "hasta el final".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimSheet(
    uri: Uri, durationMs: Long, videoW: Int, videoH: Int,
    startMs: Long, endMs: Long, reframe: Boolean, panX: Float,
    onApply: (start: Long, end: Long, reframe: Boolean, pan: Float) -> Unit,
    onDismiss: () -> Unit,
    /** false en las tomas añadidas: el reencuadre 9:16 solo se aplica a la primera. */
    allowReframe: Boolean = true,
) {
    val context = LocalContext.current
    val eligible = allowReframe && canReframe916(videoW, videoH)
    var a by remember { mutableLongStateOf(startMs) }
    var b by remember { mutableLongStateOf(if (endMs > 0) endMs else durationMs) }
    var rf by remember { mutableStateOf(reframe && eligible) }
    var pan by remember { mutableFloatStateOf(panX) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }

    // Miniatura del cuadro de inicio (con una pausa corta para no decodificar mientras se arrastra).
    LaunchedEffect(a) {
        delay(180)
        frame = withContext(Dispatchers.IO) {
            runCatching {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(a * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { bmp ->
                        val s = 720f / max(bmp.width, bmp.height)
                        if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
                    }
                } finally { r.release() }
            }.getOrNull()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Recortar", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            // Cuadro de inicio con la ventana 9:16 encima.
            Box(Modifier.fillMaxWidth().heightIn(max = 300.dp), contentAlignment = Alignment.Center) {
                val ar = if (videoH > 0) videoW.toFloat() / videoH else 16f / 9f
                Box(Modifier.aspectRatio(ar).heightIn(max = 300.dp)) {
                    frame?.let { Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit) }
                    if (rf && eligible) {
                        Canvas(Modifier.matchParentSize()) {
                            val cwf = (videoH * 9f / 16f) / videoW
                            val left = pan * (1f - cwf) * size.width
                            val right = left + cwf * size.width
                            val dim = Color(0x99000000)
                            drawRect(dim, Offset(0f, 0f), Size(left, size.height))
                            drawRect(dim, Offset(right, 0f), Size(size.width - right, size.height))
                            drawRect(Palette.accent, Offset(left, 0f), Size(right - left, size.height), style = Stroke(3.dp.toPx()))
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Inicio ${fmtMs(a)}", fontSize = 13.sp, color = Palette.text)
                Text("Dura ${fmtMs(b - a)}", fontSize = 13.sp, color = Palette.accent)
                Text("Fin ${fmtMs(b)}", fontSize = 13.sp, color = Palette.text)
            }
            RangeSlider(
                value = a.toFloat()..b.toFloat(),
                onValueChange = { r ->
                    val na = r.start.toLong(); val nb = r.endInclusive.toLong()
                    if (nb - na >= 500) { a = na; b = nb }
                },
                valueRange = 0f..durationMs.toFloat(),
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reencuadrar a 9:16", fontSize = 13.sp, color = Palette.accent)
                    Text(
                        if (eligible) "Convierte una toma horizontal en vertical; elige qué parte se queda."
                        else if (!allowReframe) "El reencuadre 9:16 solo se aplica a la primera toma."
                        else "Este video ya es vertical (9:16), no hace falta.",
                        fontSize = 12.sp, color = Palette.textSoft,
                    )
                }
                Switch(checked = rf && eligible, enabled = eligible, onCheckedChange = { rf = it })
            }
            if (rf && eligible) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Posición de la ventana", fontSize = 13.sp, color = Palette.text)
                    Text(if (pan < 0.4f) "Izquierda" else if (pan > 0.6f) "Derecha" else "Centro", fontSize = 12.sp, color = Palette.textSoft)
                }
                Slider(value = pan, onValueChange = { pan = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { a = 0L; b = durationMs; rf = false; pan = 0.5f }, modifier = Modifier.weight(1f)) { Text("Restablecer") }
                Button(
                    onClick = { onApply(a, if (b >= durationMs - 50) 0L else b, rf && eligible, pan) },
                    modifier = Modifier.weight(1f),
                ) { Text("Listo") }
            }
        }
    }
}

private fun fmtMs(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 100   // décimas
    return String.format(Locale.US, "%d:%02d.%d", t / 600, (t / 10) % 60, t % 10)
}
