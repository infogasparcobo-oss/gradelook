package com.lumo.videoeditor

import android.content.Context
import android.net.Uri

/** Recuerda dónde se quedó el usuario: último video abierto y última carpeta de la galería. */
object AppState {
    private const val PREFS = "gradelook_state"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastVideo(c: Context): Uri? = p(c).getString("video", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
    fun lastPosition(c: Context): Long = p(c).getLong("position", 0L)
    fun lastAlbum(c: Context): String? = p(c).getString("album", null)

    fun saveVideo(c: Context, uri: Uri) = p(c).edit().putString("video", uri.toString()).putLong("position", 0L).apply()
    fun savePosition(c: Context, ms: Long) = p(c).edit().putLong("position", ms.coerceAtLeast(0L)).apply()
    fun saveAlbum(c: Context, album: String?) = p(c).edit().apply { if (album == null) remove("album") else putString("album", album) }.apply()

    /** True si el video guardado todavía se puede abrir (no se borró ni se perdió el permiso). */
    fun canOpen(c: Context, uri: Uri): Boolean = runCatching {
        c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
