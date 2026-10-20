import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ribau.baixador"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ribau.baixador"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
    val signingFile = rootProject.file(".signing/keystore.properties")
    if (signingFile.exists()) {
        val key = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs {
            create("personalRelease") {
                storeFile = rootProject.file(".signing/baixador-release.jks")
                storePassword = key.getProperty("storePassword")
                keyAlias = "baixador"
                keyPassword = key.getProperty("storePassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("personalRelease")
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
