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
        versionCode = 5
        versionName = "1.4-pause"
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

// The app itself has no dependencies; it uses only the Android framework.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
