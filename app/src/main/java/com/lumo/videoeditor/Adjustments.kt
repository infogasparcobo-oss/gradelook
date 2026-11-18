package com.lumo.videoeditor

/**
 * Ajustes simples (un deslizador). Los valores internos van de -1..1 (o 0..1 cuando [min] = 0)
 * y la interfaz los muestra de -100..100 / 0..100, igual que Kuni Cam.
 */
enum class Adj(val label: String, val min: Float, val max: Float, val uniform: String, val default: Float = 0f, val response: Float = 1f) {
    BRIGHTNESS("Brillo", -1f, 1f, "uBrightness", response = 0.72f),
    CONTRAST("Contraste", -1f, 1f, "uContrast", response = 0.60f),
    HIGHLIGHTS("Luces", -1f, 1f, "uHighlights", response = 0.66f),
    SHADOWS("Sombras", -1f, 1f, "uShadows", response = 0.74f),
    SATURATION("Saturación", -1f, 1f, "uSaturation", response = 0.60f),
    VIBRANCE("Vibrance", -1f, 1f, "uVibrance", response = 1.50f),
    TINT("Color", -1f, 1f, "uTint"),
    TEMPERATURE("Temperatura", -1f, 1f, "uTemperature", response = 0.67f),
    GRAIN("Grano", 0f, 1f, "uGrain"),
    GRAIN_SIZE("Tamaño", 0f, 1f, "uGrainSize", 0.5f),
    GRAIN_ROUGH("Aspereza", 0f, 1f, "uGrainRough", 0.5f),
    FADE("Atenuar", 0f, 1f, "uFade", response = 0.73f),
    SHARPEN("Nitidez", 0f, 1f, "uSharpen", response = 0.60f),
    TEXTURE("Textura", -1f, 1f, "uTexture", response = 1.20f),
    CLARITY("Claridad", -1f, 1f, "uClarity", response = 0.76f),
    /** Propio de Gradelook (no existe en Kuni): imagen fina y limpia (suaviza ruido y grano, mantiene bordes nítidos). */
    DEFINITION("Definición", 0f, 1f, "uDefinition"),
    LIGHTEN("Aclarar", -1f, 1f, "uLighten"),
    VIGNETTE("Viñeta", 0f, 1f, "uVignette", response = 0.73f),
}

/** Bandas de color para el panel HSL: nombre y tono central (0..1). */
val hslBands = listOf(
    "Rojo" to 0f / 360f,
    "Naranja" to 30f / 360f,
    "Amarillo" to 60f / 360f,
    "Verde" to 120f / 360f,
    "Aqua" to 180f / 360f,
    "Azul" to 230f / 360f,
    "Morado" to 280f / 360f,
    "Magenta" to 320f / 360f,
)

/** Zonas de la gradación de color (como en Kuni). */
val gradeZones = listOf("Sombras", "Tonos medios", "Luces", "Global")

/** Por zona: tono (0..1), saturación (0..1) y luminancia (-1..1). Después: fusión (0..1) y equilibrio (-1..1). */
const val GRADE_SIZE = 4 * 3 + 2
const val GRADE_BLEND = 12
const val GRADE_BALANCE = 13

fun defaultGrade(): FloatArray = FloatArray(GRADE_SIZE).also { it[GRADE_BLEND] = 0.5f }

fun defaultValues(): FloatArray = FloatArray(Adj.entries.size) { Adj.entries[it].default }

/** Copia inmutable de los valores que lee el shader en cada fotograma. */
class AdjustmentSnapshot(
    val values: FloatArray = defaultValues(),
    /** 8 bandas x (tono, saturación, luminosidad), cada uno en -1..1. */
    val hsl: FloatArray = FloatArray(hslBands.size * 3),
    val grade: FloatArray = defaultGrade(),
) {
    /** true si ningún ajuste cambia la imagen (permite saltar el paso de efectos al exportar). */
    fun isNeutral(): Boolean {
        val d = defaultValues()
        if (values.indices.any { values[it] != d[it] && !isGrainShape(it) }) return false
        if (hsl.any { it != 0f }) return false
        for (z in 0 until 4) if (grade[z * 3 + 1] != 0f || grade[z * 3 + 2] != 0f) return false
        return true
    }

    private fun isGrainShape(i: Int) = Adj.entries[i] == Adj.GRAIN_SIZE || Adj.entries[i] == Adj.GRAIN_ROUGH
}

/** Contenedor compartido entre la UI y el hilo de render. */
class AdjustmentHolder {
    @Volatile
    var snapshot = AdjustmentSnapshot()
}
