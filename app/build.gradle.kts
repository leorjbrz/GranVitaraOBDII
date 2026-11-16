import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vitaraobd.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vitaraobd.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-esboco"
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Mapa OpenStreetMap, sem chave de API.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
