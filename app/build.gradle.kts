plugins {
    id("com.android.application")
}

// Release signing comes from the environment (set by the release workflow from repo secrets).
// Without it, release builds come out unsigned and local debug builds are unaffected.
val releaseKeystore: String? = System.getenv("LOOPER_KEYSTORE")

android {
    namespace = "io.github.ejarmand.looper"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ejarmand.looper"
        minSdk = 26
        targetSdk = 36
        // The release workflow passes these from the git tag and commit count.
        versionCode = (findProperty("looper.versionCode") as String?)?.toInt() ?: 5
        versionName = findProperty("looper.versionName") as String? ?: "1.4.0-dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LOOPER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LOOPER_KEY_ALIAS")
                keyPassword = System.getenv("LOOPER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
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
