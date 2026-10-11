plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.ejarmand.looper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ejarmand.looper"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Don't embed the (Google-encrypted) dependency report in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Intentionally no dependencies: the app uses only the Android framework.
