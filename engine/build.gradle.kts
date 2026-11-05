plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The engine (docs/ENGINE_SPEC.md): the launcher's platform layer, lifted out of the app in milestone E1
// (docs/E1_INVENTORY.md) with no behaviour change. Its files keep their Kotlin and AIDL packages (dev.launcher.app.*);
// only the Gradle namespace is the engine's own, so its R class is dev.launcher.engine.R.
android {
    namespace = "dev.launcher.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    lint {
        // The app's baseline entries for files that move here are carried over with them, slice by slice.
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        // The Shizuku service's interfaces move here in E1 (slice M2).
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
