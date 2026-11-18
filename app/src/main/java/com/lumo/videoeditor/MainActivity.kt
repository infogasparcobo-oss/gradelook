@file:OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)

package com.lumo.videoeditor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Barras del sistema oscuras con iconos claros, como Evoto.
        window.statusBarColor = android.graphics.Color.parseColor("#16181A")
        window.navigationBarColor = android.graphics.Color.parseColor("#16181A")
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Modo de pruebas por adb (ver DevHarness); no afecta el uso normal.
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        intent.getStringExtra("in")?.takeIf { debuggable }?.let { src ->
            DevHarness.run(
                this, src, intent.getStringExtra("out") ?: "$src.out.mp4", intent.getStringExtra("p") ?: "",
                intent.getIntExtra("br", 40_000_000), intent.getBooleanExtra("high", false),
                intent.getFloatExtra("ifr", 0f), intent.getBooleanExtra("perf", false),
                intent.getBooleanExtra("port", false), intent.getBooleanExtra("noeffect", false),
                intent.getIntExtra("bmode", -1), intent.getBooleanExtra("hevc", false), intent.getBooleanExtra("hq", false),
            )
        }
        setContent {
            MaterialTheme(colorScheme = Palette.scheme, typography = Palette.typography) {
                Box(Modifier.fillMaxSize().background(Palette.background)) { EditorScreen() }
            }
        }
    }
}

/** Herramientas en el orden de Kuni Cam (Ajustar/recorte no está incluido). */
private enum class Tool(val label: String, val adjs: List<Adj> = emptyList(), val subLabels: List<String> = emptyList()) {
    /** LUT 3D (.cube): se aplica primero, antes de los demás ajustes (p. ej. D-Log M -> color normal). */
    LUT("LUT"),
    BRIGHTNESS("Brillo", listOf(Adj.BRIGHTNESS)),
    CONTRAST("Contraste", listOf(Adj.CONTRAST)),
    HIGHLIGHTS("Luces", listOf(Adj.HIGHLIGHTS)),
    SHADOWS("Sombras", listOf(Adj.SHADOWS)),
    SATURATION("Saturación", listOf(Adj.SATURATION)),
    VIBRANCE("Vibrance", listOf(Adj.VIBRANCE)),
    TINT("Color", listOf(Adj.TINT)),
    HSL("HSL"),
    GRADE("Grad. color"),
    TEMPERATURE("Temperatura", listOf(Adj.TEMPERATURE)),
    GRAIN("Grano", listOf(Adj.GRAIN, Adj.GRAIN_SIZE, Adj.GRAIN_ROUGH), listOf("Cantidad", "Tamaño", "Aspereza")),
    FADE("Atenuar", listOf(Adj.FADE)),
    SHARPEN("Nitidez", listOf(Adj.SHARPEN)),
    TEXTURE("Textura", listOf(Adj.TEXTURE)),
    CLARITY("Claridad", listOf(Adj.CLARITY)),
    DEFINITION("Definición", listOf(Adj.DEFINITION)),
    LIGHTEN("Aclarar", listOf(Adj.LIGHTEN)),
    VIGNETTE("Viñeta", listOf(Adj.VIGNETTE)),
}

