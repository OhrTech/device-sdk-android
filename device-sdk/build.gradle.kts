plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
    signing
}

group = "net.ohrtech"
// Releases set it from the tag (-PsdkVersion=1.2.3).
version = providers.gradleProperty("sdkVersion").getOrElse("0.1.0")

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
        // Interface methods with bodies (OhrWifiView.Listener) are Java default methods: Java implements only what it needs.
        freeCompilerArgs += "-Xjvm-default=all"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// net.ohrtech:device-sdk, as Maven Central wants it: aar, sources, javadoc, POM, Gradle module, signatures.
// `./gradlew :device-sdk:publishReleasePublicationToStagingRepository` writes build/staging-repo, which
// the release workflow zips and uploads to the Central Portal.
publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "device-sdk"
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("OhrTech Device SDK")
                description.set("What an app on an OhrTech device can ask the OhrTech Agent for: the device, its updates, " +
                    "Wi-Fi, network, kiosk mode, power, settings, KEM keys in the TEE, and end-to-end push.")
                url.set("https://github.com/OhrTech/device-sdk-android")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("ohrtech")
                        name.set("OhrTech")
                        url.set("https://ohrtech.net")
                    }
                }
                scm {
                    url.set("https://github.com/OhrTech/device-sdk-android")
                    connection.set("scm:git:https://github.com/OhrTech/device-sdk-android.git")
                    developerConnection.set("scm:git:ssh://git@github.com/OhrTech/device-sdk-android.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-repo"))
        }
    }
}

// Signed only when a key is given (SIGNING_KEY: an ASCII-armoured private key; SIGNING_PASSWORD).
providers.environmentVariable("SIGNING_KEY").orNull?.let { key ->
    signing {
        useInMemoryPgpKeys(key, providers.environmentVariable("SIGNING_PASSWORD").getOrElse(""))
        sign(publishing.publications["release"])
    }
}

dependencies {
    // api: the public API returns Flow and has suspend functions.
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.annotation:annotation:1.9.0")
    // The application context at app start, so the areas need no setup call.
    implementation("androidx.startup:startup-runtime:1.1.1")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM tests (android.jar's is stubs only).
    testImplementation("org.json:json:20240303")
}

// -PtestJdk=<JDK 24+ home> runs the JVM tests there, so the ML-DSA-65 checks run too (JDK 21 has none).
providers.gradleProperty("testJdk").orNull?.let { jdk ->
    tasks.withType<Test>().configureEach { executable = "$jdk/bin/java" }
}
