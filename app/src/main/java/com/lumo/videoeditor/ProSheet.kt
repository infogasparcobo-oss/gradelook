@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lumo.videoeditor

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Hoja de planes. Sin Google Play Billing conectado, los botones de compra están desactivados y los planes
 * solo se pueden probar en compilaciones de depuración.
 */
@Composable
fun ProSheet(
    plan: Entitlements.Plan,
    monthlyLeft: Int,
    freeLeft: Int,
    canTest: Boolean,
    onSetPlan: (Entitlements.Plan) -> Unit,
    onDismiss: () -> Unit,
    /** Precio con formato local por producto, tal como lo da Google Play (vacío si aún no hay). */
    prices: Map<String, String> = emptyMap(),
    busy: Boolean = false,
    onBuy: (String) -> Unit = {},
    /** Canjea un código de acceso; devuelve null si funcionó o el mensaje de error para mostrar. */
    onRedeem: suspend (String) -> String? = { "No disponible" },
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var showCode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var codeMsg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var codeBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Botón de compra: solo si Google Play ya devolvió el precio de ese producto.
    @Composable
    fun BuyButton(productId: String, label: String, current: Boolean) {
        val price = prices[productId]
        when {
            current -> Text("Plan activo", color = Palette.accent)
            price != null -> Button(onClick = { onBuy(productId) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Procesando…" else "$label · $price al mes")
            }
            else -> Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Suscripción próximamente") }
        }
    }
    val features = listOf(
        "Exportar en 4K con la máxima calidad",
        "Todos los filtros (paisajes, atardeceres, bodas, cumpleaños, deportivos, drones y cine)",
        "Guardar tus propios filtros sin límite",
        "Recortar, reencuadrar a 9:16 e importar LUTs",
    )
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 22.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Planes de Gradelook", style = MaterialTheme.typography.titleLarge, color = Palette.accent)

            // Estado actual
            Column(Modifier.fillMaxWidth().border(1.dp, Palette.border, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text("Tu plan", fontSize = 12.sp, color = Palette.textSoft)
                Text(
                    when (plan) {
                        Entitlements.Plan.NONE -> "Gratis"
                        Entitlements.Plan.STANDARD -> "Pro · ${Entitlements.STANDARD_PRICE_USD} USD al mes"
                        Entitlements.Plan.UNLIMITED -> "Ilimitado · ${Entitlements.UNLIMITED_PRICE_USD} USD al mes"
                    },
                    fontSize = 20.sp, color = Palette.text,
                )
                Text(
                    when (plan) {
                        Entitlements.Plan.NONE ->
                            if (freeLeft > 0) "Te quedan $freeLeft de ${Entitlements.FREE_EXPORTS} exportaciones gratis (en total en este móvil)"
                            else "Ya usaste las ${Entitlements.FREE_EXPORTS} exportaciones gratis de este móvil"
                        Entitlements.Plan.STANDARD -> "Te quedan $monthlyLeft de ${Entitlements.STANDARD_MONTHLY_EXPORTS} exportaciones este mes"
                        Entitlements.Plan.UNLIMITED -> "Exportaciones sin límite"
                    },
                    fontSize = 12.sp, color = Palette.textSoft,
                )
            }

            Text(
                "Sin plan puedes exportar ${Entitlements.FREE_EXPORTS} videos en total. Después la exportación se bloquea hasta que elijas un plan.",
                fontSize = 13.sp, color = Palette.textSoft,
            )

            // Pro
            Column(Modifier.fillMaxWidth().border(1.dp, Palette.accent, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pro · ${Entitlements.STANDARD_PRICE_USD} USD al mes", fontSize = 16.sp, color = Palette.accent)
                Text("✓  ${Entitlements.STANDARD_MONTHLY_EXPORTS} exportaciones cada mes", fontSize = 14.sp, color = Palette.text)
                features.forEach { Text("✓  $it", fontSize = 14.sp, color = Palette.text) }
                BuyButton(Products.PRO, "Suscribirme a Pro", plan == Entitlements.Plan.STANDARD)
            }

            // Ilimitado
            Column(Modifier.fillMaxWidth().border(1.dp, Palette.border, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ilimitado · ${Entitlements.UNLIMITED_PRICE_USD} USD al mes", fontSize = 16.sp, color = Palette.text)
                Text("✓  Exportaciones sin límite", fontSize = 14.sp, color = Palette.text)
                features.forEach { Text("✓  $it", fontSize = 14.sp, color = Palette.text) }
                BuyButton(Products.UNLIMITED, "Suscribirme a Ilimitado", plan == Entitlements.Plan.UNLIMITED)
            }

            Text(
                if (prices.isNotEmpty()) "Las suscripciones se cobran y se cancelan desde Google Play (Pagos y suscripciones)."
                else "Los cobros se activarán cuando la app esté publicada en Google Play.",
                fontSize = 12.sp, color = Palette.textSoft,
            )

            // Código de acceso (revisores de Google Play, pruebas): discreto, al final.
            if (!showCode) {
                androidx.compose.material3.TextButton(onClick = { showCode = true }) { Text("Tengo un código de acceso", fontSize = 13.sp, color = Palette.textSoft) }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = code, onValueChange = { code = it.take(40); codeMsg = null }, singleLine = true,
                        label = { Text("Código de acceso") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            codeBusy = true; codeMsg = null
                            scope.launch { codeMsg = onRedeem(code) ?: "✓ Código aceptado: plan activado"; codeBusy = false }
                        },
                        enabled = code.trim().length >= 8 && !codeBusy, modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (codeBusy) "Comprobando…" else "Canjear código") }
                    codeMsg?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("✓")) Palette.accent else Palette.textSoft) }
                }
            }

            PrivacyLink()

            if (canTest) {
                Entitlements.Plan.entries.forEach { p ->
                    OutlinedButton(onClick = { onSetPlan(p) }, enabled = p != plan, modifier = Modifier.fillMaxWidth()) {
                        Text("[Prueba] Plan: " + when (p) {
                            Entitlements.Plan.NONE -> "Gratis"
                            Entitlements.Plan.STANDARD -> "Pro 9 USD"
                            Entitlements.Plan.UNLIMITED -> "Ilimitado 19 USD"
                        })
                    }
                }
            }
        }
    }
}
