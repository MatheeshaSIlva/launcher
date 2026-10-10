plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.launcher.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.launcher.app"
        minSdk = 29
        // 34 matches the probes, so everything they proved behaves the same here. Raise to 35+ before store release.
        targetSdk = 34
        versionCode = 1
        // CI passes -PbuildSha=<full sha>; the app logs "BUILD <versionName>" so a test can be matched to its commit.
        versionName = (project.findProperty("buildSha") as String?)?.take(7) ?: "local"
    }

    signingConfigs {
        getByName("debug") {
            // Committed on purpose: CI and local builds sign with the same key, so they install over each other.
            storeFile = rootProject.file("launcher-debug.keystore")
            storePassword = "android"
            keyAlias = "launcher"
            keyPassword = "android"
        }
    }

    buildFeatures {
        aidl = true
        compose = true
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
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku")
    implementation("dev.rikka.shizuku:provider:$shizuku")
    // The settings app (docs/PLAN_SETTINGS.md): Compose's layout, state, text and animation; no Material components (every
    // surface is drawn by our renderer from the theme's tokens).
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Logic tests (layout model) that run on the build machine: ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
    // The real org.json for logic tests (Android's copy is a stub on the build machine).
    testImplementation("org.json:json:20240303")
}