@Composable
private fun EditorScreen() {
    val context = LocalContext.current
    val holder = remember { AdjustmentHolder() }
    val values = remember { mutableStateListOf(*defaultValues().toTypedArray()) }
    val hsl = remember { mutableStateListOf(*Array(hslBands.size * 3) { 0f }) }
    val grade = remember { mutableStateListOf(*defaultGrade().toTypedArray()) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var backup by remember { mutableStateOf<AdjustmentSnapshot?>(null) }
    var band by remember { mutableStateOf(0) }
    // La barra se oculta mientras hay una herramienta abierta; su posición se conserva aquí para no volver a Brillo.
    val toolListState = androidx.compose.foundation.lazy.rememberLazyListState()
    var zone by remember { mutableStateOf(0) }
    var formulasTab by remember { mutableStateOf(false) }
    var filterCat by remember { mutableStateOf("Todos") }
    var activeFormula by remember { mutableStateOf<String?>(null) }
    val userFormulas = remember { mutableStateListOf<Formula>().also { it.addAll(Formulas.loadUser(context)) } }
    var fullscreen by remember { mutableStateOf(false) }
    // Velocidad de reproducción y de exportación (0.25x a 3x), como en TikTok.
    var speed by remember { mutableFloatStateOf(1f) }
    // Barras del sistema ocultas mientras está en pantalla completa; se restauran al salir o al cerrar la app.
    val hostWindow = context.findActivity()?.window
    DisposableEffect(fullscreen) {
        val ctl = hostWindow?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose { ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }
    var deleteTarget by remember { mutableStateOf<Formula?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }
    // Porción de la barra de progreso que ocupa el paso de efectos (1 = hardware solo, 0 = sin paso previo).
    var stage1Span by remember { mutableFloatStateOf(1f) }
    var x264Session by remember { mutableStateOf(0L) }
    var cancelled by remember { mutableStateOf(false) }
    // true mientras el video ya terminado se copia a la galería: en ese tramo ya no se puede cancelar.
    var saving by remember { mutableStateOf(false) }
    // Recorte: tramo (endMs 0 = hasta el final) y reencuadre 9:16 con la posición horizontal de la ventana (0..1).
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimEndMs by remember { mutableLongStateOf(0L) }
    var reframe by remember { mutableStateOf(false) }
    var panX by remember { mutableFloatStateOf(0.5f) }
    var showTrim by remember { mutableStateOf(false) }
    // Varias tomas: la 1.ª es el video principal (videoUri + recorte de arriba) y las demás van en `extras`, en orden.
    val extras = remember { androidx.compose.runtime.mutableStateListOf<Clip>() }
    var showClips by remember { mutableStateOf(false) }
    var addingClip by remember { mutableStateOf(false) }
    var trimIndex by remember { mutableStateOf(0) }   // toma que edita la hoja de Recortar (0 = la principal)
    val trimActive = trimStartMs > 0L || trimEndMs > 0L || reframe

    // LUT 3D importada (.cube): nombre elegido, intensidad (0..1) y datos ya leídos. El efecto se aplica antes de los ajustes.
    var lutNames by remember { mutableStateOf(LutStore.list(context)) }
    var lutName by remember { mutableStateOf<String?>(null) }
    var lutMix by remember { mutableFloatStateOf(1f) }
    var lutData by remember { mutableStateOf<LutData?>(null) }
    val lutHolder = remember { LutHolder() }

    fun mediaItemFor(uri: Uri): MediaItem = MediaItem.Builder().setUri(uri).apply {
        if (trimStartMs > 0L || trimEndMs > 0L) {
            setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(trimStartMs)
                    .apply { if (trimEndMs > 0L) setEndPositionMs(trimEndMs) }.build(),
            )
        }
    }.build()

    // Dos reproductores (con filtros / rápido sin filtros para velocidades > 1x); ver PreviewPlayers.
    // Marca de agua de creador (solo Pro): sus datos viven aquí y los leen la vista previa y la exportación.
    val wmHolder = remember { WatermarkHolder().also { it.update(context, WatermarkStore.load(context), Entitlements.isPro(context)) } }
    var wm by remember { mutableStateOf(WatermarkStore.load(context)) }
    var showWm by remember { mutableStateOf(false) }
    // Títulos: lista editable; la vista previa los dibuja con el reloj del reproductor (live) y la exportación con el suyo.
    val titleHolder = remember { TitleHolder().also { it.live = true } }
    var titles by remember { mutableStateOf<List<TitleItem>>(emptyList()) }
    var titleSel by remember { mutableStateOf<Long?>(null) }
    var showTitles by remember { mutableStateOf(false) }
    // Efecto de luz (entradas de sol): ajustes de esta edición; la vista previa y la exportación los leen.
    val lightHolder = remember { LightHolder() }
    var light by remember { mutableStateOf(LightSettings()) }
    var showLight by remember { mutableStateOf(false) }
    val pv = remember { PreviewPlayers(context, lutHolder, holder, wmHolder, titleHolder, lightHolder) }
    DisposableEffect(Unit) { onDispose { pv.release(); transformer?.cancel() } }

    /** (posición actual, duración total) en la línea de tiempo de todas las tomas, en ms. */
    fun timelineMs(): Pair<Long, Long> {
        val p = pv.cur
        val tl = p.currentTimeline
        if (tl.isEmpty) return 0L to 0L
        val w = androidx.media3.common.Timeline.Window()
        var before = 0L; var total = 0L
        for (i in 0 until tl.windowCount) {
            tl.getWindow(i, w)
            val d = if (w.durationMs == androidx.media3.common.C.TIME_UNSET) 0L else w.durationMs
            if (i < p.currentMediaItemIndex) before += d
            total += d
        }
        return (before + p.currentPosition) to total
    }
    // Mientras se editan títulos los dibuja la interfaz encima del video (se mueven con el dedo); el video no los repite.
    titleHolder.suppress = showTitles
    /** Lleva el video a un instante de la línea de tiempo (todas las tomas unidas). */
    fun seekTimelineMs(ms: Long) {
        val p = pv.cur
        val tl = p.currentTimeline
        if (tl.isEmpty) return
        val w = androidx.media3.common.Timeline.Window()
        var rem = ms.coerceAtLeast(0L)
        for (i in 0 until tl.windowCount) {
            tl.getWindow(i, w)
            val d = if (w.durationMs == androidx.media3.common.C.TIME_UNSET) Long.MAX_VALUE else w.durationMs
            if (rem < d || i == tl.windowCount - 1) { p.seekTo(i, rem.coerceAtMost(d)); return }
            rem -= d
        }
    }
    // Reloj de la edición de títulos: se lee solo al dibujar (no recompone la pantalla), así los efectos van fluidos.
    val titleNow = remember { mutableLongStateOf(0L) }
    var vidAspect by remember { mutableFloatStateOf(9f / 16f) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (titleHolder.active) titleHolder.liveMs = timelineMs().first
            if (showTitles) titleNow.longValue = timelineMs().first
            if ((showTitles || showLight) && n % 3 == 0) {
                val vs = pv.cur.videoSize
                if (vs.width > 0 && vs.height > 0) {
                    val a = vs.width * vs.pixelWidthHeightRatio / vs.height
                    if (kotlin.math.abs(a - vidAspect) > 0.001f) vidAspect = a
                }
            }
            n++
            delay(16)
        }
    }

    fun mainClip(): Clip? {
        val u = videoUri; val i = info
        return if (u != null && i != null) Clip(u, i.width, i.height, i.durationMs, trimStartMs, trimEndMs) else null
    }
    fun clipList(): List<Clip> = listOfNotNull(mainClip()) + extras

    /** Vuelve a cargar la vista previa con todas las tomas (lista de reproducción) desde la posición indicada. */
    fun refreshPreview(index: Int = 0, posMs: Long = 0L, play: Boolean = false) {
        val items = clipList().map { it.mediaItem() }
        if (items.isEmpty()) return
        pv.cur.setMediaItems(items, index.coerceIn(0, items.lastIndex), posMs)
        pv.cur.prepare(); pv.cur.playWhenReady = play
    }

    /** Reemplaza todas las tomas (tras reordenar o borrar). La primera pasa a ser la principal. */
    fun applyClipList(list: List<Clip>) {
        val first = list.first()
        if (first.uri != videoUri) {
            reframe = false; panX = 0.5f           // el reencuadre 9:16 es de la toma principal anterior
            videoUri = first.uri; info = readVideoInfo(context, first.uri)
            AppState.saveVideo(context, first.uri)
        }
        trimStartMs = first.startMs; trimEndMs = first.endMs
        extras.clear(); extras.addAll(list.drop(1))
    }

    fun snapshot() = AdjustmentSnapshot(values.toFloatArray(), hsl.toFloatArray(), grade.toFloatArray())

    // Miniaturas de los filtros: un fotograma del video actual con cada filtro aplicado (mismo shader que el video).
    // Se calculan al abrir la pestaña Filtros, o al cambiar de video o de filtros propios.
    var thumbs by remember { mutableStateOf<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    LaunchedEffect(formulasTab, videoUri, userFormulas.size) {
        val uri = videoUri
        if (!formulasTab || uri == null) return@LaunchedEffect
        val dur = pv.cur.duration.takeIf { it > 0 } ?: 0L
        val result: Map<String, androidx.compose.ui.graphics.ImageBitmap>? = withContext(Dispatchers.Default) {
            val frame = FilterThumbs.frame(context, uri, dur) ?: return@withContext null
            val all = listOf<Pair<String, AdjustmentSnapshot>>("Original" to AdjustmentSnapshot()) +
                (Formulas.presets + userFormulas).map { it.name to it.toSnapshot() }
            val bmps = FilterThumbs.render(frame, all.map { it.second }) ?: return@withContext null
            all.indices.associate { all[it].first to bmps[it].asImageBitmap() }
        }
        if (result != null) thumbs = result
    }

    // Comparación antes/después: mientras el usuario mantiene pulsado el video, se muestra el original.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showingOriginal by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(videoUri) {
        if (videoUri != null) { showHint = true; delay(3500); showHint = false }
    }

    // Con el video en pausa, un seekTo al mismo punto no siempre vuelve a pasar el cuadro por la cadena de efectos tras
    // un cambio de filtro, ajuste o LUT. Se fuerza con una reproducción mínima y silenciosa (~70 ms) y se vuelve al
    // punto de partida. Si llegan varios cambios seguidos (arrastrar un deslizador) solo se hace el último.
    var redrawJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun redrawPaused() {
        // playWhenReady = "el usuario quiere reproducir": isPlaying vale false unos instantes mientras carga (p. ej. al
        // cambiar los efectos o la velocidad) y no debe confundirse con una pausa real.
        if (pv.cur.isPlaying || pv.cur.playWhenReady) return
        redrawJob?.cancel()
        redrawJob = scope.launch {
            delay(60)
            val p = pv.cur.currentPosition
            pv.cur.volume = 0f
            pv.cur.play(); delay(70); pv.cur.pause()
            pv.cur.seekTo(p)
            pv.cur.volume = 1f
        }
    }

    // Al abrir el editor de títulos el video deja de dibujarlos (los dibuja la interfaz): se refresca el cuadro en pausa
    // para que no quede el título viejo debajo. Al cerrarlo se refresca desde el botón ✓.
    LaunchedEffect(showTitles) { if (showTitles) { delay(250); redrawPaused() } }   // tras acomodarse el panel

    fun pushChange() {
        holder.snapshot = snapshot()
        redrawPaused()
    }

    fun load(s: AdjustmentSnapshot) {
        s.values.forEachIndexed { i, v -> values[i] = v }
        s.hsl.forEachIndexed { i, v -> hsl[i] = v }
        s.grade.forEachIndexed { i, v -> grade[i] = v }
        pushChange()
    }

    // Elegir (o quitar con null) una LUT: se lee del almacén y se pasa al efecto (que siempre está en la cadena de la vista previa).
    fun applyLut(name: String?) {
        val data = name?.let { runCatching { LutStore.load(context, it) }.getOrNull() }
        lutName = if (data != null) name else null
        lutData = data
        lutHolder.lut = data
        lutHolder.mix = lutMix
        redrawPaused()
    }
    var lutBackup by remember { mutableStateOf<Pair<String?, Float>?>(null) }
    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            LutStore.import(context, uri)
                .onSuccess { name -> lutNames = LutStore.list(context); applyLut(name) }
                .onFailure { Toast.makeText(context, "LUT no válida: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun isModified(t: Tool): Boolean = when (t) {
        Tool.LUT -> lutData != null
        Tool.HSL -> hsl.any { it != 0f }
        Tool.GRADE -> (0 until 4).any { grade[it * 3 + 1] != 0f || grade[it * 3 + 2] != 0f }
        else -> t.adjs.any { isAdjModified(it, values[it.ordinal]) }
    }

    fun openTool(t: Tool) { backup = snapshot(); lutBackup = lutName to lutMix; tool = t }
    fun applyTool() { backup = null; tool = null; activeFormula = null }
    fun cancelTool() {
        backup?.let { load(it) }; backup = null
        if (tool == Tool.LUT) lutBackup?.let { (n, m) -> lutMix = m; lutHolder.mix = m; if (n != lutName) applyLut(n) }
        tool = null
    }

    fun resetTool(t: Tool) {
        when (t) {
            Tool.LUT -> applyLut(null)
            Tool.HSL -> hsl.indices.forEach { hsl[it] = 0f }
            Tool.GRADE -> defaultGrade().forEachIndexed { i, v -> grade[i] = v }
            else -> t.adjs.forEach { values[it.ordinal] = it.default }
        }
        pushChange()
    }

    // Guardar el filtro actual con un solo toque: nombre automático (Mi filtro 1, 2...) y queda seleccionado en "Míos".
    // Para borrarlo: mantener pulsado.
    fun saveCurrentFilter() {
        var n = userFormulas.size + 1
        while (userFormulas.any { it.name == "Mi filtro $n" }) n++
        val s = snapshot()
        val f = Formula("Mi filtro $n", s.values, s.hsl, s.grade)
        userFormulas.add(f)
        Formulas.saveUser(context, userFormulas)
        activeFormula = f.name
        filterCat = Formulas.MINE
        Toast.makeText(context, "Guardado en Míos como «${f.name}». Mantén pulsado para borrarlo", Toast.LENGTH_LONG).show()
    }
    // Preparando un video que el teléfono no abre directamente (p. ej. Sony A7 IV en 10 bits 4:2:2).
    val proxyOrigin = remember { mutableStateMapOf<Uri, Uri>() }
    var conv by remember { mutableStateOf<Triple<Uri, Float, Long>?>(null) }   // (original, progreso, id de sesión)
    val startProxy = remember { arrayOfNulls<(Uri, Long, Boolean) -> Unit>(1) }
    conv?.let { (_, p, sid) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparando tu video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Este video está en un formato (10 bits 4:2:2, típico de cámaras como la Sony A7 IV) que el teléfono no puede " +
                            "abrir directamente. Se convierte una sola vez a un formato compatible, casi sin pérdida; la próxima vez abre al instante.",
                        fontSize = 13.sp, color = Palette.textSoft,
                    )
                    androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()} %", fontSize = 12.sp, color = Palette.text)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { conv = null; if (sid != 0L) ProxyMaker.cancel(sid) }) { Text("Cancelar") } },
        )
    }
    deleteTarget?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Borrar \"${f.name}\"") },
            text = { Text("El filtro se eliminará de este teléfono.") },
            confirmButton = {
                TextButton(onClick = {
                    userFormulas.remove(f)
                    Formulas.saveUser(context, userFormulas)
                    if (activeFormula == f.name) activeFormula = null
                    deleteTarget = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
        )
    }

    // Borrar LUTs importadas (una o todas): siempre con confirmación, no se puede deshacer.
    var lutDelete by remember { mutableStateOf<List<String>?>(null) }
    lutDelete?.let { names ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { lutDelete = null },
            title = { Text(if (names.size == 1) "Eliminar LUT" else "Eliminar ${names.size} LUTs") },
            text = {
                Text(
                    if (names.size == 1) "¿Eliminar «${names[0]}»? Tendrás que importarla de nuevo para volver a usarla."
                    else "¿Eliminar todas las LUTs importadas? Tendrás que importarlas de nuevo para volver a usarlas.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    names.forEach { LutStore.delete(context, it) }
                    lutNames = LutStore.list(context)
                    if (lutName != null && lutName in names) applyLut(null)
                    lutDelete = null
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { lutDelete = null }) { Text("Cancelar") } },
        )
    }

    // La galería (como la pantalla de inicio de Kuni) se muestra al abrir y al pulsar "Otro video".
    var showGallery by remember { mutableStateOf(true) }

    // Lista de videos de la carpeta elegida: permite pasar al anterior / siguiente sin volver a la galería.
    var queue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // Videos que el teléfono no decodifica (cámaras de 10 bits 4:2:2 como la Sony A7 IV) se convierten a una copia compatible:
    // se guarda de qué video original viene cada copia, para que la lista de la carpeta y «recordar último video» sigan
    // apuntando al original.
    LaunchedEffect(videoUri) {
        val u = proxyOrigin[videoUri] ?: videoUri ?: return@LaunchedEffect
        if (u in queue || !hasVideoAccess(context)) return@LaunchedEffect
        // El video puede venir restaurado o de otra carpeta: la lista es la de su propia carpeta.
        val all = withContext(Dispatchers.IO) { queryVideos(context) }
        val cur = all.firstOrNull { it.uri == u } ?: return@LaunchedEffect
        queue = all.filter { it.album == cur.album }.map { it.uri }
    }

    fun loadVideo(uri: Uri, startMs: Long = 0L, autoplay: Boolean = true, remember: Boolean = true, origin: Uri? = null) {
        if (origin == null) {
            // ¿Lo puede abrir el teléfono? Si ya se convirtió antes se usa esa copia; si no, se convierte (una sola vez).
            val done = ProxyMaker.cached(context, uri)
            if (done != null) { loadVideo(Uri.fromFile(done), startMs, autoplay, remember, origin = uri); return }
            if (!ProxyMaker.canDecode(context, uri)) { startProxy[0]?.invoke(uri, startMs, autoplay); return }
        } else proxyOrigin[uri] = origin
        videoUri = uri
        info = readVideoInfo(context, uri)
        trimStartMs = 0L; trimEndMs = 0L; reframe = false; panX = 0.5f   // el recorte es por video
        titles = emptyList(); titleSel = null; titleHolder.update(emptyList())   // los títulos también
        light = LightSettings(); lightHolder.s = light                            // y la luz
        extras.clear()                                                   // abrir un video nuevo empieza de cero
        speed = 1f; pv.switchTo(false, 1f)   // y la velocidad también
        if (lutData != null) applyLut(null)                              // y la LUT también (depende del perfil de cámara)
        pv.cur.setMediaItem(mediaItemFor(uri), startMs)
        pv.cur.prepare()
        pv.cur.playWhenReady = autoplay
        showGallery = false
        if (remember) AppState.saveVideo(context, origin ?: uri)
    }

    // Convierte un video que el teléfono no puede abrir y lo carga al terminar. Si el reproductor falla con un video ya
    // cargado (decodificador ausente o error de efectos), se usa el mismo camino.
    startProxy[0] = { orig, startMs, autoplay ->
        if (conv == null) {
            val dur = readVideoInfo(context, orig)?.durationMs ?: 0L
            val mainH = Handler(Looper.getMainLooper())
            pv.cur.pause()
            conv = Triple(orig, 0f, 0L)
            val id = ProxyMaker.convert(
                context, orig, dur,
                onProgress = { p -> mainH.post { conv = conv?.let { Triple(it.first, p, it.third) } } },
                onDone = { f, err ->
                    mainH.post {
                        val wasCancelled = conv == null
                        conv = null
                        if (f != null && !wasCancelled) loadVideo(Uri.fromFile(f), startMs, autoplay, true, origin = orig)
                        else if (f == null && !wasCancelled) Toast.makeText(context, "No se pudo preparar este video${if (err != null) ": $err" else ""}", Toast.LENGTH_LONG).show()
                    }
                },
            )
            conv = conv?.let { Triple(it.first, it.second, id) }
        }
    }
    DisposableEffect(Unit) {
        pv.onUndecodable = {
            val cur = videoUri
            // Si la copia compatible también falla no se repite el intento: sería un bucle.
            if (cur != null && proxyOrigin[cur] == null) startProxy[0]?.invoke(cur, pv.cur.currentPosition, false)
        }
        onDispose { pv.onUndecodable = null }
    }

    /** Añade una toma al final y sigue reproduciendo donde estaba. */
    fun addClip(uri: Uri) {
        val vi = readVideoInfo(context, uri)
        showGallery = false; addingClip = false
        if (vi == null) { Toast.makeText(context, "No se pudo leer ese video", Toast.LENGTH_LONG).show(); return }
        extras.add(Clip(uri, vi.width, vi.height, vi.durationMs))
        val c = pv.cur
        refreshPreview(c.currentMediaItemIndex, c.currentPosition, c.playWhenReady)
        Toast.makeText(context, "Toma añadida (${extras.size + 1} en total)", Toast.LENGTH_SHORT).show()
        showClips = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Permiso persistente para poder reabrir este archivo la próxima vez que se abra la app.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (addingClip) addClip(uri) else loadVideo(uri)
        }
    }

    // Al abrir la app se vuelve al último video (en pausa), en vez de empezar desde la galería.
    LaunchedEffect(Unit) {
        val last = AppState.lastVideo(context)
        if (last != null && AppState.canOpen(context, last)) loadVideo(last, AppState.lastPosition(context), autoplay = false, remember = false)
    }

    // La reproducción se detiene en cuanto el usuario sale de la app o abre la galería para cambiar de video o
    // carpeta. Solo la exportación sigue en segundo plano (la gestiona ExportService).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && !exporting) {
                pv.cur.pause()
                if (videoUri != null) AppState.savePosition(context, pv.cur.currentPosition)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(showGallery) { if (showGallery) pv.cur.pause() }

    // Mientras se exporta: servicio en primer plano (evita que Android cierre la app si se apaga la pantalla o
    // se cambia de app) y pantalla encendida. Al terminar o cancelar, todo se libera.
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(exporting) {
        hostView.keepScreenOn = exporting
        if (exporting) ExportService.start(context)
        onDispose {
            hostView.keepScreenOn = false
            ExportService.stop(context)
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Limpia restos de exportaciones interrumpidas (pueden ocupar cientos de MB en la caché).
    remember {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("stage1_") || it.name.startsWith("export_") }?.forEach { it.delete() }
        0
    }

    // Durante la exportación se libera el reproductor de la vista previa (video 4K + búfer + decodificador)
    // para dejar memoria y decodificadores de hardware a la exportación. Al terminar se recarga donde estaba.
    var resumePosition by remember { mutableStateOf(0L) }
    var resumeIndex by remember { mutableStateOf(0) }
    var previewReleased by remember { mutableStateOf(false) }
    LaunchedEffect(exporting) {
        if (exporting) {
            if (videoUri != null && !previewReleased) {
                resumePosition = pv.cur.currentPosition; resumeIndex = pv.cur.currentMediaItemIndex
                pv.cur.stop(); pv.cur.clearMediaItems(); previewReleased = true
            }
        } else if (previewReleased) {
            refreshPreview(resumeIndex, resumePosition, play = true)
            previewReleased = false
        }
    }

    LaunchedEffect(exporting) {
        while (exporting) {
            val ph = ProgressHolder()
            val t = transformer
            if (t != null && stage1Span > 0f && t.getProgress(ph) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                progress = ph.progress / 100f * stage1Span
            }
            delay(300)
        }
    }

    // Plan gratis / Pro. `planTick` fuerza a releer el contador de exportaciones y el estado Pro.
    var planTick by remember { mutableStateOf(0) }
    var showPro by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") planTick
    val isPro = Entitlements.isPro(context)
    // Al cambiar de plan (compra, código de acceso) la marca de agua se activa o desactiva sin tener que salir de la app.
    LaunchedEffect(isPro) { wmHolder.update(context, wm, isPro) }

    // Con el servidor activo, cada exportación reserva un cupo (ticket) antes de empezar: se confirma al guardarse el
    // video y se devuelve si falla o se cancela.
    var exportTicket by remember { mutableStateOf<String?>(null) }
    fun closeTicket(ok: Boolean) {
        val t = exportTicket ?: return
        exportTicket = null
        scope.launch {
            runCatching { if (ok) Server.complete(context, t) else Server.refund(context, t) }
            runCatching { Server.refresh(context) }
            planTick++
        }
    }
    LaunchedEffect(exporting) { if (!exporting && exportTicket != null) closeTicket(false) }

    // Compras con Google Play (solo con el servidor activo): precios, compra y restauración al abrir.
    val billing = remember {
        BillingManager(
            context, scope,
            onChanged = { planTick++ },
            onMessage = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() },
        )
    }
    DisposableEffect(Unit) { billing.connect(); onDispose { billing.disconnect() } }
    LaunchedEffect(Unit) { if (Server.enabled) { runCatching { Server.refresh(context) }; planTick++ } }

    /** Exportación (ajustes + tamaño + códec + marca de agua) del video [uri]. */
    fun runExport(opts: ExportOptions, uri: Uri, src: VideoInfo) {
        cancelled = false; saving = false   // estado limpio en cada exportación (también en la ruta hardware)
        val stamp = System.currentTimeMillis()
        val outFile = File(context.cacheDir, "export_$stamp.mp4")
        val snap = snapshot()
        val neutral = snap.isNeutral()
        // Recorte: `src` ya viene con el ancho reencuadrado y la duración del tramo; aquí se reconstruye la ventana 9:16
        // (fracción del ancho original y su posición) para el efecto Crop, que trabaja en coordenadas -1..1.
        val fullW = info?.width ?: src.width
        val cropFrac = if (fullW > 0 && src.width < fullW) src.width.toFloat() / fullW else 1f
        val cropEffect: androidx.media3.common.Effect? = if (cropFrac < 0.999f) {
            val left = panX * (1f - cropFrac)
            androidx.media3.effect.Crop(2f * left - 1f, 2f * (left + cropFrac) - 1f, -1f, 1f)
        } else null
        val clipped = trimStartMs > 0L || trimEndMs > 0L
        // LUT 3D: efecto propio de esta exportación, con los datos y la intensidad elegidos.
        val lutEffect: androidx.media3.common.Effect? = lutData?.let { d ->
            LutEffect(LutHolder().also { h -> h.lut = d; h.mix = lutMix })
        }
        val multi = extras.isNotEmpty()   // varias tomas: se unen en una composición
        wmHolder.update(context, wm, Entitlements.isPro(context))
        val wmEffect: androidx.media3.common.Effect? = if (wmHolder.active) Watermark.effect(wmHolder) else null
        // Títulos: un soporte propio de esta exportación (el tiempo sale del fotograma que se codifica, no del reproductor).
        val titleExport = TitleHolder().also { it.live = false; if (Entitlements.isPro(context)) it.update(titles) }
        val titleEffect: androidx.media3.common.Effect? = if (titleExport.active) Titles.effect(titleExport) else null
        // Luz: efecto propio de esta exportación (solo con plan Pro y si está activa).
        val lightEffect: androidx.media3.common.Effect? = if (light.enabled && light.intensity > 0f && Entitlements.isPro(context)) {
            LightEffect(LightHolder().also { it.s = light })
        } else null
        val hasEdit = cropEffect != null || clipped || lutEffect != null || speed != 1f || multi || wmEffect != null || titleEffect != null || lightEffect != null
        // Velocidad (como en TikTok): cambia video y audio. La duración ya viene dividida entre la velocidad en `src`.
        val videoSpeed: androidx.media3.common.Effect? = if (speed != 1f) androidx.media3.effect.SpeedChangeEffect(speed) else null
        val audioSpeed: List<androidx.media3.common.audio.AudioProcessor> = if (speed != 1f) {
            listOf(androidx.media3.common.audio.SonicAudioProcessor().also { it.setSpeed(speed); it.setPitch(1f) })
        } else emptyList()
        // Cómo se cobra esta exportación (gratis, mes o ilimitado). Se decide ahora, al empezar; BLOCKED ya se filtró antes.
        val exportMode = Entitlements.exportMode(context)

        // Varias tomas: cada una se ajusta al tamaño de salida (la 1.ª lleva además el reencuadre 9:16) y luego se unen;
        // filtro, LUT y velocidad se aplican una sola vez sobre el conjunto. Si falta audio en alguna toma se rellena.
        val allClips = clipList()
        fun compositionOf(videoFx: List<androidx.media3.common.Effect>): Composition {
            val items = allClips.mapIndexed { i, c ->
                val fx = buildList<androidx.media3.common.Effect> {
                    if (i == 0) cropEffect?.let { add(it) }
                    add(Presentation.createForWidthAndHeight(src.width, src.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                }
                EditedMediaItem.Builder(c.mediaItem()).setEffects(Effects(emptyList(), fx)).build()
            }
            return Composition.Builder(EditedMediaItemSequence.Builder(items).build())
                .setEffects(Effects(audioSpeed, videoFx)).build()
        }

        if (opts.x264) {
            // Motor x264 (misma receta que Smart Video Compressor). Con efectos: paso 1 = efectos con el
            // hardware a muy alto bitrate (intermedio casi sin pérdida); paso 2 = x264 al bitrate final.
            val stage1 = File(context.cacheDir, "stage1_$stamp.mp4")
            val mainH = Handler(Looper.getMainLooper())
            val (ow, oh) = opts.outputSize(src)
            cancelled = false
            fun cleanup() { outFile.delete(); stage1.delete() }
            fun fail(msg: String) = mainH.post {
                if (!cancelled) Toast.makeText(context, "Error al exportar: $msg", Toast.LENGTH_LONG).show()
                cleanup(); exporting = false
            }
            // Velocidad de x264 automática: codificar 4K o 60 cuadros/s en CPU con "fast" tarda minutos (medido: 161 s para
            // 19 s de video 4K60, a ~7 cuadros/s). A partir de esa carga se usa "veryfast", que es ~2x más rápido y a
            // estos bitrates altos casi no se nota; el resto sigue con el preset normal.
            val heavy = ow.toLong() * oh * min(src.fps, 60f) > 1920L * 1080 * 30 * 2.5f
            val effPreset = if (heavy && opts.preset != "medium") "veryfast" else opts.preset
            fun runX264(input: String, from: Float, span: Float) {
                x264Session = X264Encoder.encode(
                    context, input, outFile.absolutePath, ow, oh, ow != src.width || oh != src.height,
                    opts.bitrate / 1000, opts.h265, effPreset,
                    null, src.durationMs,
                    tiktokFps = if (opts.tiktok && !opts.h265) min(src.fps, 60f) else null,
                    crf = opts.crf,
                    onProgress = { p -> mainH.post { progress = from + span * p } },
                    onDone = { ok, err ->
                        saving = true   // desde aquí ya no se puede cancelar
                        if (cancelled) { cleanup(); mainH.post { saving = false; exporting = false } }   // cancelado: no se guarda ni se cuenta
                        else if (!ok) { saving = false; fail(err ?: "x264") }
                        else Thread {
                            val saved = saveToGallery(context, outFile)
                            if (saved) Entitlements.registerExport(context, exportMode)
                            closeTicket(saved)
                            cleanup()
                            mainH.post {
                                planTick++
                                Toast.makeText(context, if (saved) "Guardado en Movies/Gradelook" else "No se pudo guardar", Toast.LENGTH_LONG).show()
                                saving = false; exporting = false; showExport = false
                            }
                        }.start()
                    },
                )
            }
            progress = 0f
            exporting = true
            if (neutral && !hasEdit) {
                stage1Span = 0f
                runX264(X264Encoder.inputFor(context, uri), 0f, 1f)
            } else {
                // Paso 1: recorte (tramo + ventana 9:16) y ajustes con el hardware, a muy alto bitrate.
                // La barra refleja el tiempo real: este paso es rápido (medido: ~10 % del total) y x264 es el resto.
                stage1Span = 0.15f
                val adjust = if (!neutral) AdjustmentsEffect(AdjustmentHolder().also { it.snapshot = snap }) else null
                val comp = if (multi) compositionOf(listOfNotNull(lutEffect, videoSpeed, adjust, lightEffect, wmEffect, titleEffect)) else null
                val edited = if (multi) null else EditedMediaItem.Builder(mediaItemFor(uri))
                    .setEffects(Effects(audioSpeed, listOfNotNull(cropEffect, lutEffect, videoSpeed, adjust, lightEffect, wmEffect, titleEffect)))
                    .build()
                val stageBitrate = max(src.bitrate * 3, (src.width.toFloat() * src.height * min(src.fps, 60f) * 0.4f).toInt())
                    .coerceIn(20_000_000, 100_000_000)
                val enc = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(stageBitrate).build())
                    .build()
                val t1 = Transformer.Builder(context)
                    .setPortraitEncodingEnabled(true)
                    .setEncoderFactory(enc)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            stage1Span = 0f
                            runX264(stage1.absolutePath, 0.15f, 0.85f)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            fail(exportException.message ?: "efectos")
                        }
                    })
                    .build()
                transformer = t1
                if (comp != null) t1.start(comp, stage1.absolutePath) else t1.start(edited!!, stage1.absolutePath)
            }
            return
        }

        val videoEffects = buildList<androidx.media3.common.Effect> {
            if (!multi) cropEffect?.let { add(it) }   // con varias tomas el reencuadre va dentro de la composición
            lutEffect?.let { add(it) }
            videoSpeed?.let { add(it) }
            if (!neutral) add(AdjustmentsEffect(AdjustmentHolder().also { it.snapshot = snap }))
            lightEffect?.let { add(it) }
            wmEffect?.let { add(it) }
            titleEffect?.let { add(it) }
            val (w, h) = opts.outputSize(src)
            if (w != src.width || h != src.height) add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
        }
        val comp = if (multi) compositionOf(videoEffects) else null
        val edited = if (multi) null else EditedMediaItem.Builder(mediaItemFor(uri))
            .setEffects(Effects(audioSpeed, videoEffects)).build()
        val encoder = DefaultEncoderFactory.Builder(context)
            // Fotograma clave cada 2 s (el valor por defecto de Media3 es 1 s y gasta bits de más).
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(opts.bitrate).setiFrameIntervalSeconds(2f).build(),
            )
            .build()
        val main = Handler(Looper.getMainLooper())
        val t = Transformer.Builder(context)
            // Vertical real (1080x1920) en vez de horizontal + etiqueta de rotación: más compatible con redes sociales.
            .setPortraitEncodingEnabled(true)
            .setVideoMimeType(if (opts.h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoder)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saving = true   // desde aquí ya no se puede cancelar
                    if (cancelled) { outFile.delete(); main.post { saving = false; exporting = false }; return }   // cancelado: no se guarda ni se cuenta
                    Thread {
                        val saved = saveToGallery(context, outFile)
                        if (saved) Entitlements.registerExport(context, exportMode)
                        closeTicket(saved)
                        outFile.delete()
                        main.post {
                            planTick++
                            Toast.makeText(context, if (saved) "Guardado en Movies/Gradelook" else "No se pudo guardar", Toast.LENGTH_LONG).show()
                            exporting = false
                            showExport = false
                        }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Toast.makeText(context, "Error al exportar: ${exportException.message}", Toast.LENGTH_LONG).show()
                    outFile.delete()
                    exporting = false
                }
            })
            .build()
        transformer = t
        progress = 0f
        stage1Span = 1f
        exporting = true
        if (comp != null) t.start(comp, outFile.absolutePath) else t.start(edited!!, outFile.absolutePath)
    }

    // Datos del video ya recortado: ancho reencuadrado a 9:16 (si está activo) y duración del tramo elegido.
    val effInfo: VideoInfo? = info?.let { fi ->
        val end = if (trimEndMs in 1 until fi.durationMs) trimEndMs else fi.durationMs
        var w = fi.width
        if (reframe && canReframe916(fi.width, fi.height)) w = max(2, (fi.height * 9f / 16f / 2f).roundToInt() * 2)
        // Duración de todas las tomas unidas (con su recorte) dividida entre la velocidad.
        val total = clipList().sumOf { it.effDurationMs }
        fi.copy(width = w, durationMs = (total / speed).toLong().coerceAtLeast(500L))
    }

    fun startExport(opts: ExportOptions) {
        val uri = videoUri ?: return
        val src = effInfo ?: return
        if (Server.enabled) {
            // Servidor: él decide si hay cupo. Sin conexión no se exporta (no se puede contar la exportación).
            scope.launch {
                try {
                    val a = Server.authorize(context)
                    planTick++
                    if (!a.ok || a.ticket == null) { showPro = true; return@launch }
                    exportTicket = a.ticket
                    runExport(opts, uri, src)
                } catch (e: Server.ServerException) {
                    Toast.makeText(context, if (e.network) "Necesitas conexión a internet para exportar" else "No se pudo autorizar la exportación: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // Sin servidor (depuración): contadores locales. Sin cupo se bloquea y se muestran los planes.
        if (Entitlements.exportMode(context) == Entitlements.Mode.BLOCKED) { showPro = true; return }
        // Android 13+: la notificación del servicio necesita permiso (sin él la exportación funciona igual, pero sin aviso).
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        runExport(opts, uri, src)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (videoUri == null) {
                Button(onClick = { showGallery = true }) {
                    Text("Elegir video")
                }
            } else {
                // Se recrea la vista al entrar o salir de pantalla completa: si no, la superficie del video conserva el
                // tamaño anterior y la imagen sale estirada.
                androidx.compose.runtime.key(fullscreen, pv.fast) {
                    AndroidView(
                        // Sin controles flotantes en el centro: la barra de reproducción va abajo (PlayerBar).
                        factory = { ctx -> PlayerView(ctx).apply { this.player = pv.cur; useController = false } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Mantener pulsado el video = ver el original sin ajustes; al soltar vuelve la edición.
                // Se deja libre la franja inferior para la barra de reproducción.
                Box(
                    Modifier.fillMaxSize().padding(top = 56.dp, bottom = 64.dp).pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                val t0 = System.currentTimeMillis()
                                var held = false
                                val job = scope.launch { delay(250); held = true; showingOriginal = true
                                    holder.snapshot = AdjustmentSnapshot(); lutHolder.enabled = false
                                    if (!pv.cur.isPlaying) pv.cur.seekTo(pv.cur.currentPosition) }
                                val released = tryAwaitRelease()
                                job.cancel()
                                if (held) {
                                    // Pulsación larga: se vuelve a la edición y NO se cambia play/pausa.
                                    showingOriginal = false
                                    holder.snapshot = snapshot(); lutHolder.enabled = true
                                    if (!pv.cur.isPlaying) pv.cur.seekTo(pv.cur.currentPosition)
                                } else if (released && System.currentTimeMillis() - t0 < 250) {
                                    if (pv.cur.isPlaying) pv.cur.pause() else pv.cur.play()   // toque corto
                                }
                            },
                        )
                    },
                )
                // Colocar el sol tocando o arrastrando sobre el video.
                if (showLight) {
                    LightPickLayer(
                        settings = light, aspect = vidAspect,
                        onChange = { n -> light = n; lightHolder.s = n; redrawPaused() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Títulos editables sobre el video (arrastrar / pellizcar), mismo cuadro que ocupa el video.
                if (showTitles) {
                    TitleEditLayer(
                        titles = titles, selectedId = titleSel, now = titleNow, aspect = vidAspect,
                        onSelect = { titleSel = it },
                        onChange = { n -> titles = n; titleHolder.update(n) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                PlayerBar(
                    pv.cur, Modifier.align(Alignment.BottomCenter), speed = speed,
                    // Con varias tomas la línea de tiempo cubre el video completo, no solo la toma que suena.
                    clipDurations = if (extras.isNotEmpty()) clipList().map { it.effDurationMs } else emptyList(),
                // Hasta 1.5x la vista previa sigue con filtros, marca, títulos y luz; con más velocidad pasa al reproductor rápido.
                ) { s -> speed = s; pv.switchTo(s > 1.5f, s) }

                // Anterior / siguiente video de la carpeta, sin pasar por la galería.
                val qi = queue.indexOf(proxyOrigin[videoUri] ?: videoUri)
                if (qi >= 0 && queue.size > 1) {
                    NavArrow("‹", "Video anterior", enabled = qi > 0 && !exporting, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                        queue.getOrNull(qi - 1)?.let { loadVideo(it) }
                    }
                    NavArrow("›", "Video siguiente", enabled = qi < queue.size - 1 && !exporting, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                        queue.getOrNull(qi + 1)?.let { loadVideo(it) }
                    }
                    Text(
                        "${qi + 1} / ${queue.size}", fontSize = 12.sp, color = Palette.text,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 58.dp, end = 10.dp)
                            .background(Color(0xB31A1C1E), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }

                // Barra superior: galería, recortar y exportar (antes eran tres botones grandes abajo).
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!fullscreen) TopIcon("‹", "Galería") { showGallery = true }
                    Spacer(Modifier.weight(1f))
                    // Ampliar / reducir: con el video en pantalla completa solo queda este botón para salir.
                    TopIcon(if (fullscreen) "⤡" else "⤢", if (fullscreen) "Reducir" else "Pantalla completa") { fullscreen = !fullscreen }
                    if (!fullscreen) {
                        // Recortar la toma que se está viendo (con varias tomas, la de la posición actual).
                        TopIcon(if (trimActive || extras.isNotEmpty()) "✂•" else "✂", "Recortar", enabled = info != null) {
                            trimIndex = pv.cur.currentMediaItemIndex.coerceIn(0, extras.size); showTrim = true
                        }
                        TopIcon(if (extras.isEmpty()) "＋" else "${extras.size + 1}", "Tomas del video", enabled = info != null) { showClips = true }
                        Button(
                            onClick = { showExport = true }, enabled = info != null,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                            modifier = Modifier.height(38.dp),
                        ) { Text("Exportar", fontSize = 14.sp, maxLines = 1) }
                    }
                }
                if (pv.fast) {
                    Text(
                        "Vista rápida: sin filtros (el video exportado sí los lleva)", fontSize = 11.sp, color = Palette.text,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 62.dp)
                            .background(Palette.scrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (showingOriginal) {
                    Text(
                        "ORIGINAL", fontSize = 12.sp, color = Palette.onAccent,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp)
                            .background(Palette.accent, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                } else if (showHint) {
                    Text(
                        "Mantén pulsado el video: ver original", fontSize = 12.sp, color = Palette.text,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp)
                            .background(Palette.scrim, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }

        val current = tool
        // Pantalla completa: se oculta todo el panel de abajo (herramientas, filtros y pestañas).
        if (!fullscreen) {
        if (showWm) {
            // Marca de agua: panel integrado, no tapa el video y se puede reproducir mientras se ajusta.
            WatermarkPanel(
                settings = wm,
                onChange = { n -> wm = n; WatermarkStore.save(context, n); wmHolder.update(context, n, Entitlements.isPro(context)) },
                onClose = { showWm = false },
            )
        } else if (showLight) {
            LightPanel(
                settings = light,
                onChange = { n -> light = n; lightHolder.s = n; redrawPaused() },
                onClose = { showLight = false },
            )
        } else if (showTitles) {
            TitlePanel(
                titles = titles, selectedId = titleSel, onSelect = { titleSel = it },
                onChange = { n -> titles = n; titleHolder.update(n) },
                onAdd = {
                    val (pos, total) = timelineMs()
                    val len = 3000L
                    val start = if (total > 0 && pos + len > total) (total - len).coerceAtLeast(0L) else pos
                    val end = if (total > 0) minOf(start + len, total) else start + len
                    val t = TitleItem(id = System.nanoTime(), startMs = start, endMs = end)
                    titles = titles + t; titleSel = t.id; titleHolder.update(titles)
                },
                now = titleNow, totalMs = { timelineMs().second },
                onSeek = { seekTimelineMs(it) },
                // «Ver»: empieza un poco antes del título para ver cómo aparece, se reproduce y desaparece.
                onPreview = { s -> seekTimelineMs((s - 600L).coerceAtLeast(0L)); pv.cur.play() },
                onClose = { showTitles = false; redrawPaused() },
            )
        } else if (current != null) {
            // Panel de herramienta (como Kuni): título, deslizadores, y X / REINICIAR / ✓ abajo.
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(current.label, fontSize = 15.sp, color = Palette.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
                when (current) {
                    Tool.LUT -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item { FilterChip(selected = lutName == null, onClick = { applyLut(null) }, label = { Text("Ninguna", fontSize = 11.sp) }) }
                            items(lutNames) { n ->
                                FilterChip(selected = lutName == n, onClick = { applyLut(n) }, label = { Text(n, fontSize = 11.sp, maxLines = 1) })
                            }
                            item {
                                AssistChip(onClick = { lutPicker.launch(arrayOf("*/*")) }, label = { Text("＋ Importar .cube", fontSize = 11.sp) })
                            }
                        }
                        if (lutName != null) {
                            AdjSlider("Intensidad", lutMix, 0f, 1f) { lutMix = it; lutHolder.mix = it; redrawPaused() }
                        } else {
                            Text(
                                "Importa una LUT 3D (.cube), por ejemplo la de D-Log M a Rec.709 del DJI, y se aplicará antes de tus ajustes.",
                                fontSize = 12.sp, color = Palette.textSoft,
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                        if (lutNames.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (lutName != null) {
                                    TextButton(onClick = { lutDelete = listOf(lutName!!) }) {
                                        Text("Eliminar «$lutName»", fontSize = 12.sp, color = Palette.textSoft, maxLines = 1)
                                    }
                                } else Spacer(Modifier.width(1.dp))
                                TextButton(onClick = { lutDelete = lutNames }) {
                                    Text("Eliminar todas (${lutNames.size})", fontSize = 12.sp, color = Palette.textSoft, maxLines = 1)
                                }
                            }
                        }
                    }
                    Tool.HSL -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(hslBands.indices.toList()) { i ->
                                FilterChip(
                                    selected = band == i,
                                    onClick = { band = i },
                                    label = { Text(hslBands[i].first, fontSize = 11.sp) },
                                    leadingIcon = {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color.hsv(hslBands[i].second * 360f, 1f, 1f)))
                                    },
                                )
                            }
                        }
                        listOf("Tono", "Saturación", "Luminancia").forEachIndexed { j, label ->
                            val idx = band * 3 + j
                            AdjSlider(label, hsl[idx], -1f, 1f) { hsl[idx] = it; pushChange() }
                        }
                    }
                    Tool.GRADE -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(gradeZones.indices.toList()) { i ->
                                FilterChip(selected = zone == i, onClick = { zone = i }, label = { Text(gradeZones[i], fontSize = 11.sp) })
                            }
                        }
                        val b = zone * 3
                        AdjSlider("Tono", grade[b], 0f, 1f) { grade[b] = it; pushChange() }
                        AdjSlider("Saturación", grade[b + 1], 0f, 1f) { grade[b + 1] = it; pushChange() }
                        AdjSlider("Luminancia", grade[b + 2], -1f, 1f) { grade[b + 2] = it; pushChange() }
                        AdjSlider("Fusión", grade[GRADE_BLEND], 0f, 1f) { grade[GRADE_BLEND] = it; pushChange() }
                        AdjSlider("Equilibrio", grade[GRADE_BALANCE], -1f, 1f) { grade[GRADE_BALANCE] = it; pushChange() }
                    }
                    else -> current.adjs.forEachIndexed { j, adj ->
                        val i = adj.ordinal
                        val label = current.subLabels.getOrNull(j)
                        AdjSlider(label ?: "", values[i], adj.min, adj.max, showLabel = label != null) { values[i] = it; pushChange() }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { cancelTool() }) { Text("✕", fontSize = 22.sp, color = Palette.textSoft) }
                    TextButton(onClick = { resetTool(current) }, modifier = Modifier.weight(1f)) {
                        Text("REINICIAR", fontSize = 13.sp, color = Palette.text)
                    }
                    TextButton(onClick = { applyTool() }) { Text("✓", fontSize = 24.sp, color = Palette.accent) }
                }
            }
        } else if (formulasTab) {
            // Filtros: Original, los incluidos (por categoría) y los tuyos.
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(listOf("Todos") + Formulas.categories + Formulas.MINE) { cat ->
                        FilterChip(
                            selected = filterCat == cat, onClick = { filterCat = cat },
                            label = { Text(cat, fontSize = 12.sp, maxLines = 1) },
                        )
                    }
                    // Guardar los ajustes actuales como filtro propio (antes era un botón de ancho completo).
                    item {
                        androidx.compose.material3.AssistChip(
                            onClick = { if (Entitlements.canSaveFormula(context, userFormulas.size)) saveCurrentFilter() else showPro = true },
                            label = { Text("＋ Guardar", fontSize = 12.sp, maxLines = 1) },
                        )
                    }
                }
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FormulaCard("Original", thumbs["Original"], activeFormula == null, false) { load(AdjustmentSnapshot()); activeFormula = null }
                    }
                    items(
                        when (filterCat) {
                            "Todos" -> Formulas.presets + userFormulas
                            Formulas.MINE -> userFormulas.toList()
                            else -> Formulas.presets.filter { it.category == filterCat }
                        },
                    ) { f ->
                        val locked = !isPro && f.builtIn && !f.free   // isPro es estado: la lista se redibuja al cambiar de plan
                        FormulaCard(
                            if (locked) "🔒 ${f.name}" else f.name, thumbs[f.name], activeFormula == f.name, !f.builtIn,
                            onLongClick = { if (!f.builtIn) deleteTarget = f },
                        ) {
                            // Filtros Pro: se muestra la hoja con lo que incluye Pro en vez de aplicarlos.
                            if (locked) showPro = true else { load(f.toSnapshot()); activeFormula = f.name }
                        }
                    }
                }
            }
        } else {
            // Barra de herramientas (nombre arriba, círculo con valor debajo).
            LazyRow(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                state = toolListState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    ToolButton(tool = "WATERMARK", label = "Marca", valueText = "", modified = wm.enabled && isPro) {
                        if (Entitlements.isPro(context)) showWm = true else showPro = true
                    }
                }
                item {
                    ToolButton(tool = "LIGHT", label = "Luz", valueText = "", modified = light.enabled && isPro) {
                        if (Entitlements.isPro(context)) showLight = true else showPro = true
                    }
                }
                item {
                    ToolButton(tool = "TITLE", label = "Título", valueText = if (titles.isEmpty()) "" else "${titles.size}", modified = titles.isNotEmpty()) {
                        if (Entitlements.isPro(context)) showTitles = true else showPro = true
                    }
                }
                items(Tool.entries.toList()) { t ->
                    ToolButton(tool = t.name, label = t.label, valueText = toolBadge(t, values, hsl, grade), modified = isModified(t)) { openTool(t) }
                }
            }
        }

        if (current == null && !showWm && !showTitles && !showLight) {
            // Editar | Filtros (como las pestañas de Kuni).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { formulasTab = false }) {
                    Text("Editar", color = if (!formulasTab) Palette.accent else Palette.textSoft)
                }
                TextButton(onClick = { formulasTab = true }) {
                    Text("Filtros", color = if (formulasTab) Palette.accent else Palette.textSoft)
                }
            }
        }
        }   // fin de !fullscreen
    }

    if (showGallery) {
        GalleryScreen(
            canClose = videoUri != null,
            // Si se abrió para añadir una toma, lo elegido se suma al final en vez de reemplazar el video.
            onPick = { u, list -> if (addingClip) addClip(u) else { queue = list; loadVideo(u) } },
            onBrowse = { picker.launch(arrayOf("video/*")) },
            onClose = { showGallery = false; if (addingClip) { addingClip = false; showClips = true } },
        )
    }

    // Recortar una toma: la principal (con reencuadre 9:16) o cualquiera de las añadidas.
    val trimClips = clipList()
    val trimTarget = trimClips.getOrNull(trimIndex)
    if (showTrim && trimTarget != null) {
        TrimSheet(
            uri = trimTarget.uri, durationMs = trimTarget.durationMs, videoW = trimTarget.width, videoH = trimTarget.height,
            startMs = trimTarget.startMs, endMs = trimTarget.endMs,
            reframe = trimIndex == 0 && reframe, panX = panX, allowReframe = trimIndex == 0,
            onApply = { s, e, rf, p ->
                if (trimIndex == 0) { trimStartMs = s; trimEndMs = e; reframe = rf; panX = p }
                else extras[trimIndex - 1] = extras[trimIndex - 1].copy(startMs = s, endMs = e)
                showTrim = false
                // La vista previa solo reproduce el tramo elegido; vuelve al inicio de la toma recortada.
                refreshPreview(trimIndex, 0L, play = false)
            },
            onDismiss = { showTrim = false },
        )
    }

    // Tomas del video: añadir, borrar, reordenar y recortar.
    if (showClips && clipList().isNotEmpty()) {
        ClipsSheet(
            clips = clipList(), current = pv.cur.currentMediaItemIndex,
            onAdd = { showClips = false; addingClip = true; showGallery = true },
            onTrim = { i -> showClips = false; trimIndex = i; showTrim = true },
            onMove = { i, d ->
                val l = clipList().toMutableList(); val j = i + d
                if (j in l.indices) { l.add(j, l.removeAt(i)); applyClipList(l); refreshPreview(j, 0L, play = false) }
            },
            onDelete = { i ->
                val l = clipList().toMutableList()
                if (l.size > 1) { l.removeAt(i); applyClipList(l); refreshPreview(i.coerceAtMost(l.lastIndex), 0L, play = false) }
            },
            onDismiss = { showClips = false },
        )
    }

    val src = effInfo
    if (showExport && src != null) {
        ExportSheet(
            source = src,
            exporting = exporting,
            progress = progress,
            onDismiss = { if (!exporting) showExport = false },
            onStart = { startExport(it) },
            saving = saving,
            onCancel = { if (!saving) { cancelled = true; transformer?.cancel(); X264Encoder.cancel(x264Session); exporting = false } },
            isPro = isPro,
            exportMode = Entitlements.exportMode(context),
            freeLeft = Entitlements.freeLeft(context),
            monthlyLeft = Entitlements.monthlyLeft(context),
            onUpgrade = { showPro = true },
        )
    }

    if (showPro) {
        val debuggable = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        ProSheet(
            plan = Entitlements.plan(context),
            monthlyLeft = Entitlements.monthlyLeft(context),
            freeLeft = Entitlements.freeLeft(context),
            canTest = debuggable,
            onSetPlan = { Entitlements.setPlanForTesting(context, it); planTick++ },
            onDismiss = { showPro = false },
            prices = billing.prices,
            busy = billing.busy,
            onBuy = { id -> context.findActivity()?.let { billing.buy(it, id) } },
            onRedeem = { code ->
                if (!Server.enabled) "Los códigos funcionan en la versión publicada de la app."
                else try { Server.redeemCode(context, code); planTick++; null }
                catch (e: Server.ServerException) {
                    when {
                        e.network -> "Necesitas conexión a internet para canjear el código."
                        e.code.contains("permission", true) || e.message?.contains("incorrecto") == true -> "Código incorrecto."
                        e.code.contains("exhausted", true) -> "Demasiados intentos. Prueba de nuevo en una hora."
                        else -> "No se pudo canjear el código: ${e.message}"
                    }
                }
            },
        )
    }
}

