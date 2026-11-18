package com.lumo.videoeditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un filtro = una combinación de ajustes. Los filtros incluidos en la app usan SOLO Textura, Claridad, Brillo y HSL
 * (nada más); los que guarda el usuario pueden usar cualquier ajuste.
 */
data class Formula(
    val name: String,
    val values: FloatArray,
    val hsl: FloatArray,
    val grade: FloatArray,
    val builtIn: Boolean = false,
    val category: String = Formulas.MINE,
    /** Disponible sin plan de pago (los demás filtros incluidos requieren Pro). */
    val free: Boolean = false,
) {
    fun toSnapshot() = AdjustmentSnapshot(values.copyOf(), hsl.copyOf(), grade.copyOf())
}

object Formulas {
    private const val PREFS = "lumo_formulas"
    private const val KEY = "list"

    const val MINE = "Míos"
    val categories = listOf("Paisajes", "Atardeceres", "Bodas", "Cumpleaños", "Deportivos", "Drones", "Cine")

    // Bandas HSL: 0 Rojo, 1 Naranja, 2 Amarillo, 3 Verde, 4 Aqua, 5 Azul, 6 Morado, 7 Magenta.
    private const val R = 0; private const val O = 1; private const val Y = 2; private const val G = 3
    private const val A = 4; private const val Bl = 5; private const val P = 6; private const val M = 7

    /**
     * Constructor con la misma escala que ve el usuario (-100..100). Solo expone los cuatro controles permitidos:
     * t = Textura, c = Claridad, b = Brillo y hsl(banda, tono, saturación, luminancia).
     * Tono: en Rojo + va hacia naranja; Naranja + hacia amarillo; Amarillo + hacia verde; Verde + hacia aqua;
     * Aqua + hacia azul; Azul + hacia morado (– hacia aqua).
     */
    private class B(val name: String, val category: String) {
        val v = defaultValues()
        val h = FloatArray(hslBands.size * 3)
        val g = defaultGrade()
        var free = false
        fun t(percent: Int) = apply { v[Adj.TEXTURE.ordinal] = percent / 100f }
        fun c(percent: Int) = apply { v[Adj.CLARITY.ordinal] = percent / 100f }
        fun b(percent: Int) = apply { v[Adj.BRIGHTNESS.ordinal] = percent / 100f }
        /** Nitidez: solo la usan los filtros que la piden de forma expresa (p. ej. "Paisaje Nítido"). */
        fun s(percent: Int) = apply { v[Adj.SHARPEN.ordinal] = percent / 100f }
        fun hsl(band: Int, hue: Int, sat: Int, lum: Int) = apply {
            h[band * 3] = hue / 100f; h[band * 3 + 1] = sat / 100f; h[band * 3 + 2] = lum / 100f
        }
        /** Saturación (y luminancia opcional) igual en las 8 bandas, para filtros globales (pastel, blanco y negro...). */
        fun allSat(sat: Int, lum: Int = 0) = apply { for (i in 0 until 8) { h[i * 3 + 1] = sat / 100f; h[i * 3 + 2] = lum / 100f } }
        fun free() = apply { free = true }
        fun build() = Formula(name, v, h, g, builtIn = true, category = category, free = free)
    }

    private fun f(name: String, category: String) = B(name, category)

