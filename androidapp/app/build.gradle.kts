plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.yusukeiwaki.android_apk_instant_deploy"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yusukeiwaki.android_apk_instant_deploy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
