import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lumo.videoeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gradelook.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"
        // Solo ARM de 64 bits (todos los móviles actuales); ahorra ~15 MB de x86_64 de FFmpeg.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Clave de subida a Google Play: los datos están en keystore.properties (fuera del control de versiones; hacer copia).
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (ksProps.getProperty("storeFile") != null) create("release") {
            storeFile = rootProject.file(ksProps.getProperty("storeFile"))
            storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias")
            keyPassword = ksProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        // Depuración: contadores locales (el servidor aún no está desplegado). Para probar contra el servidor real:
        // gradlew installDebug -PuseServer=true
        debug {
            buildConfigField("boolean", "USE_SERVER", project.findProperty("useServer")?.toString() ?: "false")
        }
        release {
            buildConfigField("boolean", "USE_SERVER", "true")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    // FFmpeg con x264/x265 (GPL v3), bibliotecas alineadas a 16 KB (requisito de Play Store).
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")

    // Compras (suscripciones) con Google Play Billing.
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // Play Integrity: comprueba que la app y el móvil son originales antes de hablar con el servidor.
    implementation("com.google.android.play:integrity:1.4.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
}

