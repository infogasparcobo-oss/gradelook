package com.lumo.videoeditor

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding

/** Enlaces legales que Google Play exige mostrar también dentro de la app. */
object Legal {
    const val PRIVACY_URL = "https://gradelook-88444.web.app/privacy"
}

/** «Política de privacidad»: abre la página en el navegador. */
@Composable
fun PrivacyLink(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = Palette.textSoft) {
    val uri = LocalUriHandler.current
    Text(
        "Política de privacidad", fontSize = 12.sp, color = color, textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable { runCatching { uri.openUri(Legal.PRIVACY_URL) } }.padding(vertical = 8.dp, horizontal = 4.dp),
    )
}
