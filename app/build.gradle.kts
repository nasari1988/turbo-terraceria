plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nasari.gbaia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nasari.gbaia"
        minSdk = 31 // NPU (LiteRT + QNN) pide Android 12+; el Poco F7 trae Android 15
        targetSdk = 35
        versionCode = 21
        versionName = "0.21"
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

    // El modelo .tflite se lee mapeado en memoria: no comprimirlo
    androidResources {
        noCompress += "tflite"
    }

    // Las librerías de la NPU de Qualcomm (skel) deben quedar como archivos en nativeLibraryDir
    packaging {
        jniLibs {
            useLegacyPackaging = true
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

dependencies {
    // LiteRT (antes TensorFlow Lite), API Interpreter
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    // NPU Hexagon de Qualcomm (backend HTP) vía el delegado QNN
    implementation("com.qualcomm.qti:qnn-runtime:2.50.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.50.0")
}
