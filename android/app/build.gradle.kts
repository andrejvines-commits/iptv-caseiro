import java.util.Properties

plugins {
    id("com.android.application")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
}
