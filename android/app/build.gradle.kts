import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun quoted(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val releaseKeystore = rootProject.file("release.keystore")
val releaseProperties = Properties().apply {
    val file = rootProject.file("release-signing.properties")
    if (file.isFile) file.inputStream().use(::load)
}
val hasReleaseSigning = releaseKeystore.isFile &&
    (System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: releaseProperties.getProperty("storePassword")) != null &&
    (System.getenv("ANDROID_KEY_ALIAS") ?: releaseProperties.getProperty("keyAlias")) != null &&
    (System.getenv("ANDROID_KEY_PASSWORD") ?: releaseProperties.getProperty("keyPassword")) != null

android {
    namespace = "br.com.iptvcaseiro"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.iptvcaseiro"
        minSdk = 26
        targetSdk = 36
        versionCode = (System.getenv("VERSION_CODE") ?: "10000").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"

        buildConfigField(
            "String",
            "GITHUB_REPOSITORY",
            quoted(System.getenv("GITHUB_REPOSITORY") ?: "SEU_USUARIO/iptv-caseiro"),
        )
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: releaseProperties.getProperty("storePassword")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: releaseProperties.getProperty("keyAlias")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 chegou a permanecer indefinidamente em algumas máquinas Windows.
            // O APK é pequeno o bastante para priorizar uma build confiável.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    // Mantém compile/target SDK 36 conforme o requisito do aplicativo.
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("androidx.room:room-runtime:2.8.4")
    annotationProcessor("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