/** Flecha grande a los lados del video para pasar al video anterior / siguiente. */
@Composable
private fun NavArrow(symbol: String, description: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color(0x44FFFFFF)
    Box(
        modifier.size(width = 40.dp, height = 64.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xB31A1C1E))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val w = size.width; val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val path = androidx.compose.ui.graphics.Path().apply {
                if (symbol == "‹") { moveTo(w * 0.64f, w * 0.14f); lineTo(w * 0.30f, w * 0.5f); lineTo(w * 0.64f, w * 0.86f) }
                else { moveTo(w * 0.36f, w * 0.14f); lineTo(w * 0.70f, w * 0.5f); lineTo(w * 0.36f, w * 0.86f) }
            }
            drawPath(path, tint, style = st)
        }
    }
}

/** Botón redondo de la barra superior sobre el video (galería, recortar). */
@Composable
private fun TopIcon(symbol: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color(0x88FFFFFF)
    // Fondo oscuro casi opaco con borde fino: se ve igual sobre un video claro, oscuro o sobre las franjas negras.
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color(0xE61A1C1E))
            .border(1.dp, Color(0x55FFFFFF), CircleShape)
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (symbol) {
            "‹" -> androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val w = size.width; val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.62f, w * 0.18f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.82f) }, tint, style = st)
            }
            "⤢", "⤡" -> androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                // ⤢ = cuatro esquinas hacia afuera (ampliar); ⤡ = hacia adentro (reducir).
                val w = size.width; val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                val out = symbol == "⤢"
                val a = if (out) 0.10f else 0.40f; val b = if (out) 0.40f else 0.10f
                fun corner(cx: Float, cy: Float, dx: Float, dy: Float) = androidx.compose.ui.graphics.Path().apply {
                    val x0 = w * cx; val y0 = w * cy
                    moveTo(x0 + dx * w * 0.28f, y0); lineTo(x0, y0); lineTo(x0, y0 + dy * w * 0.28f)
                }
                val m = if (out) 0.12f else 0.36f
                drawPath(corner(m, m, 1f, 1f), tint, style = st)
                drawPath(corner(1f - m, m, -1f, 1f), tint, style = st)
                drawPath(corner(m, 1f - m, 1f, -1f), tint, style = st)
                drawPath(corner(1f - m, 1f - m, -1f, -1f), tint, style = st)
                if (a < 0 || b < 0) return@Canvas
            }
            else -> Text(symbol, fontSize = 20.sp, color = tint)
        }
    }
}

