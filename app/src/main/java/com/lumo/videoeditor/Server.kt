package com.lumo.videoeditor

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Cliente del servidor de Gradelook (Firebase Functions): contador de exportaciones por móvil, planes y compras.
 * Cada petición lleva un token de Play Integrity ligado a este móvil y a la hora, para que no se pueda falsear
 * ni repetir. Si [enabled] es false (versión de depuración) la app usa solo los contadores locales.
 */
object Server {
    private const val PROJECT_ID = "gradelook-88444"
    private const val PROJECT_NUMBER = 1072291731737L   // número del proyecto de Google Cloud, para Play Integrity
    private const val BASE = "https://us-central1-$PROJECT_ID.cloudfunctions.net"

    val enabled: Boolean get() = BuildConfig.USE_SERVER

    class ServerException(val code: String, message: String, val network: Boolean = false) : Exception(message)

    data class State(val plan: Entitlements.Plan, val freeLeft: Int, val monthlyLeft: Int)
    data class Auth(val ok: Boolean, val mode: String, val ticket: String?, val state: State)

    /** Identificador estable de este móvil: SHA-256 de ANDROID_ID (propio de esta app) y el paquete. */
    fun deviceId(c: Context): String {
        val raw = Settings.Secure.getString(c.contentResolver, Settings.Secure.ANDROID_ID).orEmpty() + "|" + c.packageName
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun nonce(deviceId: String, ts: Long): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest("$deviceId|$ts".toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private suspend fun integrityToken(c: Context, nonce: String): String = suspendCancellableCoroutine { cont ->
        IntegrityManagerFactory.create(c)
            .requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).setCloudProjectNumber(PROJECT_NUMBER).build())
            .addOnSuccessListener { cont.resume(it.token()) }
            .addOnFailureListener { cont.resumeWith(Result.failure(ServerException("integrity", "No se pudo verificar este dispositivo (${it.message})"))) }
    }

    private suspend fun call(c: Context, name: String, extra: Map<String, Any> = emptyMap()): JSONObject {
        val id = deviceId(c)
        val ts = System.currentTimeMillis()
        val body = JSONObject().put("deviceId", id).put("ts", ts).put("integrityToken", integrityToken(c, nonce(id, ts)))
        extra.forEach { (k, v) -> body.put(k, v) }
        return withContext(Dispatchers.IO) {
            val conn = (URL("$BASE/$name").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(JSONObject().put("data", body).toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (code in 200..299 && json?.has("result") == true) json.getJSONObject("result")
                else {
                    val err = json?.optJSONObject("error")
                    throw ServerException(err?.optString("status").orEmpty().ifEmpty { "http_$code" }, err?.optString("message").orEmpty().ifEmpty { "Error del servidor ($code)" })
                }
            } catch (e: java.io.IOException) {
                throw ServerException("network", "Sin conexión con el servidor", network = true)
            } finally { conn.disconnect() }
        }
    }

    private fun state(j: JSONObject) = State(
        runCatching { Entitlements.Plan.valueOf(j.optString("plan", "NONE")) }.getOrDefault(Entitlements.Plan.NONE),
        j.optInt("freeLeft"), j.optInt("monthlyLeft"),
    )

    /** Pide el estado al servidor y lo guarda en la caché local para mostrarlo en pantalla. */
    suspend fun refresh(c: Context): State = state(call(c, "status")).also { Entitlements.applyServer(c, it.plan, it.freeLeft, it.monthlyLeft) }

    /** Reserva una exportación. Si [Auth.ok] es false, no hay cupo y no se debe exportar. */
    suspend fun authorize(c: Context): Auth {
        val j = call(c, "authorize")
        val s = state(j)
        Entitlements.applyServer(c, s.plan, s.freeLeft, s.monthlyLeft)
        return Auth(j.optBoolean("ok"), j.optString("mode"), j.optString("ticket").ifEmpty { null }, s)
    }

    suspend fun complete(c: Context, ticket: String) { call(c, "complete", mapOf("ticket" to ticket)) }
    suspend fun refund(c: Context, ticket: String) { call(c, "refund", mapOf("ticket" to ticket)) }

    /** Código de acceso (para el revisor de Google Play y pruebas): el servidor lo valida y activa el plan Ilimitado un tiempo. */
    suspend fun redeemCode(c: Context, code: String): State =
        state(call(c, "redeemCode", mapOf("code" to code.trim()))).also { Entitlements.applyServer(c, it.plan, it.freeLeft, it.monthlyLeft) }

    /** Compra hecha en Google Play: el servidor la valida con Google y activa el plan. */
    suspend fun verifyPurchase(c: Context, purchaseToken: String): State =
        state(call(c, "verifyPurchase", mapOf("purchaseToken" to purchaseToken))).also { Entitlements.applyServer(c, it.plan, it.freeLeft, it.monthlyLeft) }
}
