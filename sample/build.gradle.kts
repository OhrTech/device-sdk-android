plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.ohrtech.device.sample"
    compileSdk = 34

    defaultConfig {
        // -PsampleAppId / -PsampleVersion: run the sample as another hub app (device checks).
        applicationId = providers.gradleProperty("sampleAppId").getOrElse("net.ohrtech.device.sample")
        minSdk = 33
        targetSdk = 34
        versionCode = providers.gradleProperty("sampleVersion").getOrElse("1").toInt()
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