/** Botón de herramienta como en Kuni: nombre arriba y círculo con el valor debajo. */
@Composable
private fun ToolButton(tool: String, label: String, valueText: String, modified: Boolean, onClick: () -> Unit) {
    val accent = Palette.accent
    Column(
        Modifier.width(74.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, color = Palette.textSoft)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(Palette.chip)
                .border(BorderStroke(1.5.dp, if (modified) accent else Palette.border), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            ToolIcon(tool, if (modified) accent else Palette.text, Modifier.size(22.dp))
        }
        // El valor solo se muestra cuando el ajuste está modificado.
        Text(if (modified) valueText else " ", fontSize = 10.sp, color = accent, maxLines = 1)
    }
}

private fun toolBadge(t: Tool, values: List<Float>, hsl: List<Float>, grade: List<Float>): String = when (t) {
    Tool.LUT -> "•"
    Tool.HSL -> if (hsl.any { it != 0f }) "•" else "H"
    Tool.GRADE -> if ((0 until 4).any { grade[it * 3 + 1] != 0f || grade[it * 3 + 2] != 0f }) "•" else "G"
    else -> (values[t.adjs[0].ordinal] * 100).roundToInt().toString()
}

private fun isAdjModified(a: Adj, v: Float) = v != a.default

/** Deslizador de -100..100 (o 0..100 si [min] = 0), con el valor a la derecha. */
@Composable
private fun AdjSlider(label: String, value: Float, min: Float, max: Float, showLabel: Boolean = true, onChange: (Float) -> Unit) {
    val v = (value * 100).roundToInt()
    val accent = Palette.accent
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (showLabel) label else "", fontSize = 13.sp, color = Palette.textSoft)
            Text(if (min < 0f && v > 0) "+$v" else v.toString(), fontSize = 13.sp, color = Palette.text)
        }
        Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            // Pista propia: el relleno parte del 0 (centro si el ajuste es de -100 a 100), como en Kuni.
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(32.dp)) {
                val pad = 10.dp.toPx()
                val w = size.width - 2 * pad
                val y = size.height / 2
                fun x(v: Float) = pad + (v - min) / (max - min) * w
                drawLine(Palette.border, androidx.compose.ui.geometry.Offset(pad, y), androidx.compose.ui.geometry.Offset(pad + w, y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                val zeroX = x(if (min < 0f) 0f else min)
                drawLine(accent, androidx.compose.ui.geometry.Offset(zeroX, y), androidx.compose.ui.geometry.Offset(x(value), y), 4.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Slider(
                value = value, onValueChange = onChange, valueRange = min..max, modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = accent, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                ),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FormulaCard(
    name: String, thumb: androidx.compose.ui.graphics.ImageBitmap?, selected: Boolean, deletable: Boolean,
    onLongClick: () -> Unit = {}, onClick: () -> Unit,
) {
    val accent = Palette.accent
    Box(
        Modifier.width(88.dp).height(88.dp).clip(RoundedCornerShape(10.dp))
            .background(Palette.chip)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            // Miniatura con el filtro aplicado y el nombre sobre una franja oscura abajo.
            androidx.compose.foundation.Image(thumb, contentDescription = name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Text(
                name, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, color = if (selected) accent else Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xB3000000)).padding(vertical = 3.dp, horizontal = 2.dp),
            )
        } else {
            Text(name, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, color = if (selected) accent else Palette.text, modifier = Modifier.padding(6.dp))
        }
        if (deletable) Text("★", fontSize = 10.sp, color = accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        // El borde va encima de la imagen para que se vea la selección.
        Box(Modifier.fillMaxSize().border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else Palette.border), RoundedCornerShape(10.dp)))
    }
}

// ---------- Exportar / comprimir ----------

private data class VideoInfo(
    val width: Int, val height: Int, val durationMs: Long, val bitrate: Int, val sizeBytes: Long, val fps: Float,
)

private data class ExportOptions(
    val shortSide: Int, val h265: Boolean, val bitrate: Int, val x264: Boolean = true, val preset: String = "fast",
    /** Perfil "TikTok 4K": H.264 con fps constantes, BT.709, GOP cerrado de 2 s y bitrate alto. */
    val tiktok: Boolean = false,
    /** Calidad constante x264 (0 = bitrate medio); con CRF, [bitrate] es el tope. */
    val crf: Int = 0,
) {
    fun outputSize(src: VideoInfo): Pair<Int, Int> {
        val srcShort = min(src.width, src.height)
        if (shortSide <= 0 || shortSide >= srcShort) return src.width to src.height
        val s = shortSide.toFloat() / srcShort
        return even(src.width * s) to even(src.height * s)
    }
    private fun even(v: Float) = max(2, (v / 2f).roundToInt() * 2)
}

private fun readVideoInfo(context: Context, uri: Uri): VideoInfo? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(context, uri)
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toFloatOrNull() ?: 0f
        val fps = if (frames > 0f && dur > 0) frames * 1000f / dur else 30f
        VideoInfo(w, h, dur, if (br > 0) br else w * h * 4, size, fps.coerceIn(10f, 120f))
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

/** Bitrate estimado según resolución, calidad (20..100) y códec. */
private fun computeBitrate(src: VideoInfo, shortSide: Int, quality: Float, h265: Boolean): Int {
    val srcShort = min(src.width, src.height)
    val scale = if (shortSide <= 0 || shortSide >= srcShort) 1f else shortSide.toFloat() / srcShort
    val q = (quality / 100f).pow(1.5f)
    val codec = if (h265) 0.65f else 1f
    // 100% = lo bastante alto para que el detalle realzado no se pixele al recodificar
    // (el original ya viene comprimido): 1.15x el bitrate de origen (el codificador de hardware
    // no usa fotogramas B, así que necesita algo más) y al menos 0.07 bits/píxel.
    val base = max(src.bitrate * 1.15f, src.width.toFloat() * src.height * src.fps * 0.07f)
    return (base * scale * scale * q * codec).coerceIn(300_000f, 80_000_000f).toInt()
}

/**
 * Bitrate del motor x264, medido en Smart Video Compressor: proporcional a la calidad (20..100 %) y al área
 * de salida elevada a 0.75, sobre el bitrate del origen (1080x1920 desde 1216x2160 a 14 Mbps -> 11.7 Mbps).
 */
private fun computeX264Bitrate(src: VideoInfo, outW: Int, outH: Int, quality: Float, h265: Boolean): Int {
    val areaRatio = (outW.toFloat() * outH) / (src.width.toFloat() * src.height)
    val codec = if (h265) 0.65f else 1f
    val b = src.bitrate * areaRatio.coerceAtMost(1f).pow(0.75f) * (quality / 100f) * codec
    return b.coerceIn(300_000f, 80_000_000f).toInt()
}

private fun saveToGallery(context: Context, file: File): Boolean {
    val name = "Gradelook_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
    val cv = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Gradelook")
    }
    val dest = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
    return try {
        context.contentResolver.openOutputStream(dest)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        true
    } catch (e: Exception) {
        false
    }
}

