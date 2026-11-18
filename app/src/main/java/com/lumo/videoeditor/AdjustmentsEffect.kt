@file:OptIn(UnstableApi::class)

package com.lumo.videoeditor

import android.content.Context
import android.opengl.GLES20
import kotlin.math.pow
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

class AdjustmentsEffect(private val holder: AdjustmentHolder) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        AdjustmentsShaderProgram(holder, useHdr)
}

internal class AdjustmentsShaderProgram(
    private val holder: AdjustmentHolder,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram
    private var texelX = 0f
    private var texelY = 0f

    init {
        try {
            program = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            program.setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelX = 1f / inputWidth
        texelY = 1f / inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = holder.snapshot
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            setAdjustmentUniforms(program, s, texelX, texelY, (presentationTimeUs % 100_000_000L) / 1_000_000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
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

        val FRAGMENT_SHADER = """
precision highp float;
varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uTime;
uniform float uBrightness, uContrast, uHighlights, uShadows, uLighten;
uniform float uSaturation, uVibrance, uTint, uTemperature;
uniform float uSharpen, uTexture, uClarity, uDefinition;
uniform float uGrain, uGrainSize, uGrainRough, uFade, uVignette;
uniform vec3 uGradeS, uGradeM, uGradeH, uGradeG;
uniform vec2 uGradeMix;
uniform vec3 uHsl0, uHsl1, uHsl2, uHsl3, uHsl4, uHsl5, uHsl6, uHsl7;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float fine(float v) { return v * (0.35 + 0.65 * abs(v)); }

// Curva tonal medida en Kuni: 8 puntos (luma 0.06, 0.19, ..., 0.94) con interpolación lineal.
float lut8(float l, float a0, float a1, float a2, float a3, float a4, float a5, float a6, float a7) {
  float t = clamp(l * 8.0 - 0.5, 0.0, 7.0);
  return a0 + clamp(t, 0.0, 1.0) * (a1 - a0) + clamp(t - 1.0, 0.0, 1.0) * (a2 - a1)
       + clamp(t - 2.0, 0.0, 1.0) * (a3 - a2) + clamp(t - 3.0, 0.0, 1.0) * (a4 - a3)
       + clamp(t - 4.0, 0.0, 1.0) * (a5 - a4) + clamp(t - 5.0, 0.0, 1.0) * (a6 - a5)
       + clamp(t - 6.0, 0.0, 1.0) * (a7 - a6);
}
vec3 chroma(vec3 c, float g) { float lm = dot(c, vec3(0.2126, 0.7152, 0.0722)); return vec3(lm) + (c - vec3(lm)) * g; }

// Curvas por tono (t = tono en radianes) medidas contra Kuni con Aclarar al 8%.
float kuniHueShift(float t) {
  return -1.2042
    + -0.4773 * cos(1.0 * t) + 9.5651 * sin(1.0 * t)
    + -2.2156 * cos(2.0 * t) + 1.1042 * sin(2.0 * t)
    + 1.1608 * cos(3.0 * t) + -1.3965 * sin(3.0 * t);
}
float kuniHueSat(float t) {
  return 1.1688
    + -0.1603 * cos(1.0 * t) + -0.0861 * sin(1.0 * t)
    + -0.0852 * cos(2.0 * t) + -0.0433 * sin(2.0 * t)
    + 0.0442 * cos(3.0 * t) + 0.0631 * sin(3.0 * t);
}
float kuniHueVal(float t) {
  return 1.0171
    + 0.0512 * cos(1.0 * t) + -0.0372 * sin(1.0 * t)
    + -0.0152 * cos(2.0 * t) + 0.0062 * sin(2.0 * t)
    + 0.0371 * cos(3.0 * t) + 0.0053 * sin(3.0 * t);
}

vec3 blurAt(vec2 uv, float r) {
  vec2 o = uTexel * r;
  vec3 s = texture2D(uTexSampler, uv).rgb * 4.0;
  s += texture2D(uTexSampler, uv + vec2(o.x, 0.0)).rgb * 2.0;
  s += texture2D(uTexSampler, uv - vec2(o.x, 0.0)).rgb * 2.0;
  s += texture2D(uTexSampler, uv + vec2(0.0, o.y)).rgb * 2.0;
  s += texture2D(uTexSampler, uv - vec2(0.0, o.y)).rgb * 2.0;
  s += texture2D(uTexSampler, uv + o).rgb;
  s += texture2D(uTexSampler, uv - o).rgb;
  s += texture2D(uTexSampler, uv + vec2(o.x, -o.y)).rgb;
  s += texture2D(uTexSampler, uv + vec2(-o.x, o.y)).rgb;
  return s / 16.0;
}

// Una muestra del suavizado bilateral: pesa menos a los vecinos cuya luminancia difiere (bordes).
void tapB(vec2 p, float l0, float sg, inout vec3 acc, inout float ws) {
  vec3 n = texture2D(uTexSampler, p).rgb;
  float d = (luma(n) - l0) / sg;
  float w = exp(-d * d);
  acc += n * w; ws += w;
}

vec3 rgb2hsl(vec3 c) {
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float l = (mx + mn) * 0.5;
  float d = mx - mn;
  float h = 0.0;
  float s = 0.0;
  if (d > 0.00001) {
    s = d / (1.0 - abs(2.0 * l - 1.0) + 0.00001);
    if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    h /= 6.0;
  }
  return vec3(h, s, l);
}

vec3 hsl2rgb(vec3 hsl) {
  float c = (1.0 - abs(2.0 * hsl.z - 1.0)) * hsl.y;
  float h6 = hsl.x * 6.0;
  float x = c * (1.0 - abs(mod(h6, 2.0) - 1.0));
  vec3 r;
  if (h6 < 1.0) r = vec3(c, x, 0.0);
  else if (h6 < 2.0) r = vec3(x, c, 0.0);
  else if (h6 < 3.0) r = vec3(0.0, c, x);
  else if (h6 < 4.0) r = vec3(0.0, x, c);
  else if (h6 < 5.0) r = vec3(x, 0.0, c);
  else r = vec3(c, 0.0, x);
  return r + vec3(hsl.z - c * 0.5);
}

void band(inout vec3 hsl, float center, vec3 adj) {
  float d = abs(hsl.x - center);
  d = min(d, 1.0 - d);
  float w = (1.0 - smoothstep(0.0, 0.11, d)) * smoothstep(0.02, 0.15, hsl.y);
  hsl.x = fract(hsl.x + adj.x * 0.08 * w + 1.0);
  hsl.y = clamp(hsl.y * (1.0 + adj.y * w), 0.0, 1.0);
  hsl.z = clamp(hsl.z + adj.z * 0.25 * w, 0.0, 1.0);
}

float hash(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

void main() {
  vec2 uv = vTexSamplingCoord;
  vec3 c0 = texture2D(uTexSampler, uv).rgb;
  vec3 c = c0;

  // Detalle: nitidez, textura, claridad (a partir de la imagen original).
  // Se trabaja solo sobre la luminancia (sin halos de color) y los radios
  // escalan con la resolución del video; la curva "fine" hace más suave el inicio.
  float l0 = luma(c0);
  float rs = clamp(1.0 / (uTexel.y * 1080.0), 0.6, 2.5);
  vec3 dv = vec3(0.0);
  if (uSharpen > 0.0) {
    // Nitidez (Kuni: solo 0..100). Realza todas las escalas finas.
    dv += (c0 - blurAt(uv, 0.7 * rs)) * fine(uSharpen) * 3.35;
  }
  // Medido contra Kuni: con Textura +100 su brillo medio sube ~1.3/255 y el mío no se movía (combinada con Claridad negativa
  // mi imagen quedaba 1.6 más oscura). Se añade ese ajuste solo con Textura positiva.
  if (uTexture > 0.0) dv += vec3(0.0058 * uTexture);
  if (uTexture > 0.0) {
    // Textura (como Kuni): realce suave de banda media, solo sobre la luminancia (sin halos de color).
    dv += vec3((l0 - luma(blurAt(uv, 8.0 * rs))) * uTexture * 0.16);
    // 4K: la banda de Textura se mide en fracción de pantalla, así que a 4K se pierde el detalle fino extra
    // (hojas, bordes). Se añade una banda de ~2.5 px reales, solo desde 1440p hacia arriba.
    float k4 = smoothstep(1.3, 2.0, rs);
    if (k4 > 0.0) dv += vec3((l0 - luma(blurAt(uv, 2.5))) * uTexture * 0.20 * k4);
  } else if (uTexture < 0.0) {
    dv += vec3((l0 - luma(blurAt(uv, 3.0 * rs))) * uTexture * 0.24);   // negativa: suaviza el detalle fino
  }
  if (uClarity != 0.0) {
    // Claridad (Kuni): contraste local en todas las escalas. +100 sube el detalle ~1.4x, -100 lo baja ~0.64x.
    // Medido otra vez contra Kuni sobre un video 4K de dron a 1080p: -100 deja el detalle fino en 0.49x, el medio en 0.55x y
    // el grueso en 0.59x, baja las luces altas (de 91 a 79) y mantiene saturación y brillo medio. Por eso la parte
    // negativa suaviza mucho más y comprime las luces, y ya no compensa la saturación.
    float g = uClarity > 0.0 ? 0.62 : 0.66;
    dv += vec3((l0 - luma(blurAt(uv, 24.0 * rs))) * g * uClarity);
    if (uClarity > 0.0) dv += vec3(lut8(l0, -0.025, -0.060, -0.074, -0.040, -0.016, -0.012, -0.020, -0.030) * uClarity);
    else dv += vec3(lut8(l0, -0.010, -0.010, -0.015, -0.022, -0.025, -0.025, -0.025, -0.025) * (-uClarity));
  }
  if (uDefinition > 0.0) {
    // Definición (propia) = imagen FINA y CLARA, lo contrario de "más textura":
    // 1) suavizado bilateral (promedia vecinos parecidos, respeta los bordes) -> superficies limpias, sin grano ni
    //    bloques de compresión; 2) ruido de color eliminado; 3) bordes finos realzados con tope anti-halo.
    float u = uDefinition;
    vec2 o1 = uTexel * 1.0 * rs;
    vec2 o2 = uTexel * 2.2 * rs;
    float sg = 0.035;
    vec3 acc = c0; float ws = 1.0;
    tapB(uv + vec2(o1.x, 0.0), l0, sg, acc, ws);  tapB(uv - vec2(o1.x, 0.0), l0, sg, acc, ws);
    tapB(uv + vec2(0.0, o1.y), l0, sg, acc, ws);  tapB(uv - vec2(0.0, o1.y), l0, sg, acc, ws);
    tapB(uv + o1 * 0.707, l0, sg, acc, ws);       tapB(uv - o1 * 0.707, l0, sg, acc, ws);
    tapB(uv + vec2(o1.x, -o1.y) * 0.707, l0, sg, acc, ws); tapB(uv + vec2(-o1.x, o1.y) * 0.707, l0, sg, acc, ws);
    tapB(uv + vec2(o2.x, 0.0), l0, sg, acc, ws);  tapB(uv - vec2(o2.x, 0.0), l0, sg, acc, ws);
    tapB(uv + vec2(0.0, o2.y), l0, sg, acc, ws);  tapB(uv - vec2(0.0, o2.y), l0, sg, acc, ws);
    tapB(uv + o2 * 0.707, l0, sg, acc, ws);       tapB(uv - o2 * 0.707, l0, sg, acc, ws);
    tapB(uv + vec2(o2.x, -o2.y) * 0.707, l0, sg, acc, ws); tapB(uv + vec2(-o2.x, o2.y) * 0.707, l0, sg, acc, ws);
    vec3 bl = acc / ws;
    float edge = clamp((luma(bl) - luma(blurAt(uv, 0.8 * rs))) * 1.6, -0.05, 0.05);   // desenfoque vs. bilateral = solo bordes
    c = mix(c, bl, 0.9 * u);
    vec3 bc2 = blurAt(uv, 1.8 * rs);
    float lc = luma(c);
    c = vec3(lc) + mix(c - vec3(lc), bc2 - vec3(luma(bc2)), 0.6 * u);                  // ruido de color fuera
    dv += vec3(edge * u);
  }
  c += dv;
  // Claridad de Kuni casi no cambia la saturación (medido: x1.11 a +100, x0.95 a -100); se compensa la de mi curva.
  if (uClarity > 0.0) c = chroma(c, 1.0 - 0.16 * uClarity);
  if (uTexture != 0.0) {
    // Textura de Kuni también define el COLOR local: el contraste de color por escalas sube ~1.7x a +100 y
    // baja ~0.6x a -100 (medido). Se realza la diferencia de croma respecto a su entorno, sin tocar la luminancia.
    vec3 bc = blurAt(uv, 20.0 * rs);
    vec3 chromaLocal = (c0 - vec3(l0)) - (bc - vec3(luma(bc)));
    // Tope suave: sin él, una roca gris en un campo verde se empuja al color opuesto (morado fluorescente).
    chromaLocal = clamp(chromaLocal, vec3(-0.05), vec3(0.05));
    c += chromaLocal * (uTexture > 0.0 ? 1.0 : 0.40) * uTexture;
    c = chroma(c, 1.0 + (uTexture > 0.0 ? 0.02 : 0.05) * uTexture);
  }

  // Luz.
  c += uBrightness * 0.2;
  // Contraste, luces y sombras: curvas tonales medidas en Kuni a +100 y -100 (lineales en el valor).
  if (uContrast > 0.0) {
    c += vec3(lut8(luma(c), -0.110, -0.134, -0.094, -0.030, 0.033, 0.096, 0.119, 0.046) * uContrast);
    c = chroma(c, 1.0 + 0.40 * uContrast);
  } else if (uContrast < 0.0) {
    c += vec3(lut8(luma(c), 0.215, 0.158, 0.096, 0.030, -0.034, -0.100, -0.158, -0.238) * (-uContrast));
    c = chroma(c, 1.0 + 0.55 * uContrast);
  }
  c = clamp(c, 0.0, 1.0);
  float l = luma(c);
  vec3 cLS = c;
  if (uHighlights > 0.0) c += vec3(lut8(l, 0.0, 0.0, 0.0, 0.010, 0.050, 0.127, 0.145, 0.045) * uHighlights);
  else if (uHighlights < 0.0) c += vec3(lut8(l, 0.0, 0.001, 0.0, -0.007, -0.040, -0.114, -0.129, -0.032) * (-uHighlights));
  if (uShadows > 0.0) c += vec3(lut8(l, 0.055, 0.101, 0.079, 0.033, 0.010, 0.0, 0.004, 0.004) * uShadows);
  else if (uShadows < 0.0) c += vec3(lut8(l, -0.108, -0.143, -0.111, -0.040, -0.008, 0.004, 0.003, 0.006) * (-uShadows));
  c = clamp(c, 0.0, 1.0);
  if (uShadows != 0.0 || uHighlights != 0.0) {
    // Kuni mantiene la saturación (HSV) al mover luces/sombras: se restituye tras sumar luminancia.
    float mxB = max(cLS.r, max(cLS.g, cLS.b)); float mnB = min(cLS.r, min(cLS.g, cLS.b));
    float sB = mxB > 0.0001 ? (mxB - mnB) / mxB : 0.0;
    float mxA = max(c.r, max(c.g, c.b)); float mnA = min(c.r, min(c.g, c.b));
    float dA = mxA - mnA;
    if (dA > 0.0001) c = vec3(mxA) - (vec3(mxA) - c) * (mxA * sB / dA);
    c = clamp(c, 0.0, 1.0);
  }
  // Aclarar (Kuni): +100 oscurece y satura mucho (tipo "quitar neblina"), -100 sube negros y desatura (velo).
  // La respuesta es cóncava: al 8 % ya hace ~1/3 del efecto total (medido), de ahí los exponentes.
  if (uLighten < 0.0) {
    float n = -uLighten;
    c += vec3(lut8(luma(c), 0.488, 0.482, 0.428, 0.371, 0.305, 0.225, 0.151, 0.045) * n);
    c += vec3((luma(blurAt(uv, 8.0 * rs)) - l0) * 0.75 * n);   // el velo borra el detalle fino
    c = chroma(c, 1.0 - 0.84 * n);
  }
  if (uLighten > 0.0) {
    float a = pow(uLighten, 0.55);                 // tono
    float s = min(1.0, a * 4.0);                   // ajustes por tono medidos al 8 %
    c += vec3(lut8(luma(c), -0.107, -0.196, -0.243, -0.244, -0.227, -0.199, -0.151, -0.078) * a);
    c += vec3((l0 - luma(blurAt(uv, 24.0 * rs))) * 0.45 * uLighten);   // y sube el contraste local
    c = clamp(c, 0.0, 1.0);
    c = chroma(c, 1.0 + 1.10 * pow(uLighten, 0.45));
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float sp = mx > 0.0001 ? (mx - mn) / mx : 0.0;
    vec3 hs = rgb2hsl(c);
    float ht = hs.x * 6.2831853;
    float hval = 1.0 + (kuniHueVal(ht) - 1.0) * s * smoothstep(0.08, 0.25, sp);
    hs.x = fract(hs.x + kuniHueShift(ht) / 360.0 * s + 1.0);
    c = clamp(hsl2rgb(hs) * hval, 0.0, 1.0);
  }

  // Color: temperatura (Kuni: suma a los tres canales, más rojo que verde y azul) y matiz.
  c += uTemperature * vec3(0.215, 0.115, 0.058);
  c.g -= uTint * 0.08;
  c.r += uTint * 0.04;
  c.b += uTint * 0.04;
  c = clamp(c, 0.0, 1.0);

  // Atenuar (Kuni): comprime hacia el gris medio y desatura a la mitad.
  c += vec3(lut8(luma(c), 0.215, 0.127, 0.063, 0.024, -0.002, -0.025, -0.058, -0.128) * uFade);
  c = chroma(c, 1.0 - 0.45 * uFade);
  c = clamp(c, 0.0, 1.0);

  // Saturación y vibrance.
  l = luma(c);
  // Saturación (Kuni): +100 sube ~1.85x (HSV); -100 deja ~0.55x (no llega a gris).
  c = mix(vec3(l), c, uSaturation >= 0.0 ? 1.0 + 1.35 * uSaturation : 1.0 + 0.45 * uSaturation);
  if (uVibrance < 0.0) {
    c = chroma(c, 1.0 + 0.62 * uVibrance);       // Kuni: -100 deja ~0.47x de saturación (HSV), parejo
    c += vec3(lut8(luma(c), -0.010, -0.031, -0.043, -0.034, -0.029, -0.025, -0.016, -0.002) * (-uVibrance));
  } else {
    // Vibrance + (Kuni): satura sobre todo lo apagado (x2.9 en tonos grises, x1.5 en los ya saturados) y oscurece un poco.
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float inv = 1.0 - sat;
    c = chroma(c, 1.0 + 1.9 * uVibrance * inv * inv);
    c += vec3(lut8(luma(c), 0.002, -0.007, -0.030, -0.027, -0.014, -0.008, -0.005, -0.002) * uVibrance);
  }
  c = clamp(c, 0.0, 1.0);

  // HSL por bandas.
  vec3 hsl = rgb2hsl(c);
  band(hsl, 0.0 / 360.0, uHsl0);
  band(hsl, 30.0 / 360.0, uHsl1);
  band(hsl, 60.0 / 360.0, uHsl2);
  band(hsl, 120.0 / 360.0, uHsl3);
  band(hsl, 180.0 / 360.0, uHsl4);
  band(hsl, 230.0 / 360.0, uHsl5);
  band(hsl, 280.0 / 360.0, uHsl6);
  band(hsl, 320.0 / 360.0, uHsl7);
  c = hsl2rgb(hsl);

  // Gradación de color por zonas (sombras, tonos medios, luces, global).
  // x = tono (0..1), y = intensidad (0..1), z = luminancia (-1..1).
  l = luma(c);
  float soft = 0.25 + 0.25 * uGradeMix.x;                  // fusión: ancho de la transición
  float tz = clamp(l + uGradeMix.y * 0.25, 0.0, 1.0);      // equilibrio: desplaza el reparto
  float wS = 1.0 - smoothstep(0.0, 0.35 + soft, tz);
  float wH = smoothstep(0.65 - soft, 1.0, tz);
  float wM = smoothstep(0.1, 0.5, tz) * (1.0 - smoothstep(0.5, 0.9, tz));
  c += (hsl2rgb(vec3(uGradeS.x, 1.0, 0.5)) - 0.5) * uGradeS.y * wS * 0.5 + uGradeS.z * wS * 0.25;
  c += (hsl2rgb(vec3(uGradeM.x, 1.0, 0.5)) - 0.5) * uGradeM.y * wM * 0.5 + uGradeM.z * wM * 0.25;
  c += (hsl2rgb(vec3(uGradeH.x, 1.0, 0.5)) - 0.5) * uGradeH.y * wH * 0.5 + uGradeH.z * wH * 0.25;
  c += (hsl2rgb(vec3(uGradeG.x, 1.0, 0.5)) - 0.5) * uGradeG.y * 0.3 + uGradeG.z * 0.25;
  c = clamp(c, 0.0, 1.0);


  // Viñeta.
  float d = distance(uv, vec2(0.5)) * 1.4142;
  // Kuni: el oscurecimiento crece casi lineal desde el centro (esquina ~0.14x a +100), no empieza en un borde.
  float v = 0.97 * pow(d, 1.1);
  c *= 1.0 - v * uVignette;

  // Grano.
  if (uGrain > 0.0) {
    // Tamaño: el ruido se calcula en celdas más grandes. Aspereza: mezcla ruido fino y grueso.
    float cell = 1.0 + uGrainSize * 3.0;
    vec2 seed = vec2(floor(uTime * 24.0) * 17.0, floor(uTime * 24.0) * 31.0);
    vec2 px = floor(uv / uTexel / cell);
    float n1 = hash(px + seed);
    float n2 = hash(floor(px / 2.0) + seed * 1.7);
    float n = mix(n1, mix(n1, n2, 0.5), 1.0 - uGrainRough);
    n = (n - 0.5) * (0.6 + 0.8 * uGrainRough);
    float gm = 1.0 - pow(abs(2.0 * luma(c) - 1.0), 3.0) * 0.6;
    c += n * uGrain * 0.4 * gm;
  }

  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}

/** Valores de los ajustes → uniformes del shader. Lo usan el video (cada fotograma) y las miniaturas de filtros. */
internal fun setAdjustmentUniforms(program: GlProgram, s: AdjustmentSnapshot, texelX: Float, texelY: Float, time: Float) {
    program.setFloatsUniform("uTexel", floatArrayOf(texelX, texelY))
    program.setFloatUniform("uTime", time)
    Adj.entries.forEachIndexed { i, adj ->
        // El deslizador es lineal (0..100) pero Kuni aplica una curva por ajuste (medida a +50 y +100).
        val v = s.values[i]
        val mapped = if (adj.response == 1f || v == adj.default) v else Math.signum(v) * Math.abs(v).toDouble().pow(adj.response.toDouble()).toFloat()
        program.setFloatUniform(adj.uniform, mapped)
    }
    val zones = arrayOf("uGradeS", "uGradeM", "uGradeH", "uGradeG")
    for (z in 0 until 4) program.setFloatsUniform(zones[z], s.grade.copyOfRange(z * 3, z * 3 + 3))
    program.setFloatsUniform("uGradeMix", floatArrayOf(s.grade[GRADE_BLEND], s.grade[GRADE_BALANCE]))
    for (i in hslBands.indices) {
        program.setFloatsUniform("uHsl$i", s.hsl.copyOfRange(i * 3, i * 3 + 3))
    }
}

/** Código de los shaders de ajustes, para reutilizarlos fuera del video. */
internal fun adjustmentShaderSources(): Pair<String, String> =
    AdjustmentsShaderProgram.VERTEX_SHADER to AdjustmentsShaderProgram.FRAGMENT_SHADER
