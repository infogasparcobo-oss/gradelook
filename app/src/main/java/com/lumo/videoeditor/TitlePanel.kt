package com.lumo.videoeditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmt(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    return "%d:%02d.%d".format(t / 60000, (t / 1000) % 60, (t % 1000) / 100)
}

private fun familyOf(f: WatermarkLook.Font): FontFamily = when (f.family) {
    "serif" -> FontFamily.Serif; "monospace" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

private fun weightOf(f: WatermarkLook.Font): FontWeight = when (f.family) {
    "sans-serif-black" -> FontWeight.Black
    "sans-serif-thin" -> FontWeight.Thin
    "sans-serif-light" -> FontWeight.Light
    "sans-serif-medium" -> FontWeight.Medium
    else -> if (f.style == android.graphics.Typeface.BOLD || f.style == android.graphics.Typeface.BOLD_ITALIC) FontWeight.Bold else FontWeight.Normal
}

/**
 * Títulos sobre el video mientras se edita: se ven siempre (aunque el video esté en pausa), se arrastran con un dedo y se
 * agrandan o achican pellizcando. Con el video en marcha aparecen y desaparecen en su tiempo y con sus efectos (los mismos
 * que se exportan); fuera de su tiempo el título elegido se ve atenuado para poder seguir colocándolo.
 */
@Composable
fun TitleEditLayer(
    titles: List<TitleItem>, selectedId: Long?, now: LongState, aspect: Float,
    onSelect: (Long) -> Unit, onChange: (List<TitleItem>) -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val latest by rememberUpdatedState(titles)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
        // El video se ve ajustado al recuadro (con franjas negras): ese es el cuadro real donde caen los títulos.
        val dw = if (aspect > cw / ch) cw else ch * aspect
        val dh = if (aspect > cw / ch) cw / aspect else ch
        Box(Modifier.size(with(density) { dw.toDp() }, with(density) { dh.toDp() })) {
            titles.forEach { t ->
                val bmp = remember(t.text, t.font, t.color, t.bg, t.outline, t.caps) { Titles.render(t) } ?: return@forEach
                val box = Titles.place(t, bmp.width, bmp.height, dw, dh)
                val wPx = bmp.width * box.scale; val hPx = bmp.height * box.scale
                val selected = t.id == selectedId
                Image(
                    bitmap = bmp.asImageBitmap(), contentDescription = t.text,
                    modifier = Modifier
                        .offset { IntOffset((box.cx * dw - wPx / 2f).roundToInt(), (box.cy * dh - hPx / 2f).roundToInt()) }
                        .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                        // Se lee el reloj aquí (al dibujar), así el efecto es fluido sin recomponer toda la pantalla.
                        .graphicsLayer {
                            val an = Titles.anim(t, now.longValue)
                            if (an != null) {
                                // El título elegido nunca desaparece del todo al editar (p. ej. en pausa justo al empezar el fundido).
                                alpha = if (selected) maxOf(an.alpha, 0.35f) else an.alpha
                                scaleX = an.scale; scaleY = an.scale
                                translationX = an.dx * dw; translationY = an.dy * dh
                            } else alpha = if (selected) 0.3f else 0f
                        }
                        .then(if (selected) Modifier.border(BorderStroke(1.5.dp, Palette.accent), RoundedCornerShape(6.dp)) else Modifier)
                        .pointerInput(t.id) {
                            detectTapGestures(onTap = { if (selected || Titles.anim(t, now.longValue) != null) onSelect(t.id) })
                        }
                        .pointerInput(t.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (!selected && Titles.anim(t, now.longValue) == null) return@detectTransformGestures
                                onSelect(t.id)
                                onChange(latest.map {
                                    if (it.id != t.id) it else it.copy(
                                        posX = (it.posX + pan.x / (0.9f * dw)).coerceIn(0f, 1f),
                                        posY = (it.posY + pan.y / (0.9f * dh)).coerceIn(0f, 1f),
                                        size = (it.size * zoom).coerceIn(0.03f, 0.22f),
                                    )
                                })
                            }
                        },
                )
            }
        }
    }
}