    val presets: List<Formula> = listOf(
        // ---------- Paisajes ----------
        f("Fresco", "Paisajes").t(70).c(-30).b(-10).hsl(O, 0, 30, -5).hsl(R, 0, 20, 0).hsl(Y, 0, 20, 0).hsl(Bl, 0, 10, -20).hsl(G, 5, 20, -5).free().build(),
        f("Fresco Plus", "Paisajes").t(80).c(-35).b(-14).hsl(O, -5, 45, -8).hsl(R, -5, 35, 0).hsl(Y, -5, 30, 0).hsl(Bl, 0, 15, -30).hsl(A, 0, 0, -20).hsl(G, 8, 25, -12).build(),
        // Sobre la base de Fresco Plus: verdes y azules más profundos y limpios, naranjas cálidos y Nitidez para el detalle.
        f("Paisaje Nítido", "Paisajes").t(75).c(-35).b(-12).s(35)
            .hsl(G, 6, 32, -14).hsl(Y, -4, 25, 0).hsl(O, -5, 40, -8).hsl(R, -5, 30, 0)
            .hsl(A, 0, 20, -18).hsl(Bl, 0, 25, -28).build(),
        // Dos verdes distintos: Esmeralda (frío y profundo, tira a turquesa) y Primavera (luminoso y amarillento).
        f("Verde Esmeralda", "Paisajes").t(70).c(-32).b(-14)
            .hsl(G, 18, 30, -12).hsl(Y, 14, 15, -6).hsl(A, 4, 22, -16).hsl(Bl, 0, 15, -26).hsl(O, 0, 10, -5).build(),
        f("Verde Primavera", "Paisajes").t(65).c(-28).b(2)
            .hsl(G, -10, 28, 8).hsl(Y, 8, 28, 6).hsl(O, 0, 15, 3).hsl(A, 0, 10, -4).hsl(Bl, 0, 12, -14).build(),
        f("Verde Vivo", "Paisajes").t(70).c(-30).b(-8).hsl(G, 0, 35, -10).hsl(Y, 0, 20, 0).hsl(Bl, 0, 15, -15).hsl(A, 0, 10, 0).free().build(),
        f("Bosque Profundo", "Paisajes").t(80).c(-35).b(-18).hsl(G, -5, 10, -25).hsl(A, 0, 0, -20).hsl(Y, 0, -10, -10).build(),
        f("Montaña Clara", "Paisajes").t(60).c(-20).b(4).hsl(Bl, 0, 20, -15).hsl(A, 0, 15, 0).hsl(G, 0, 15, -5).build(),
        f("Otoño", "Paisajes").t(70).c(-30).b(-8).hsl(O, 0, 40, 0).hsl(Y, -10, 30, 0).hsl(R, 0, 25, 0).hsl(G, -30, -10, -5).build(),
        f("Campo Dorado", "Paisajes").t(60).c(-30).b(-4).hsl(Y, 0, 30, 5).hsl(G, -20, 15, 0).hsl(O, 0, 20, 5).build(),

        // ---------- Atardeceres ----------
        f("Atardecer", "Atardeceres").t(80).c(-35).b(-14).hsl(O, -5, 55, -5).hsl(R, -5, 45, 0).hsl(Y, -5, 35, 0).hsl(M, 0, 25, 0)
            .hsl(P, 0, 20, -10).hsl(Bl, 0, 10, -30).hsl(G, 8, 10, -20).free().build(),
        f("Hora Dorada", "Atardeceres").t(65).c(-30).b(-6).hsl(Y, -10, 40, 8).hsl(O, 0, 35, 6).hsl(R, 0, 20, 0).hsl(G, -25, 10, 0).hsl(Bl, 0, -10, -10).free().build(),
        f("Atardecer Intenso", "Atardeceres").t(90).c(-40).b(-22).hsl(O, -8, 75, -8).hsl(R, -8, 60, -3).hsl(Y, -8, 45, 0).hsl(M, 0, 40, 0)
            .hsl(P, 0, 30, -15).hsl(Bl, 0, 15, -40).hsl(G, 10, 0, -30).hsl(A, 0, 0, -25).build(),
        f("Rosa y Naranja", "Atardeceres").t(70).c(-35).b(-10).hsl(M, 0, 50, 0).hsl(R, 0, 40, 0).hsl(O, 0, 45, -5).hsl(P, 0, 30, 0).hsl(Bl, 0, 0, -25).build(),
        f("Cielo de Fuego", "Atardeceres").t(85).c(-40).b(-20).hsl(R, 0, 65, -5).hsl(O, -10, 70, -8).hsl(Y, -5, 40, 0).hsl(M, 0, 40, 0)
            .hsl(Bl, 0, 20, -40).hsl(G, 0, -10, -25).build(),

        // ---------- Bodas ----------
        f("Boda Suave", "Bodas").t(40).c(-45).b(8).hsl(O, 0, -5, 8).hsl(R, 0, -5, 5).hsl(Y, 0, -15, 5).hsl(G, 0, -25, 5).hsl(A, 0, -20, 5).hsl(Bl, 0, -15, 5).free().build(),
        f("Boda Clásica", "Bodas").t(50).c(-40).b(4).hsl(Y, 0, -20, 4).hsl(G, 0, -30, 6).hsl(O, 0, 0, 6).hsl(A, 0, -20, 0).free().build(),
        f("Boda Dorada", "Bodas").t(45).c(-45).b(6).hsl(O, 0, 10, 6).hsl(Y, -5, 10, 4).hsl(G, -30, -20, 4).hsl(Bl, 0, -20, 0).hsl(R, 0, 0, 4).build(),
        f("Romance", "Bodas").t(35).c(-50).b(8).hsl(M, 0, 15, 5).hsl(R, 0, 10, 5).hsl(O, 0, 0, 7).hsl(G, 0, -30, 5).hsl(P, 0, 10, 0).build(),
        f("Boda en Jardín", "Bodas").t(50).c(-35).b(4).hsl(G, 0, 10, 4).hsl(Y, 0, -5, 4).hsl(O, 0, 0, 6).hsl(M, 0, 10, 4).hsl(Bl, 0, -5, 0).build(),

        // ---------- Cumpleaños ----------
        f("Fiesta", "Cumpleaños").t(70).c(-20).b(6).hsl(R, 0, 35, 0).hsl(O, 0, 30, 0).hsl(Y, 0, 35, 0).hsl(G, 0, 25, 0).hsl(A, 0, 25, 0)
            .hsl(Bl, 0, 30, 0).hsl(P, 0, 35, 0).hsl(M, 0, 35, 0).free().build(),
        f("Pastel", "Cumpleaños").t(30).c(-45).b(10).allSat(-15, 8).free().build(),
        f("Globos", "Cumpleaños").t(60).c(-25).b(6).hsl(M, 0, 40, 4).hsl(P, 0, 35, 2).hsl(Bl, 0, 25, 0).hsl(R, 0, 30, 0).hsl(Y, 0, 25, 3).build(),
        f("Cálido Familiar", "Cumpleaños").t(50).c(-30).b(4).hsl(O, 0, 10, 8).hsl(R, 0, 5, 4).hsl(Y, 0, 15, 0).hsl(G, 0, -10, 0).build(),
        f("Confeti", "Cumpleaños").t(80).c(-15).b(4).hsl(R, 0, 45, 0).hsl(Y, 0, 45, 0).hsl(G, 0, 40, 0).hsl(A, 0, 40, 0).hsl(Bl, 0, 45, 0).hsl(M, 0, 45, 0).build(),

        // ---------- Deportivos ----------
        f("Acción", "Deportivos").t(100).c(10).b(-5).hsl(G, 0, 30, -5).hsl(Bl, 0, 25, -15).hsl(Y, 0, 20, 0).hsl(R, 0, 20, 0).free().build(),
        f("Estadio", "Deportivos").t(90).c(5).b(-3).hsl(G, 0, 35, -10).hsl(R, 0, 30, 0).hsl(Bl, 0, 30, -10).hsl(Y, 0, 20, 0).free().build(),
        f("Extremo", "Deportivos").t(100).c(20).b(-10).hsl(O, 0, 30, 0).hsl(Bl, 0, 30, -25).hsl(G, 0, 15, -15).hsl(A, 0, 25, -5).build(),
        f("Noche Deportiva", "Deportivos").t(80).c(10).b(-15).hsl(Bl, 0, 20, -20).hsl(A, 0, 20, -15).hsl(R, 0, 25, 0).hsl(M, 0, 20, 0).build(),
        f("Motor", "Deportivos").t(90).c(15).b(-8).hsl(R, 0, 35, -5).hsl(O, 0, 25, 0).hsl(Y, 0, 25, 0).hsl(Bl, 0, 15, -15).hsl(G, 0, -10, -10).build(),

        // ---------- Drones ----------
        // DJI 4K: la base de tus fórmulas For1/For2 (Textura 100, Claridad -45) probada con un clip 4K del DJI Mini 4 Pro.
        f("DJI 4K", "Drones").t(100).c(-45).free().build(),
        f("Nubes", "Drones").t(55).c(25).b(-5).hsl(Bl, 0, 20, -20).hsl(A, 0, 15, -10).hsl(G, 0, 20, -8).free().build(),
        f("Nubes Intenso", "Drones").t(80).c(40).b(-8).hsl(Bl, 0, 30, -30).hsl(A, 0, 20, -15).hsl(G, 0, 30, -12).build(),
        f("Dron Cinematográfico", "Drones").t(90).c(-35).b(-18).hsl(Bl, 0, -10, -25).hsl(G, -8, -15, -15).hsl(A, 0, -10, -15).hsl(O, 0, 20, 0).build(),
        f("Dron Vivo", "Drones").t(100).c(-45).b(6).hsl(G, 0, 35, -8).hsl(Bl, 0, 30, -15).hsl(A, 0, 25, -10).hsl(Y, 0, 20, 0).build(),
        f("Selva Oscura", "Drones").t(100).c(-45).b(-60).allSat(-60).hsl(G, 0, -70, -15).build(),
        f("Selva Suave", "Drones").t(100).c(-45).b(-30).allSat(-45).hsl(G, 0, -45, -10).build(),

        // ---------- Cine ----------
        f("Cine 1", "Cine").t(100).c(-40).b(-60).hsl(Bl, -30, 10, -10).hsl(A, 0, 15, -10).hsl(O, 0, 15, 0).free().build(),
        f("Epic Dark", "Cine").t(100).c(-44).b(-45).hsl(G, 0, -25, -10).hsl(Bl, 0, -20, -5).free().build(),
        f("Cine 2", "Cine").t(100).c(-47).b(12).hsl(Bl, -30, 15, -10).hsl(A, 0, 20, -5).hsl(O, 0, 20, 0).hsl(R, 0, 10, 0).build(),
        f("Cinematic", "Cine").t(50).c(10).b(-6).hsl(A, 0, 20, -10).hsl(Bl, -25, 15, -10).hsl(O, 0, 20, 0).hsl(G, 0, -20, -8).build(),
        f("Teal & Orange", "Cine").t(40).c(-10).b(-2).hsl(Bl, -40, 35, -10).hsl(A, 0, 40, -5).hsl(O, 0, 40, 0).hsl(R, 0, 20, 0).hsl(G, 0, -30, -5).hsl(Y, 0, -10, 0).build(),
        f("Blockbuster", "Cine").t(50).c(15).b(-8).hsl(Bl, -30, 25, -15).hsl(A, 0, 30, -10).hsl(O, 0, 25, 0).hsl(G, 0, -30, -10).build(),
        f("Noir", "Cine").t(60).c(30).b(-8).allSat(-100).build(),
        f("Moody", "Cine").t(50).c(-20).b(-14).allSat(-20).hsl(Bl, 0, -20, -15).hsl(A, 0, -20, -15).hsl(O, 0, -15, 0).build(),
        f("Cold Steel", "Cine").t(60).c(15).b(-5).hsl(O, 0, -40, 0).hsl(Y, 0, -40, 0).hsl(R, 0, -30, 0).hsl(Bl, 0, 10, -10).hsl(A, 0, 25, -5).build(),
        f("Vintage Film", "Cine").t(30).c(-30).b(6).allSat(-25).hsl(Y, -10, -15, 0).hsl(O, 0, -15, 0).build(),
        f("Neon Night", "Cine").t(80).c(10).b(-14).hsl(M, 0, 45, 0).hsl(P, 0, 45, 0).hsl(Bl, 0, 30, -10).hsl(A, 0, 40, 0).build(),
    )

