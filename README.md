# Gradelook

Editor de video enfocado en el color para Android (`com.gradelook.app`).
Android video editor focused on color grading.

## Licencia / License

Este código se publica bajo la **GNU General Public License v3.0** (ver `LICENSE`), porque la app
distribuye FFmpeg con x264 (GPL). Quien reciba la app puede obtener aquí el código fuente
correspondiente a la versión publicada.

This source code is released under the **GNU GPL v3.0** (see `LICENSE`) because the app ships
FFmpeg with x264 (GPL). The source corresponding to the published app version is in this repository.

## Componentes de terceros / Third-party components

- FFmpegKit (full-gpl) con FFmpeg y x264 — GPL v3
- AndroidX Media3 (ExoPlayer, Transformer, Effect) — Apache 2.0
- Jetpack Compose / AndroidX — Apache 2.0
- Google Play Billing y Play Integrity — términos de Google

## Compilar / Build

Requisitos: JDK 17, Android SDK 36.

```
./gradlew assembleDebug
```

Para firmar una versión release hace falta tu propia clave: crea `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). No se incluye en el repositorio.
Las versiones debug usan contadores locales; la versión release habla con un servidor privado
(las exportaciones y suscripciones), que no forma parte de este repositorio.

## Privacidad / Privacy

https://gradelook-88444.web.app/privacy
