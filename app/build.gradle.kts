plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nasari.gbaia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nasari.gbaia"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
        ndk {
            abiFilters += "arm64-v8a" // Poco F7
        }
    }

    signingConfigs {
        getByName("debug") {
            // Llave fija: cada APK nuevo se instala encima del anterior sin borrar partidas.
            storeFile = rootProject.file("ci/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Se compila en modo release (núcleo optimizado) pero firmado con la llave de prueba.
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
