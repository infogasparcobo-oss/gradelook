@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Dos reproductores para la vista previa:
 *  - [fx]: con filtros y LUT en tiempo real (velocidad 1x).
 *  - el "rápido": sin motor de efectos, que se usa SOLO cuando la velocidad es mayor que 1x. Medido en el teléfono: con
 *    efectos, a 2x el reproductor descarta ~90 cuadros por segundo y va a ~10 cuadros/s (a 1x va a 60); sin efectos va a
 *    60 cuadros/s a 1x, 2x y 3x. Mientras se usa el rápido la vista previa no muestra los filtros (el video exportado sí).
 */
class PreviewPlayers(private val context: Context, private val lutHolder: LutHolder, private val holder: AdjustmentHolder, private val wmHolder: WatermarkHolder, private val titleHolder: TitleHolder, private val lightHolder: LightHolder) {
    /** true = se está usando el reproductor rápido (velocidad > 1x, sin filtros en pantalla). */
    var fast by mutableStateOf(false)
        private set

    /**
     * Se llama cuando el video no se puede decodificar o la cadena de efectos falla con él (p. ej. H.265 de 10 bits 4:2:2 de una
     * Sony A7 IV): la pantalla se queda en negro. Quien lo recibe convierte el video a un formato compatible.
     */
    var onUndecodable: ((PlaybackException) -> Unit)? = null

    private val errorListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED,
                PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> onUndecodable?.invoke(error)
            }
        }
    }

    private fun build(withEffects: Boolean): ExoPlayer = ExoPlayer.Builder(context)
        // Búfer pequeño: con 4K a 60 fps el valor por defecto reserva decenas de MB y agota la memoria al exportar.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(1500, 6000, 500, 1000)
                .setTargetBufferBytes(24 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build(),
        )
        .build().apply {
            // El paso de LUT va siempre (sin LUT elegida solo copia la imagen); así no hay que reconfigurar la cadena al elegirla.
            if (withEffects) setVideoEffects(listOf(LutEffect(lutHolder), AdjustmentsEffect(holder), LightEffect(lightHolder), Watermark.effect(wmHolder), Titles.effect(titleHolder)))
            repeatMode = Player.REPEAT_MODE_ALL
            addListener(errorListener)
        }

    val fx: ExoPlayer = build(true)
    private val plain: ExoPlayer by lazy { build(false) }

    /** Reproductor activo ahora mismo. */
    val cur: ExoPlayer get() = if (fast) plain else fx

    /** Cambia de reproductor llevándose el video, la posición y si estaba reproduciendo; aplica la velocidad al activo. */
    fun switchTo(fastMode: Boolean, speed: Float) {
        if (fastMode == fast) { cur.setPlaybackSpeed(speed); return }
        val src = cur
        // Se lleva toda la lista de tomas (no solo la actual), el punto exacto y si estaba reproduciendo.
        val items = List(src.mediaItemCount) { src.getMediaItemAt(it) }
        val index = src.currentMediaItemIndex
        val pos = src.currentPosition
        val playing = src.playWhenReady
        src.playWhenReady = false
        src.stop(); src.clearMediaItems()   // libera el decodificador antes de que el otro lo pida
        fast = fastMode
        val dst = cur
        if (items.isNotEmpty()) {
            dst.setMediaItems(items, index, pos)
            dst.prepare()
            dst.playWhenReady = playing
        }
        dst.setPlaybackSpeed(speed)
    }

    fun release() {
        fx.release()
        runCatching { plain.release() }
    }
}
