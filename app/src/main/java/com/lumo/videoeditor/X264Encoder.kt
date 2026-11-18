package com.lumo.videoeditor

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode

/** Recodificación final con x264/x265 (misma receta que Smart Video Compressor: preset medium, ABR). */
object X264Encoder {

    /**
     * Recodifica [input] (ruta de archivo o Uri de contenido) a [output].
     * [onProgress] recibe 0..1; [onDone] recibe éxito y un mensaje de error si falló.
     * Devuelve el id de sesión, que sirve para cancelar con [cancel].
     */
    fun encode(
        context: Context,
        input: String,
        output: String,
        width: Int,
        height: Int,
        scale: Boolean,
        kbps: Int,
        h265: Boolean,
        preset: String,
        watermarkPng: String?,
        durationMs: Long,
        /** Perfil "TikTok 4K": fps constantes (los de este valor), BT.709, GOP cerrado de 2 s y aq-mode 3 (protege planos y oscuros). */
        tiktokFps: Float? = null,
        /**
         * Calidad constante (CRF) con tope de bitrate [kbps]: conserva mucho mejor el detalle fino que un bitrate fijo.
         * Medido contra Kuni sobre un clip de dron a 1080p: ABR a 31 Mb/s perdía ~17 % del detalle fino; CRF 22 con
         * "tune grain" lo conserva y supera a Kuni en detalle fino, medio y grueso a ~49 Mb/s. 0 = usar bitrate medio.
         */
        crf: Int = 0,
        onProgress: (Float) -> Unit,
        onDone: (Boolean, String?) -> Unit,
    ): Long {
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
        val args = buildList {
            addAll(listOf("-y", "-i", input))
            if (watermarkPng != null) {
                // Plan gratis (desde la 6.ª exportación): marca de agua abajo a la derecha.
                val m = (width * 0.03f).toInt().coerceAtLeast(12)
                val chain = (if (scale) "[0:v]scale=$width:$height:flags=lanczos[b];[b][1:v]" else "[0:v][1:v]") +
                    "overlay=W-w-$m:H-h-$m[v]"
                addAll(listOf("-i", watermarkPng, "-filter_complex", chain, "-map", "[v]", "-map", "0:a?"))
            } else {
                addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
                if (scale) addAll(listOf("-vf", "scale=$width:$height:flags=lanczos"))
            }
            if (h265) {
                addAll(listOf("-c:v", "libx265", "-preset", preset, "-b:v", "${kbps}k", "-tag:v", "hvc1", "-x265-params", "log-level=error"))
            } else if (tiktokFps != null) {
                val gop = (tiktokFps * 2f).toInt().coerceAtLeast(12)
                addAll(listOf(
                    "-c:v", "libx264", "-preset", preset, "-b:v", "${kbps}k", "-maxrate", "${(kbps * 1.25f).toInt()}k",
                    "-bufsize", "${kbps * 2}k", "-profile:v", "high", "-g", "$gop",
                    "-x264-params", "aq-mode=3:keyint=$gop:min-keyint=$gop:open-gop=0",
                ))
            } else if (crf > 0) {
                addAll(listOf(
                    "-c:v", "libx264", "-preset", preset, "-crf", "$crf", "-tune", "grain",
                    "-maxrate", "${kbps}k", "-bufsize", "${kbps * 2}k",
                ))
            } else {
                addAll(listOf("-c:v", "libx264", "-preset", preset, "-b:v", "${kbps}k"))
            }
            if (tiktokFps != null) {
                addAll(listOf(
                    "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                    "-fps_mode", "cfr", "-r", String.format(java.util.Locale.US, "%.3f", tiktokFps), "-ar", "48000",
                ))
            }
            addAll(listOf("-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output))
        }.toTypedArray()

        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { s ->
                val ok = ReturnCode.isSuccess(s.returnCode)
                onDone(ok, if (ok) null else (s.failStackTrace ?: s.output?.takeLast(300) ?: "Error de FFmpeg"))
            },
            null,
            { st ->
                if (durationMs > 0) onProgress((st.time / durationMs.toDouble()).toFloat().coerceIn(0f, 1f))
            },
        )
        return session.sessionId
    }

    fun cancel(sessionId: Long) = FFmpegKit.cancel(sessionId)

    /** Convierte una Uri de contenido en un argumento de entrada que FFmpeg puede abrir. */
    fun inputFor(context: Context, uri: Uri): String =
        if (uri.scheme == "file") uri.path ?: uri.toString() else FFmpegKitConfig.getSafParameterForRead(context, uri)
}
