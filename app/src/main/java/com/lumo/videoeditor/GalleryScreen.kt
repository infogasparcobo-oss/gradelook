package com.lumo.videoeditor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Un video de la galería del teléfono. */
data class VideoItem(val id: Long, val uri: Uri, val durationMs: Long, val album: String)

/** Caché de miniaturas limitada por MEMORIA (24 MB), no por cantidad: antes guardaba 160 imágenes (~80 MB) y llevaba a OutOfMemory al exportar 4K. */
private object ThumbCache {
    private val cache = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    fun get(id: Long): Bitmap? = cache.get(id)
    fun put(id: Long, b: Bitmap) { cache.put(id, b) }
    fun clear() = cache.evictAll()
}

private fun videoPermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

internal fun hasVideoAccess(context: Context) =
    ContextCompat.checkSelfPermission(context, videoPermission()) == PackageManager.PERMISSION_GRANTED

/** Lee los videos del teléfono (galería, cámara, descargas indexadas), del más reciente al más antiguo. */
internal fun queryVideos(context: Context): List<VideoItem> {
    val out = ArrayList<VideoItem>()
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    )
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val albCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            out.add(
                VideoItem(
                    id, Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    c.getLong(durCol), c.getString(albCol) ?: "Otros",
                ),
            )
        }
    }
    return out
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * Pantalla de inicio al estilo de Kuni Cam: cuadrícula de videos del teléfono con álbumes, duración y
 * un botón de cámara. "Archivos" abre el selector del sistema (Descargas, Drive).
 */
@Composable
fun GalleryScreen(
    canClose: Boolean,
    /** Recibe el video elegido y la lista de la carpeta que se estaba viendo (para pasar al siguiente sin volver aquí). */
    onPick: (Uri, List<Uri>) -> Unit,
    onBrowse: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasVideoAccess(context)) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    // La carpeta elegida se recuerda entre aperturas de la app.
    var album by remember { mutableStateOf(AppState.lastAlbum(context)) }
    LaunchedEffect(album) { AppState.saveAlbum(context, album) }
    var menu by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> allowed = ok }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.data?.let { onPick(it, emptyList()) }
    }

    LaunchedEffect(allowed) {
        if (allowed) videos = withContext(Dispatchers.IO) { queryVideos(context) }
    }
    // Al salir de la galería se libera la caché de miniaturas para dejar memoria al editor y a la exportación.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ThumbCache.clear() } }
    if (canClose) BackHandler(onBack = onClose)

    val albums = remember(videos) { videos.groupingBy { it.album }.eachCount().toList().sortedByDescending { it.second } }
    // Si la carpeta recordada ya no existe (se borró o quedó vacía), se vuelve a "Todos".
    LaunchedEffect(videos) { if (videos.isNotEmpty() && album != null && videos.none { it.album == album }) album = null }
    val shown = remember(videos, album) { if (album == null) videos else videos.filter { it.album == album } }

    Column(Modifier.fillMaxSize().background(Palette.background).statusBarsPadding()) {
        // Título centrado con desplegable de álbumes (como el selector de Evoto).
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            if (canClose) {
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text("✕", fontSize = 20.sp, color = Palette.text)
                }
            }
            Box {
                TextButton(onClick = { menu = true }) {
                    Text((album ?: "Todos los videos") + "  ⌄", style = MaterialTheme.typography.titleMedium, color = Palette.text)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Todos los videos (${videos.size})") }, onClick = { album = null; menu = false })
                    albums.forEach { (name, n) ->
                        DropdownMenuItem(text = { Text("$name ($n)") }, onClick = { album = name; menu = false })
                    }
                }
            }
        }

        // Fichas de álbum en forma de píldora.
        if (albums.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                item { AlbumChip("Todos", album == null) { album = null } }
                items(albums.take(12), key = { it.first }) { (name, _) -> AlbumChip(name, album == name) { album = name } }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !allowed -> Column(
                    Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Permite el acceso a tus videos para verlos aquí", color = Palette.text, textAlign = TextAlign.Center)
                    Button(onClick = { askPermission.launch(videoPermission()) }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Permitir acceso")
                    }
                    OutlinedButton(onClick = onBrowse, modifier = Modifier.padding(top = 8.dp)) { Text("Buscar en archivos") }
                    Text(
                        "Tus videos se editan solo en tu teléfono y no se suben a ningún servidor.",
                        fontSize = 12.sp, color = Palette.textSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp),
                    )
                    PrivacyLink()
                }
                shown.isEmpty() -> Text(
                    "No hay videos en este álbum", color = Palette.textSoft, modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    items(shown, key = { it.id }) { v -> VideoCell(v) { onPick(v.uri, shown.map { it.uri }) } }
                }
            }
        }

        // Enlace legal que Google Play exige dentro de la app: siempre a la vista, discreto.
        PrivacyLink(Modifier.align(Alignment.CenterHorizontally))

        // Barra inferior en píldora (Videos | Archivos) con el botón de cámara amarillo, como Evoto.
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).height(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp)).background(Palette.bar),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("Videos", style = MaterialTheme.typography.labelLarge, color = Palette.text)
                }
                Box(Modifier.weight(1f).fillMaxSize().clickable(onClick = onBrowse), contentAlignment = Alignment.Center) {
                    Text("Archivos", style = MaterialTheme.typography.labelLarge, color = Palette.textSoft)
                }
            }
            Box(
                Modifier.padding(start = 12.dp).size(60.dp).clip(CircleShape).background(Palette.accent).clickable {
                    // Cámara: abre la app de cámara del teléfono para grabar un video y lo carga al terminar.
                    try { camera.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE)) } catch (e: Exception) { /* sin cámara */ }
                },
                contentAlignment = Alignment.Center,
            ) { Text("●", fontSize = 22.sp, color = Palette.onAccent) }
        }
    }
}

@Composable
private fun AlbumChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(if (selected) Palette.bar else Palette.surface)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(name, fontSize = 14.sp, color = if (selected) Palette.text else Palette.textSoft, maxLines = 1)
    }
}
@Composable
private fun VideoCell(v: VideoItem, onClick: () -> Unit) {
    val context = LocalContext.current
    var bmp by remember(v.id) { mutableStateOf(ThumbCache.get(v.id)) }
    LaunchedEffect(v.id) {
        if (bmp == null) {
            val b = withContext(Dispatchers.IO) {
                try { context.contentResolver.loadThumbnail(v.uri, Size(256, 256), null) } catch (e: Exception) { null }
            }
            if (b != null) { ThumbCache.put(v.id, b); bmp = b }
        }
    }
    Box(Modifier.aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(Palette.chip).clickable(onClick = onClick)) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            formatDuration(v.durationMs), fontSize = 11.sp, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Palette.scrim, androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
        )
    }
}
