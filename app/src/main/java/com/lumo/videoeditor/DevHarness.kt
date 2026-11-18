@file:OptIn(UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Modo de pruebas por adb: procesa un archivo con valores fijos y lo guarda sin
 * interfaz. Ejemplo:
 * am start -n com.lumo.videoeditor/.MainActivity --es in /ruta/in.mp4 --es out /ruta/out.mp4 --es p "TEXTURE=1.0,CLARITY=-0.7"
 */
object DevHarness {
    fun run(
        context: Context, input: String, output: String, params: String, bitrate: Int = 40_000_000, high: Boolean = false,
        iFrameSeconds: Float = 0f, perf: Boolean = false, portrait: Boolean = false, noEffect: Boolean = false,
        bitrateMode: Int = -1, hevc: Boolean = false, hq: Boolean = false,
    ) {
        val snap = AdjustmentSnapshot()
        params.split(",").filter { it.contains("=") }.forEach { kv ->
            val (k, v) = kv.split("=")
            if (k.trim() == "PRESET") {
                // PRESET=<índice en Formulas.presets>: aplica un filtro incluido tal cual (solo pruebas)
                val p = Formulas.presets[v.trim().toInt()]
                p.values.copyInto(snap.values); p.hsl.copyInto(snap.hsl); p.grade.copyInto(snap.grade)
            } else if (k.trim().startsWith("HSL")) {
                // HSL<banda 0..7>=<tono>_<saturación>_<luminancia>, cada uno en -1..1 (solo pruebas)
                val b = k.trim().removePrefix("HSL").toInt(); val p = v.trim().split("_")
                for (j in 0..2) snap.hsl[b * 3 + j] = p[j].toFloat()
            } else if (k.trim().startsWith("ZONE")) {
                // ZONE<n>=<tono 0..1>_<saturación 0..1>  (solo pruebas)
                val z = k.trim().removePrefix("ZONE").toInt(); val (h, s) = v.trim().split("_")
                snap.grade[z * 3] = h.toFloat(); snap.grade[z * 3 + 1] = s.toFloat()
            } else snap.values[Adj.valueOf(k.trim()).ordinal] = v.trim().toFloat()
        }
        val holder = AdjustmentHolder().also { it.snapshot = snap }
        File(output).delete()
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(input))))
            .setEffects(Effects(emptyList(), if (noEffect) emptyList() else listOf(AdjustmentsEffect(holder))))
            .build()
        val encoder = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(bitrate).apply {
                    if (iFrameSeconds > 0f) setiFrameIntervalSeconds(iFrameSeconds)
                    if (bitrateMode >= 0) setBitrateMode(bitrateMode)
                    if (perf) setEncoderPerformanceParameters(Short.MAX_VALUE.toInt(), 1)
                    if (high) setEncodingProfileLevel(
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                        android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel42,
                    )
                }.build(),
            )
            .build()
        Transformer.Builder(context)
            .setPortraitEncodingEnabled(portrait)
            .apply { if (hevc) setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H265) }
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Log.i("LumoHarness", "DONE $output")
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Log.e("LumoHarness", "ERROR ${exportException.message}", exportException)
                }
            })
            .build()
            .start(edited, output)
    }
}
