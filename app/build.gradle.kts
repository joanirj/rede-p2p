plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.redep2p"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.redep2p"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4"
    }

    // Chave de teste fixa: permite instalar uma versão nova por cima da anterior.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            // Assinatura v1 + v2: alguns instaladores de fabricantes recusam APK só com v2.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Bibliotecas nativas compactadas: APK menor e compatível com instaladores antigos.
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
    // Gerado pelo GitHub Actions a partir de tun2socks-bridge/ (Go + gomobile).
    implementation(files("libs/tun2socks.aar"))
}
