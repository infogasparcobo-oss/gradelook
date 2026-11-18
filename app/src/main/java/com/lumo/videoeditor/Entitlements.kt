package com.lumo.videoeditor

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Reglas de planes de Gradelook.
 *
 *  - Gratis: 3 exportaciones EN TOTAL por móvil (no se renuevan). Al agotarse, la exportación se bloquea.
 *  - Pro 9 USD/mes: 50 exportaciones al mes, 4K, todos los filtros y filtros propios sin límite.
 *  - Ilimitado 19 USD/mes: lo mismo sin tope de exportaciones.
 *
 * IMPORTANTE: hasta conectar Google Play Billing y un servidor, el plan y los contadores viven en el teléfono y se
 * pueden falsear. El contador gratis va en un archivo aparte que entra en la copia de seguridad de Android (sobrevive a
 * reinstalar con la misma cuenta de Google); el plan NO se respalda, para que una copia no regale Pro. Antes de publicar
 * hay que validar las compras y el contador gratis en un servidor (ANDROID_ID + Play Integrity).
 */
object Entitlements {
    const val FREE_EXPORTS = 3
    const val STANDARD_MONTHLY_EXPORTS = 50
    const val STANDARD_PRICE_USD = 9
    const val UNLIMITED_PRICE_USD = 19
    const val FREE_MAX_SHORT_SIDE = 1080
    const val FREE_SAVED_FORMULAS = 2

    private const val PERIOD_MS = 30L * 24 * 60 * 60 * 1000
    private const val PREFS = "gradelook_plan"          // plan y consumo mensual (no se respalda)
    private const val FREE_PREFS = "gradelook_free"     // contador gratis (se respalda en la nube de Android)

    enum class Plan { NONE, STANDARD, UNLIMITED }

    /** Cómo se cobrará la próxima exportación. */
    enum class Mode { FREE, MONTHLY, UNLIMITED, BLOCKED }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun freePrefs(c: Context) = c.getSharedPreferences(FREE_PREFS, Context.MODE_PRIVATE)
    private fun debuggable(c: Context) = c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    // Con el servidor activo ([Server.enabled]) el plan y los cupos son los que el servidor dijo la última vez (caché solo
    // para mostrar en pantalla; la autorización real de cada exportación siempre la da el servidor).
    private val useServer get() = Server.enabled
    private fun cache(c: Context) = c.getSharedPreferences("gradelook_server", Context.MODE_PRIVATE)

    fun applyServer(c: Context, plan: Plan, freeLeft: Int, monthlyLeft: Int) {
        cache(c).edit().putString("plan", plan.name).putInt("free_left", freeLeft).putInt("monthly_left", monthlyLeft).apply()
    }

    fun plan(c: Context): Plan = runCatching {
        Plan.valueOf((if (useServer) cache(c) else prefs(c)).getString("plan", Plan.NONE.name)!!)
    }.getOrDefault(Plan.NONE)
    fun isPro(c: Context) = plan(c) != Plan.NONE

    fun freeUsed(c: Context) = freePrefs(c).getInt("free_used", 0)
    /** Exportaciones gratis que quedan en este móvil (nunca negativo). */
    fun freeLeft(c: Context) = if (useServer) cache(c).getInt("free_left", FREE_EXPORTS) else (FREE_EXPORTS - freeUsed(c)).coerceAtLeast(0)

    /** Plan de 50 al mes: reinicia el consumo cada 30 días desde que empezó el período. */
    private fun refreshPeriod(c: Context) {
        if (plan(c) != Plan.STANDARD) return
        val p = prefs(c)
        val start = p.getLong("period_start", 0L)
        val now = System.currentTimeMillis()
        if (start == 0L) p.edit().putLong("period_start", now).putInt("monthly_used", 0).apply()
        else if (now - start >= PERIOD_MS) p.edit().putLong("period_start", start + PERIOD_MS * ((now - start) / PERIOD_MS)).putInt("monthly_used", 0).apply()
    }

    fun monthlyLeft(c: Context): Int {
        if (plan(c) != Plan.STANDARD) return 0
        if (useServer) return cache(c).getInt("monthly_left", 0)
        refreshPeriod(c)
        return (STANDARD_MONTHLY_EXPORTS - prefs(c).getInt("monthly_used", 0)).coerceAtLeast(0)
    }

    fun exportMode(c: Context): Mode = when (plan(c)) {
        Plan.UNLIMITED -> Mode.UNLIMITED
        Plan.STANDARD -> if (monthlyLeft(c) > 0) Mode.MONTHLY else Mode.BLOCKED
        Plan.NONE -> if (freeLeft(c) > 0) Mode.FREE else Mode.BLOCKED
    }

    /** Se llama cuando una exportación terminó y se guardó: cuenta la exportación según el modo con que se inició. */
    fun registerExport(c: Context, mode: Mode) {
        if (useServer) return   // el servidor ya contó la exportación al autorizarla
        when (mode) {
            Mode.FREE -> freePrefs(c).edit().putInt("free_used", freeUsed(c) + 1).apply()
            Mode.MONTHLY -> prefs(c).edit().putInt("monthly_used", prefs(c).getInt("monthly_used", 0) + 1).apply()
            else -> {}
        }
    }

    fun isFormulaLocked(c: Context, f: Formula) = !isPro(c) && f.builtIn && !f.free
    fun canSaveFormula(c: Context, userCount: Int) = isPro(c) || userCount < FREE_SAVED_FORMULAS

    // ---- Solo pruebas (compilaciones de depuración) ----

    fun setPlanForTesting(c: Context, plan: Plan): Boolean {
        if (!debuggable(c) || useServer) return false   // con el servidor activo el plan solo lo cambian las compras reales
        prefs(c).edit().putString("plan", plan.name).putLong("period_start", if (plan == Plan.STANDARD) System.currentTimeMillis() else 0L)
            .putInt("monthly_used", 0).apply()
        return true
    }

    fun resetForTesting(c: Context) {
        if (!debuggable(c)) return
        prefs(c).edit().clear().apply()
        freePrefs(c).edit().clear().apply()
    }
}
