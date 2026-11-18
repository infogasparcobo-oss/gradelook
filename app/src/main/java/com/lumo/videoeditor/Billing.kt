package com.lumo.videoeditor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** IDs de las suscripciones en Play Console (deben coincidir con backend/functions/logic.js). */
object Products {
    const val PRO = "gradelook_pro_monthly"
    const val UNLIMITED = "gradelook_unlimited_monthly"
    val ALL = listOf(PRO, UNLIMITED)
}

/** Busca la Activity dentro de un Context (los de Compose suelen venir envueltos). */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Compras con Google Play Billing. La app NO activa nada por sí sola: cada compra se manda al servidor, que la valida
 * con Google y es quien activa el plan. Solo se usa con el servidor activo ([Server.enabled]).
 */
class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
    private val onMessage: (String) -> Unit,
) {
    /** Precio con formato local por producto (p. ej. "US$9.00"), vacío mientras no se hayan cargado. */
    var prices by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set

    private var details: Map<String, ProductDetails> = emptyMap()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(PurchasesUpdatedListener { result, purchases ->
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(it) }
                BillingClient.BillingResponseCode.USER_CANCELED -> busy = false
                else -> { busy = false; onMessage("No se pudo completar la compra (${result.debugMessage})") }
            }
        })
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun connect() {
        if (!Server.enabled || client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) scope.launch { loadProducts(); restore() }
            }
            override fun onBillingServiceDisconnected() {}   // se vuelve a conectar al abrir la hoja de planes o la app
        })
    }

    fun disconnect() { if (client.isReady) client.endConnection() }

    private suspend fun loadProducts() {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            Products.ALL.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() },
        ).build()
        val res = withContext(Dispatchers.IO) { client.queryProductDetails(params) }
        val list = res.productDetailsList.orEmpty()
        details = list.associateBy { it.productId }
        prices = list.mapNotNull { d ->
            d.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice?.let { d.productId to it }
        }.toMap()
    }

    /** Al abrir: compras ya hechas (otro móvil, reinstalación) se mandan al servidor para recuperar el plan. */
    suspend fun restore() {
        val res = withContext(Dispatchers.IO) {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build())
        }
        res.purchasesList.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { handle(it, silent = true) }
    }

    fun buy(activity: Activity, productId: String) {
        val d = details[productId] ?: return onMessage("Esta suscripción aún no está disponible")
        val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return onMessage("Esta suscripción aún no está disponible")
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(offer.offerToken).build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product))
            .setObfuscatedAccountId(Server.deviceId(context))
            .build()
        busy = true
        val r = client.launchBillingFlow(activity, flow)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) { busy = false; onMessage("No se pudo abrir Google Play (${r.debugMessage})") }
    }

    private fun handle(p: Purchase, silent: Boolean = false) {
        if (p.purchaseState == Purchase.PurchaseState.PENDING) { busy = false; if (!silent) onMessage("Pago pendiente: se activará cuando Google lo confirme"); return }
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        scope.launch {
            try {
                val s = Server.verifyPurchase(context, p.purchaseToken)
                busy = false
                onChanged()
                if (!silent && s.plan != Entitlements.Plan.NONE) onMessage("¡Listo! Tu plan está activo")
            } catch (e: Server.ServerException) {
                busy = false
                // La compra sigue en Google Play sin confirmar: se reintenta al volver a abrir la app.
                if (!silent) onMessage(if (e.network) "Compra hecha; activaremos tu plan en cuanto haya conexión" else "No se pudo validar la compra: ${e.message}")
            }
        }
    }
}