/**
 * Línea de tiempo de los títulos: cada título es una barra sobre la duración del video. Se arrastra el borde izquierdo
 * o derecho para cambiar cuándo empieza o termina, el centro para moverlo entero, y se toca en vacío para ir a ese punto.
 * Al arrastrar un borde, el video salta a ese instante para ver exactamente dónde cae.
 */
@Composable
private fun TitleTimeline(
    titles: List<TitleItem>, selectedId: Long?, total: Long, now: LongState,
    onSelect: (Long) -> Unit, onChange: (List<TitleItem>) -> Unit, onSeek: (Long) -> Unit,
) {
    val tl by rememberUpdatedState(titles)
    val sid by rememberUpdatedState(selectedId)
    val tot by rememberUpdatedState(total)
    val accent = Palette.accent
    fun edit(id: Long, f: (TitleItem) -> TitleItem) = onChange(tl.map { if (it.id == id) f(it) else it })
    Canvas(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF26282C))
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val w = size.width.toFloat(); val t = tot.coerceAtLeast(1L)
                    val hit = tl.lastOrNull { o.x in (it.startMs.toFloat() / t * w)..(it.endMs.toFloat() / t * w) }
                    if (hit != null) onSelect(hit.id) else onSeek((o.x / w * t).toLong())
                }
            }
            .pointerInput(Unit) {
                var mode = 0   // 1 = borde izquierdo, 2 = borde derecho, 3 = mover, 4 = ir a ese punto
                var id = 0L
                var acc = 0f
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat(); val t = tot.coerceAtLeast(1L)
                        fun x(ms: Long) = ms.toFloat() / t * w
                        val grab = 28.dp.toPx()
                        mode = 4; id = 0L; acc = 0f
                        val sel = tl.firstOrNull { it.id == sid }
                        if (sel != null) {
                            val x0 = x(sel.startMs); val x1 = x(sel.endMs)
                            if (abs(o.x - x0) <= grab) { mode = 1; id = sel.id }
                            else if (abs(o.x - x1) <= grab) { mode = 2; id = sel.id }
                            else if (o.x in x0..x1) { mode = 3; id = sel.id }
                        }
                        if (mode == 4) {
                            val other = tl.lastOrNull { o.x in x(it.startMs)..x(it.endMs) }
                            if (other != null) { onSelect(other.id); mode = 3; id = other.id }
                        }
                        if (mode == 4) onSeek((o.x / w * t).toLong())
                    },
                    onDrag = { change, amt ->
                        change.consume()
                        val w = size.width.toFloat(); val t = tot.coerceAtLeast(1L)
                        if (mode == 4) { onSeek((change.position.x / w * t).toLong().coerceIn(0L, t)); return@detectDragGestures }
                        acc += amt.x / w * t
                        val step = acc.toLong(); acc -= step
                        if (step == 0L) return@detectDragGestures
                        when (mode) {
                            1 -> edit(id) { it.copy(startMs = (it.startMs + step).coerceIn(0L, it.endMs - 300L)) }
                            2 -> edit(id) { it.copy(endMs = (it.endMs + step).coerceIn(it.startMs + 300L, maxOf(t, it.startMs + 300L))) }
                            3 -> edit(id) {
                                val d = it.endMs - it.startMs
                                val s = (it.startMs + step).coerceIn(0L, maxOf(0L, t - d))
                                it.copy(startMs = s, endMs = s + d)
                            }
                        }
                        // El video salta al borde que se mueve (o al inicio si se mueve la barra entera).
                        tl.firstOrNull { it.id == id }?.let { cur -> onSeek(if (mode == 2) cur.endMs else cur.startMs) }
                    },
                )
            },
    ) {
        val w = size.width; val t = tot.coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = ms / t * w
        titles.forEach { it0 ->
            val sel = it0.id == sid
            val x0 = x(it0.startMs); val x1 = maxOf(x(it0.endMs), x0 + 6f)
            drawRoundRect(
                if (sel) accent else Color(0xFF6A6E76), Offset(x0, if (sel) 6f else 14f), Size(x1 - x0, size.height - if (sel) 12f else 28f),
                CornerRadius(8f), alpha = if (sel) 0.9f else 0.55f,
            )
            if (sel) {
                // Agarraderas de los dos bordes.
                for (hx in floatArrayOf(x0 + 5f, x1 - 5f)) drawRoundRect(Color(0xFF1A1C1E), Offset(hx - 2.5f, 15f), Size(5f, size.height - 30f), CornerRadius(3f))
            }
        }
        // Marca de reproducción.
        val px = x(now.longValue.coerceIn(0L, tot))
        drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height))
    }
}

