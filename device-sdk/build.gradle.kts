plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "net.ohrtech"
version = "0.1.0"

android {
    namespace = "net.ohrtech.device"
    compileSdk = 34

    defaultConfig {
        // OhrTech devices run Android 17; 33 is the oldest the Agent's SDK service runs on.
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.annotation:annotation:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar's is stubs only).
    testImplementation("org.json:json:20240303")
}

// -PtestJdk=<JDK 24+ home> runs the JVM tests there, so the ML-DSA-65 checks run too (JDK 21 has none).
providers.gradleProperty("testJdk").orNull?.let { jdk ->
    tasks.withType<Test>().configureEach { executable = "$jdk/bin/java" }
}
