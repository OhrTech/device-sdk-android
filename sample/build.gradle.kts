plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.ohrtech.device.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.ohrtech.device.sample"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1"
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
    implementation(project(":device-sdk"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