    fun loadUser(context: Context): List<Formula> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveUser(context: Context, list: List<Formula>) {
        val arr = JSONArray()
        list.filter { !it.builtIn }.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(f: Formula) = JSONObject().apply {
        put("name", f.name)
        // Los ajustes se guardan por nombre, para que añadir ajustes nuevos no rompa los filtros antiguos.
        put("values", JSONObject().also { o -> Adj.entries.forEachIndexed { i, a -> o.put(a.name, f.values[i].toDouble()) } })
        put("hsl", JSONArray(f.hsl.map { it.toDouble() }))
        put("grade", JSONArray(f.grade.map { it.toDouble() }))
    }

    private fun fromJson(o: JSONObject): Formula? {
        val name = o.optString("name").ifBlank { return null }
        val values = defaultValues()
        o.optJSONObject("values")?.let { vo -> Adj.entries.forEachIndexed { i, a -> if (vo.has(a.name)) values[i] = vo.getDouble(a.name).toFloat() } }
        val hsl = FloatArray(hslBands.size * 3)
        o.optJSONArray("hsl")?.let { arr -> for (i in 0 until minOf(arr.length(), hsl.size)) hsl[i] = arr.getDouble(i).toFloat() }
        val grade = defaultGrade()
        o.optJSONArray("grade")?.let { arr -> for (i in 0 until minOf(arr.length(), grade.size)) grade[i] = arr.getDouble(i).toFloat() }
        return Formula(name, values, hsl, grade)
    }
}
