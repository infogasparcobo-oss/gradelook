@file:OptIn(UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Efecto de luz con varios tipos (ver [LightLook.kinds]): rayos de sol, resplandor, fuga de luz, destello de lente y niebla
 * luminosa. Todos se mezclan con la imagen como una luz real (pantalla) y usan caídas suaves, sin bordes duros.
 */
data class LightSettings(
    val enabled: Boolean = false,
    /** Tipo de luz: índice en [LightLook.kinds]. */
    val kind: Int = 0,
    /** Nombre del efecto listo elegido (para marcarlo en el panel); vacío si se ajustó a mano desde cero. */
    val name: String = "",
    /** Posición del sol: 0..1 desde la izquierda y desde ABAJO (1 = arriba). Puede salirse un poco del cuadro. */
    val x: Float = 0.2f,
    val y: Float = 0.95f,
    val intensity: Float = 0.7f,
    /** Segundo elemento del tipo (rayos, franja del destello, mancha rosada de la fuga...) y resplandor, 0..1. */
    val rays: Float = 0.6f,
    val glow: Float = 0.7f,
    /** Índice en [LightLook.colors]. */
    val color: Int = 0,
    /** Movimiento suave: el sol se mueve despacio, los haces giran y la luz "respira". */
    val anim: Boolean = true,
)

object LightLook {
    class Swatch(val label: String, val argb: Int)
    val colors = listOf(
        Swatch("Dorado", Color.rgb(255, 205, 120)), Swatch("Cálido", Color.rgb(255, 165, 85)), Swatch("Blanco", Color.rgb(255, 245, 228)),
        Swatch("Rosado", Color.rgb(255, 150, 150)), Swatch("Amanecer", Color.rgb(255, 190, 150)), Swatch("Frío", Color.rgb(190, 225, 255)),
    )

    const val RAYS = 0; const val GLOW = 1; const val LEAK = 2; const val FLARE = 3; const val FOG = 4
    val kinds = listOf("Rayos", "Resplandor", "Fuga de luz", "Destello", "Niebla")

    class Preset(val name: String, val s: LightSettings)
    val presets = listOf(
        Preset("Rayos suaves", LightSettings(true, RAYS, "Rayos suaves", 0.15f, 1.0f, 0.7f, 0.55f, 0.6f, 0)),
        Preset("Rayos fuertes", LightSettings(true, RAYS, "Rayos fuertes", 0.2f, 1.04f, 0.95f, 0.95f, 0.5f, 0)),
        Preset("Resplandor", LightSettings(true, GLOW, "Resplandor", 0.75f, 0.82f, 0.8f, 0.3f, 0.85f, 0)),
        Preset("Fuga de luz", LightSettings(true, LEAK, "Fuga de luz", 0.02f, 0.55f, 0.55f, 0.6f, 0.75f, 1)),
        Preset("Destello", LightSettings(true, FLARE, "Destello", 0.78f, 0.82f, 0.8f, 0.7f, 0.7f, 2)),
        Preset("Niebla dorada", LightSettings(true, FOG, "Niebla dorada", 0.5f, 0.7f, 0.42f, 0.5f, 0.7f, 0)),
        Preset("Amanecer", LightSettings(true, RAYS, "Amanecer", 0.85f, 0.5f, 0.85f, 0.6f, 0.8f, 4)),
        Preset("Atardecer", LightSettings(true, RAYS, "Atardecer", 0.12f, 0.35f, 0.85f, 0.5f, 1.0f, 1)),
    )
}

/** Valores compartidos entre la interfaz y el hilo de render. */
class LightHolder {
    @Volatile var s = LightSettings()
}

class LightEffect(private val holder: LightHolder) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = LightShaderProgram(holder, useHdr)
}

