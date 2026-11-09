plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.elora.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elora.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.2.0-alpha1"
        buildConfigField("String", "ELORA_SIGNALING_URL", "\"wss://elora-signaling.padxiomi91.workers.dev\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }


    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
}