/**
 * Panel de títulos. Como el de la marca de agua, va en la zona de herramientas y no tapa el video. Arriba, los títulos como
 * fichas; abajo, tres pestañas del título elegido: Texto (escribir y tamaño), Estilo (letra, color, fondo) y Tiempo
 * (línea de tiempo y efectos de aparición y desaparición).
 */
@Composable
fun TitlePanel(
    titles: List<TitleItem>, selectedId: Long?, onSelect: (Long?) -> Unit,
    onChange: (List<TitleItem>) -> Unit, onAdd: () -> Unit,
    now: LongState, totalMs: () -> Long, onSeek: (Long) -> Unit, onPreview: (Long) -> Unit, onClose: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val sel = titles.firstOrNull { it.id == selectedId }
    fun edit(f: (TitleItem) -> TitleItem) { if (sel != null) onChange(titles.map { if (it.id == sel.id) f(it) else it }) }

    // Altura fija (con desplazamiento dentro): el video no cambia de tamaño al pasar de una pestaña a otra y se ve más grande.
    Column(
        Modifier.fillMaxWidth().height(264.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f)) {
                ChipRow {
                    titles.forEachIndexed { i, t ->
                        Chip("${i + 1}. ${t.text.replace("\n", " ").take(10).ifBlank { "…" }}", t.id == selectedId) { onSelect(t.id) }
                    }
                    if (titles.size < TitleLook.MAX) Chip("＋ Nuevo", false, onAdd)
                }
            }
            TextButton(onClick = onClose) { Text("✓", fontSize = 24.sp, color = Palette.accent) }
        }

        if (sel == null) {
            Text(
                if (titles.isEmpty()) "Toca «＋ Nuevo» para añadir un título en el punto donde estás del video."
                else "Elige un título arriba para editarlo.",
                fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.padding(vertical = 18.dp),
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            listOf("Texto", "Estilo", "Tiempo").forEachIndexed { i, n ->
                TextButton(onClick = { tab = i }) {
                    Text(n, fontSize = 13.sp, color = if (tab == i) Palette.accent else Palette.textSoft,
                        fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        when (tab) {
            0 -> {
                OutlinedTextField(
                    value = sel.text, onValueChange = { v -> edit { it.copy(text = v.take(80)) } },
                    maxLines = 3, label = { Text("Escribe tu título") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tamaño", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(60.dp))
                    Slider(value = sel.size, onValueChange = { v -> edit { it.copy(size = v) } }, valueRange = 0.03f..0.22f, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Arrastra el título sobre el video para moverlo; pellizca para cambiar el tamaño.",
                        fontSize = 11.sp, color = Palette.textSoft, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        onChange(titles.filter { it.id != sel.id }); onSelect(titles.firstOrNull { it.id != sel.id }?.id)
                    }) { Text("Eliminar", fontSize = 12.sp) }
                }
            }
            1 -> {
                // Estilos listos (un toque): cada ficha se ve ya con su letra y color.
                ChipRow {
                    TitleLook.styles.forEach { s ->
                        val f = TitleLook.fonts[s.font]
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (s.bg != 0) Color(TitleLook.backgrounds[s.bg].argb) else Color(0xFF2A2C30))
                                .border(BorderStroke(1.dp, Palette.border), RoundedCornerShape(10.dp))
                                .clickable { edit { it.copy(font = s.font, color = s.color, bg = s.bg, outline = s.outline, caps = s.caps, size = s.size) } }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                if (s.caps) s.name.uppercase() else s.name, fontSize = 14.sp, fontFamily = familyOf(f), fontWeight = weightOf(f),
                                color = Color(TitleLook.colors[s.color].argb),
                            )
                        }
                    }
                }
                ChipRow {
                    TitleLook.fonts.forEachIndexed { i, f ->
                        FilterChip(selected = sel.font == i, onClick = { edit { it.copy(font = i) } }, label = {
                            Text(f.label, fontFamily = familyOf(f), fontSize = 13.sp, fontWeight = weightOf(f))
                        })
                    }
                }
                // Colores como círculos: se elige a ojo, sin leer nombres.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TitleLook.colors.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c.argb))
                                .border(BorderStroke(if (sel.color == i) 3.dp else 1.dp, if (sel.color == i) Palette.accent else Palette.border), CircleShape)
                                .clickable { edit { it.copy(color = i) } },
                        )
                    }
                }
                ChipRow {
                    TitleLook.backgrounds.forEachIndexed { i, b -> Chip(b.label, sel.bg == i) { edit { it.copy(bg = i) } } }
                    Chip("Contorno", sel.outline) { edit { it.copy(outline = !it.outline) } }
                    Chip("MAYÚSCULAS", sel.caps) { edit { it.copy(caps = !it.caps) } }
                }
            }
            else -> {
                val total = totalMs()
                fun setStart(ms: Long) = edit { it.copy(startMs = ms.coerceIn(0L, (it.endMs - 300L).coerceAtLeast(0L))) }
                fun setEnd(ms: Long) = edit { it.copy(endMs = ms.coerceIn(it.startMs + 300L, if (total > 0) maxOf(total, it.startMs + 300L) else Long.MAX_VALUE)) }
                TitleTimeline(titles, sel.id, total, now, onSelect = { onSelect(it) }, onChange = onChange, onSeek = onSeek)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Empieza ${fmt(sel.startMs)}", fontSize = 12.sp, color = Palette.text)
                    Text("Dura ${"%.1f".format((sel.endMs - sel.startMs) / 1000f)} s", fontSize = 12.sp, color = Palette.textSoft)
                    Text("Termina ${fmt(sel.endMs)}", fontSize = 12.sp, color = Palette.text)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pad = PaddingValues(horizontal = 10.dp)
                    OutlinedButton(onClick = { setStart(now.longValue) }, contentPadding = pad) { Text("Empieza aquí", fontSize = 12.sp) }
                    OutlinedButton(onClick = { setEnd(now.longValue) }, contentPadding = pad) { Text("Termina aquí", fontSize = 12.sp) }
                    OutlinedButton(onClick = { onPreview(sel.startMs) }, contentPadding = pad) { Text("▶ Ver", fontSize = 12.sp, color = Palette.accent) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Entra", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(44.dp))
                    ChipRow { TitleLook.anims.forEachIndexed { i, n -> Chip(n, sel.animIn == i) { edit { it.copy(animIn = i) } } } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Sale", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(44.dp))
                    ChipRow { TitleLook.anims.forEachIndexed { i, n -> Chip(n, sel.animOut == i) { edit { it.copy(animOut = i) } } } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Efecto", fontSize = 12.sp, color = Palette.textSoft, modifier = Modifier.width(44.dp))
                    ChipRow { TitleLook.animSpeeds.forEach { (n, ms) -> Chip(n, sel.animMs == ms) { edit { it.copy(animMs = ms) } } } }
                }
            }
        }
    }
}