private class LightShaderProgram(private val holder: LightHolder, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var aspect = 1f

    init {
        try {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        aspect = inputWidth.toFloat() / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = holder.s
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            val c = LightLook.colors[s.color.coerceIn(0, LightLook.colors.size - 1)].argb
            program.setFloatUniform("uTime", (presentationTimeUs % 100_000_000L) / 1_000_000f)
            program.setFloatUniform("uAspect", aspect)
            program.setFloatUniform("uIntensity", if (s.enabled) s.intensity else 0f)
            program.setFloatUniform("uRays", s.rays)
            program.setFloatUniform("uGlow", s.glow)
            program.setFloatUniform("uAnim", if (s.anim) 1f else 0f)
            program.setFloatUniform("uKind", s.kind.toFloat())
            program.setFloatsUniform("uSun", floatArrayOf(s.x, s.y))
            program.setFloatsUniform("uColor", floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    companion object {
        const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        const val FRAGMENT_SHADER = """
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTime, uAspect, uIntensity, uRays, uGlow, uAnim, uKind;
uniform vec2 uSun;
uniform vec3 uColor;

vec3 tex(vec2 p) { return texture2D(uTexSampler, clamp(p, 0.0, 1.0)).rgb; }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
float vnoise(vec2 p) {
  vec2 i = floor(p); vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) { return vnoise(p) * 0.55 + vnoise(p * 2.1 + 3.7) * 0.30 + vnoise(p * 4.3 + 1.3) * 0.15; }

// Lo muy brillante de la imagen, desenfocado en anillos: el resplandor (bloom) natural de cielo y nubes.
// Pocas muestras (6 + 6): una vista previa fluida importa más que un halo perfecto, y el ruido final disimula el resto.
vec3 bloomAt(vec2 uv, float r0) {
  vec3 b = vec3(0.0);
  for (int i = 0; i < 6; i++) {
    float a = float(i) * 1.0471976 + 0.3;
    vec2 dv = vec2(cos(a) / uAspect, sin(a));
    vec3 s1 = tex(uv + dv * r0 * 1.6);
    vec3 s2 = tex(uv + dv * r0 * 4.6);
    b += s1 * smoothstep(0.50, 1.0, luma(s1)) + s2 * smoothstep(0.50, 1.0, luma(s2)) * 0.7;
  }
  return b / 8.0;
}

// Rayos radiales: lo brillante se estira hacia el sol.
vec3 radialRays(vec2 uv, vec2 sun) {
  vec2 dir = (sun - uv) * 0.075;
  vec2 p = uv + dir * (0.3 + 0.7 * fract(sin(dot(uv, vec2(12.9898, 78.233))) * 43758.5453));   // desfase por píxel: menos escalones
  vec3 acc = vec3(0.0); float decay = 1.0;
  for (int i = 0; i < 12; i++) {
    p += dir;
    vec3 s = tex(p);
    acc += s * smoothstep(0.58, 1.0, luma(s)) * decay;
    decay *= 0.89;
  }
  return acc / 6.0;
}

// Cuánta luz del sol llega a este punto: se atenúa si en el camino hacia el sol hay algo oscuro (montaña, árbol, tejado).
float transmit(vec2 uv, vec2 sun) {
  float t = 1.0;
  vec2 p = uv;
  vec2 st = (sun - uv) / 5.0;
  for (int i = 0; i < 5; i++) {
    p += st;
    t *= mix(0.62, 1.0, smoothstep(0.30, 0.70, luma(tex(p))));
  }
  return t;
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec3 base = tex(uv);
  if (uIntensity <= 0.001) { gl_FragColor = vec4(base, 1.0); return; }

  vec2 sun = uSun + uAnim * vec2(sin(uTime * 0.30) * 0.04, cos(uTime * 0.23) * 0.02);
  float breathe = 1.0 + uAnim * 0.07 * sin(uTime * 0.9);
  vec2 d = uv - sun;
  d.x *= uAspect;
  float dist = length(d);
  vec3 warm = uColor;
  vec3 tint = mix(vec3(1.0), warm, 0.65);
  vec3 light = vec3(0.0);
  // Oclusión: la luz que llega pasando por montañas y árboles se apaga (los rayos aparecen por los huecos y en el aire).
  // (Solo los tipos que la usan: Rayos, Resplandor y Niebla; fuga de luz y destello no la necesitan.)
  float tr = (uKind < 1.5 || uKind > 3.5) ? transmit(uv, sun) : 1.0;
  float occl = mix(0.30, 1.0, tr);
  // Si el sol cae sobre algo oscuro, el núcleo brillante se contiene: un sol "pegado" sobre una montaña se ve falso.
  float sunSky = 0.35 + 0.65 * smoothstep(0.28, 0.75, luma(tex(sun)));

  if (uKind < 0.5) {
    // RAYOS: haces anchos y blandos que se abren desde el sol (ondas enteras: sin costura) + rayos de lo brillante.
    float ang = atan(d.y, d.x);
    float s = 0.5 + 0.5 * (0.42 * sin(ang * 3.0 + uTime * 0.10 * uAnim)
                         + 0.30 * sin(ang * 5.0 - uTime * 0.07 * uAnim + 1.3)
                         + 0.18 * sin(ang * 8.0 + 0.6 + uTime * 0.05 * uAnim)
                         + 0.10 * sin(ang * 13.0 + 2.1));
    float shafts = smoothstep(0.22, 0.95, s);
    float reach = exp(-dist * 1.25) * smoothstep(0.0, 0.14, dist);
    float wash = exp(-dist * 2.0) * 0.8 + exp(-dist * 12.0) * 0.35 * sunSky;
    light = warm * (wash * uGlow * 0.55 * mix(0.6, 1.0, tr) + shafts * reach * uRays * 0.85 * occl) * breathe
          + radialRays(uv, sun) * tint * uRays * 0.9
          + bloomAt(uv, 0.012) * tint * uGlow * 0.55;
  } else if (uKind < 1.5) {
    // RESPLANDOR: la luz se "derrama" desde las zonas claras, con un velo cálido amplio.
    float wash = exp(-dist * 1.5) * 0.7 + exp(-dist * 9.0) * 0.4 * sunSky;
    light = bloomAt(uv, 0.016) * tint * uGlow * 2.0
          + warm * wash * uGlow * 0.9 * mix(0.6, 1.0, tr) * breathe
          + radialRays(uv, sun) * tint * uRays * 0.5;
  } else if (uKind < 2.5) {
    // FUGA DE LUZ: manchas de color suaves que entran por un borde, como una película con luz colada.
    float flick = 1.0 + uAnim * (0.10 * sin(uTime * 0.7) + 0.05 * sin(uTime * 1.9 + 1.0));
    float l1 = exp(-dist * 1.8);
    vec2 d2 = vec2((uv.x - (sun.x + 0.30)) * uAspect, uv.y - (sun.y - 0.28));
    float l2 = exp(-length(d2) * 2.3);
    vec2 d3 = vec2((uv.x - (sun.x + 0.05)) * uAspect, uv.y - (sun.y + 0.30));
    float l3 = exp(-length(d3) * 3.2);
    light = (warm * l1 * 0.95 + vec3(1.0, 0.36, 0.48) * l2 * 0.65 * uRays + vec3(1.0, 0.78, 0.42) * l3 * 0.5) * uGlow * 1.25 * flick;
    // un velo muy leve de textura evita que se vea un degradado plano
    light *= 0.92 + 0.16 * fbm(uv * vec2(uAspect, 1.0) * 3.0 + uTime * 0.03 * uAnim);
  } else if (uKind < 3.5) {
    // DESTELLO: núcleo, franja horizontal de lente y reflejos en línea con el centro del cuadro.
    vec3 cool = mix(warm, vec3(0.55, 0.78, 1.0), 0.45);
    float core = exp(-dist * 16.0) * 0.85 + exp(-dist * 4.0) * 0.28;
    float streak = exp(-abs(d.y) * 38.0) * exp(-abs(d.x) * 1.7) * uRays;
    vec3 fl = warm * core * uGlow + cool * streak * 0.55;
    vec2 m = vec2(1.0) - sun;
    for (int k = 1; k <= 4; k++) {
      float t = 0.30 * float(k);
      vec2 g = mix(sun, m, t);
      vec2 dg = uv - g; dg.x *= uAspect;
      float r = 0.025 + 0.018 * float(k);
      float disc = smoothstep(r, r * 0.55, length(dg)) * 0.10 + exp(-pow((length(dg) - r) * 26.0, 2.0)) * 0.10;
      vec3 gc = k == 1 ? vec3(1.0, 0.55, 0.35) : (k == 2 ? vec3(0.55, 0.85, 1.0) : (k == 3 ? vec3(0.85, 0.55, 1.0) : vec3(1.0, 0.85, 0.5)));
      fl += gc * disc * uRays;
    }
    light = fl * breathe + bloomAt(uv, 0.012) * tint * 0.6;
  } else {
    // NIEBLA LUMINOSA: bruma dorada que se mueve despacio y se concentra hacia el sol.
    float fog = fbm(uv * vec2(uAspect, 1.0) * 2.2 + vec2(uTime * 0.025 * uAnim, uTime * 0.012 * uAnim));
    float near = exp(-dist * 1.1);
    light = warm * ((near * 0.55 + fog * near * uRays * 1.1) * mix(0.55, 1.0, tr) + exp(-dist * 6.0) * 0.3 * sunSky) * uGlow * 1.3 * breathe
          + bloomAt(uv, 0.014) * tint * 0.9;
  }

  light *= uIntensity * 1.5;
  // Integración: la luz se suma como luz real sobre las superficies. Cada superficie la toma con su propio color y brillo
  // (el pasto verde se ilumina verde-dorado, la roca gris queda gris cálido), las sombras profundas se resisten y las
  // texturas se conservan; ya no es una capa plana encima. Fuga de luz y destello son de lente/película: sí van más "encima".
  float surface = (uKind < 1.5 || uKind > 3.5) ? 1.0 : 0.4;
  float lb = luma(base);
  vec3 take = mix(vec3(1.0), (0.20 + 0.95 * base) * (0.35 + 0.65 * smoothstep(0.06, 0.62, lb)), surface);
  vec3 c = base + light * take;
  // Hombro suave: los blancos se comprimen en vez de quemarse de golpe.
  vec3 over = max(c - 0.80, vec3(0.0));
  c = min(c, vec3(0.80)) + 0.20 * (1.0 - exp(-over * 5.0));
  // Ruido casi invisible: evita bandas en los degradados suaves.
  c += (hash(uv * vec2(1920.0, 1080.0) + fract(uTime)) - 0.5) * (1.5 / 255.0);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
