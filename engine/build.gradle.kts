plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Not for composables (the engine has none yet): the Compose compiler gives every class it compiles its stability
    // metadata ($stable, @StabilityInferred), which the app's composables read to decide when to skip. Without it, a class
    // moved here from the app counts as unstable there, and "a move is a move" would not hold at the bytecode level
    // (E1 slice M1, D-071). Step 2 brings Compose into the engine anyway.
    id("org.jetbrains.kotlin.plugin.compose")
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

dependencies {
    // For the Compose compiler only (see plugins): compile-time, so nothing new reaches the APK, whose Compose comes from
    // the app's own declarations of the same BOM.
    compileOnly(platform("androidx.compose:compose-bom:2024.10.01"))
    compileOnly("androidx.compose.runtime:runtime")
    // Already in the APK through the app's Compose (owner, 2026-11-05: declared here once the engine's own code needs
    // them, at the versions the APK ships). IconSets parses symbol paths with Compose's PathParser.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui-graphics")
    // GoogleFontCatalog's previews and HomeBridge's suspend calls: pinned to the version :app:dependencies showed the APK
    // resolving before it was declared here (1.7.3).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