@Composable
private fun ExportSheet(
    source: VideoInfo,
    exporting: Boolean,
    progress: Float,
    onDismiss: () -> Unit,
    onStart: (ExportOptions) -> Unit,
    onCancel: () -> Unit,
    saving: Boolean,
    isPro: Boolean,
    exportMode: Entitlements.Mode,
    freeLeft: Int,
    monthlyLeft: Int,
    onUpgrade: () -> Unit,
) {
    val info = source
    val srcShort = min(info.width, info.height)
    val sizes = remember(info) { listOf(0, 1080, 720, 480, 360).filter { it == 0 || it < srcShort } }
    var shortSide by remember { mutableStateOf(0) }
    // Plan gratis: la salida nunca pasa de 1080p aunque se elija "Original" o un valor mayor.
    val freeCap = !isPro && srcShort > Entitlements.FREE_MAX_SHORT_SIDE
    val effShort = if (freeCap && (shortSide == 0 || shortSide > Entitlements.FREE_MAX_SHORT_SIDE)) Entitlements.FREE_MAX_SHORT_SIDE else shortSide
    var h265 by remember { mutableStateOf(false) }
    var quality by remember { mutableFloatStateOf(100f) }
    // Tope de bits por píxel (solo con los perfiles rápidos); null = sin tope.
    var capBpp by remember { mutableStateOf<Float?>(null) }

    var x264 by remember { mutableStateOf(true) }
    // Velocidad de x264 (medida: fast -20 % de tiempo / -0.09 dB, faster -35 % / -0.2 dB, medium = referencia).
    var preset by remember { mutableStateOf("fast") }

    // Perfil TikTok 4K: bitrate fijo y alto (0.17 bits/píxel/fotograma: ~42 Mb/s en 4K a 30 fps, tope 70 Mb/s) para que la
    // recompresión de la red parta de un video muy limpio. Cualquier cambio manual de códec, motor o calidad lo desactiva.
    var tiktok by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf("Máxima calidad") }

    val (ow, oh) = ExportOptions(effShort, h265, 0).outputSize(info)
    val bitrate = if (tiktok) (ow.toFloat() * oh * min(info.fps, 60f) * 0.17f).toInt().coerceIn(8_000_000, 70_000_000)
    else (if (x264) computeX264Bitrate(info, ow, oh, quality, h265) else computeBitrate(info, effShort, quality, h265)).let { b ->
        // Medido contra Kuni (exporta a ~77 Mb/s en 1080p): a 12 Mb/s se pierde ~26 % del detalle fino que crea Textura,
        // a 40 Mb/s ~10 % y a 77 Mb/s ~4 %. Por eso "Redes sociales" y "Máxima calidad" parten de un bitrate alto
        // por píxel, aunque el video original venga muy comprimido. WhatsApp sigue ligero a propósito.
        val px = ow.toFloat() * oh * min(info.fps, 60f)
        when {
            !x264 -> capBpp?.let { min(b, (px * it).toInt()) } ?: b
            profile == "Redes sociales" -> (px * 0.75f).toInt().coerceIn(12_000_000, 60_000_000)
            profile == "Máxima calidad" -> max(b, (px * 0.2f).toInt()).coerceAtMost(80_000_000)
            else -> capBpp?.let { min(b, (px * it).toInt()) } ?: b
        }
    }
    // Calidad constante con tope de bitrate en "Redes sociales" y "Máxima calidad" (H.264 con x264); el `bitrate` de arriba
    // pasa a ser solo la estimación del tamaño que se muestra.
    val crf = if (x264 && !h265 && !tiktok) when (profile) { "Redes sociales" -> 22; "Máxima calidad" -> 20; else -> 0 } else 0
    val ceiling = if (crf > 0) (if (profile == "Redes sociales") 60_000_000 else 80_000_000) else bitrate
    val opts = ExportOptions(effShort, h265, ceiling, x264, preset, tiktok, crf)
    val estBytes = ((bitrate + 128_000L) * (info.durationMs / 1000.0) / 8).toLong()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Exportar / comprimir", style = MaterialTheme.typography.titleMedium)

            // Cuánto costará esta exportación: gratis, 1 crédito, o marca de agua si no quedan créditos.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (exportMode) {
                        Entitlements.Mode.FREE -> "Gratis: te quedan $freeLeft de ${Entitlements.FREE_EXPORTS} exportaciones (en total en este móvil)"
                        Entitlements.Mode.MONTHLY -> "Plan Pro: te quedan $monthlyLeft de ${Entitlements.STANDARD_MONTHLY_EXPORTS} exportaciones este mes"
                        Entitlements.Mode.UNLIMITED -> "Plan ilimitado: exportaciones sin límite"
                        Entitlements.Mode.BLOCKED -> "Ya no te quedan exportaciones. Elige un plan para seguir exportando."
                    },
                    fontSize = 12.sp,
                    color = if (exportMode == Entitlements.Mode.BLOCKED) Palette.accent else Palette.textSoft,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUpgrade) { Text("Ver planes", fontSize = 12.sp, color = Palette.accent) }
            }
            if (freeCap) Text("Sin Pro: máximo ${Entitlements.FREE_MAX_SHORT_SIDE}p. 4K requiere Pro.", fontSize = 12.sp, color = Palette.textSoft)

            // Hoja simple: solo se elige para qué se va a usar el video. Los detalles técnicos (motor, códec, bitrate...)
            // no se muestran: los fija cada perfil.
            Text("¿Para qué lo vas a usar?", fontSize = 13.sp, color = Palette.accent)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("TikTok 4K", "Redes sociales", "Máxima calidad", "WhatsApp")) { p ->
                    FilterChip(
                        selected = profile == p, enabled = !exporting,
                        onClick = {
                            profile = p
                            x264 = true; h265 = false
                            when (p) {
                                "TikTok 4K" -> { shortSide = 0; quality = 100f; capBpp = null; tiktok = true }
                                "Redes sociales" -> { tiktok = false; shortSide = if (srcShort > 1080) 1080 else 0; quality = 100f; capBpp = 0.12f }
                                "Máxima calidad" -> { tiktok = false; shortSide = 0; quality = 100f; capBpp = null }
                                else -> { tiktok = false; shortSide = if (srcShort > 720) 720 else 0; quality = 70f; capBpp = 0.08f }
                            }
                        },
                        label = { Text(p, maxLines = 1) },
                    )
                }
            }
            Text(
                when (profile) {
                    "TikTok 4K" -> "Pensado para subir a TikTok sin perder calidad."
                    "Redes sociales" -> "Instagram, YouTube y más, hasta 1080p."
                    "Máxima calidad" -> "Conserva el tamaño original del video."
                    else -> "Archivo ligero para enviar por chat."
                },
                fontSize = 12.sp, color = Palette.textSoft,
            )
            Text("Resolución: ${ow}x$oh", fontSize = 13.sp)
            Text(
                "Tamaño aproximado: ${formatMb(estBytes)}" + if (info.sizeBytes > 0) "  (original ${formatMb(info.sizeBytes)})" else "",
                fontSize = 13.sp,
            )

            if (exporting) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                // Al terminar de codificar el video se copia a la galería: en ese tramo ya no se puede cancelar.
                OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (saving) "Guardando en la galería…" else "Cancelar (${(progress * 100).toInt()}%)")
                }
            } else {
                if (exportMode == Entitlements.Mode.BLOCKED) {
                    Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) { Text("Ver planes para exportar") }
                } else Button(onClick = { onStart(opts) }, modifier = Modifier.fillMaxWidth()) { Text("Exportar") }
            }
        }
    }
}

private fun formatMb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
