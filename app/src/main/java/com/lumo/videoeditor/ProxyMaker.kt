package com.lumo.videoeditor

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.security.MessageDigest

/**
 * Videos que el teléfono no sabe decodificar (típico de cámaras como la Sony A7 IV: H.265 o H.264 de 10 bits 4:2:2, o 10 bits
 * con perfiles que los chips de los móviles no traen). Con ellos el reproductor se queda en negro y la exportación por
 * hardware falla. Se convierten UNA vez a H.264 de 8 bits 4:2:0 (casi sin pérdida) con FFmpeg, que sí los lee, y a partir de
 * ahí el editor trabaja con esa copia. La copia se guarda en la caché y se reutiliza si se vuelve a abrir el mismo video.
 */
object ProxyMaker {
    private const val DIR = "proxies"
    private const val MAX_CACHE_BYTES = 6L * 1024 * 1024 * 1024

    private fun dir(c: Context) = File(c.cacheDir, DIR).also { it.mkdirs() }

    private fun sourceSize(c: Context, uri: Uri): Long = try {
        if (uri.scheme == "file") File(uri.path ?: "").length()
        else c.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    } catch (e: Exception) { -1L }

    private fun key(c: Context, uri: Uri): String {
        val raw = uri.toString() + "|" + sourceSize(c, uri)
        val d = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    fun file(c: Context, uri: Uri) = File(dir(c), "p_${key(c, uri)}.mp4")

    /** Copia ya convertida de este video, si existe. */
    fun cached(c: Context, uri: Uri): File? = file(c, uri).takeIf { it.exists() && it.length() > 100_000L }

    /** true si el teléfono tiene un decodificador capaz de abrir la pista de video de este archivo. */
    fun canDecode(c: Context, uri: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(c, uri, null)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                f.setString(MediaFormat.KEY_FRAME_RATE, null)   // findDecoderForFormat lo exige así
                return MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(f) != null
            }
            true
        } catch (e: Exception) { true } finally { runCatching { ex.release() } }
    }

    /**
     * Convierte [uri] a un H.264 de 8 bits 4:2:0 (calidad casi sin pérdida, resolución máxima 4K) con audio AAC.
     * [onProgress] 0..1; [onDone] recibe el archivo o null si falló o se canceló. Devuelve el id de sesión para cancelar.
     */
    fun convert(c: Context, uri: Uri, durationMs: Long, onProgress: (Float) -> Unit, onDone: (File?, String?) -> Unit): Long {
        trimCache(c)
        val out = file(c, uri)
        val tmp = File(out.parentFile, out.name + ".part.mp4")
        tmp.delete()
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_WARNING)
        val args = arrayOf(
            "-y", "-i", X264Encoder.inputFor(c, uri),
            "-map", "0:v:0", "-map", "0:a?",
            // Se conserva la geometría y los colores tal cual (sin tone mapping): una cámara en S-Log o HLG sale plana, igual
            // que en cualquier visor, y se corrige después con la LUT. Máximo 4K de ancho/alto.
            "-vf", "scale='if(gt(iw,ih),min(3840,iw),-2)':'if(gt(iw,ih),-2,min(3840,ih))':flags=bicubic,format=yuv420p",
            "-c:v", "libx264", "-preset", "ultrafast", "-crf", "14", "-pix_fmt", "yuv420p",
            "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
            "-c:a", "aac", "-b:a", "256k", "-ar", "48000",
            "-movflags", "+faststart", tmp.absolutePath,
        )
        val session = FFmpegKit.executeWithArgumentsAsync(
            args,
            { s ->
                val ok = ReturnCode.isSuccess(s.returnCode) && tmp.exists() && tmp.length() > 100_000L
                if (ok) { out.delete(); tmp.renameTo(out); onDone(out, null) }
                else { tmp.delete(); onDone(null, if (ReturnCode.isCancel(s.returnCode)) null else (s.output?.takeLast(300) ?: "Error de FFmpeg")) }
            },
            null,
            { st -> if (durationMs > 0) onProgress((st.time / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)) },
        )
        return session.sessionId
    }

    fun cancel(sessionId: Long) = FFmpegKit.cancel(sessionId)

    /** Las copias ocupan espacio: se borran las más viejas cuando pasan de unos GB. */
    private fun trimCache(c: Context) {
        val files = dir(c).listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= MAX_CACHE_BYTES) break; total -= f.length(); f.delete() }
    }
}
