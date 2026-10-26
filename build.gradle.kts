plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.soundpad"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.soundpad"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.13.1")
}
