import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.yusukeiwaki.android_apk_instant_deploy"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yusukeiwaki.android_apk_instant_deploy"
        applicationIdSuffix = ".alpha"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:34.7.0"))
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
