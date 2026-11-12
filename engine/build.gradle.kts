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
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "dev.launcher.engine.adb.AdbProofInstrumentation"
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=none" }
        }
    }

    // The device proofs read the app's motion presets, themes and Inter (the island's roll on the real preset, T-012).
    sourceSets["androidTest"].assets.srcDir("../app/src/main/assets")

    lint {
        // The app's baseline entries for files that move here are carried over with them, slice by slice.
        baseline = file("lint-baseline.xml")
    }

    buildFeatures {
        // The Shizuku service's interfaces move here in E1 (slice M2).
        aidl = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 2.2 refuses kotlinOptions {} (T-002).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // T-001 / D-075: native SPAKE2 and the self-signed certificate builder. All prefab ABIs retained.
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    // For the Compose compiler only (see plugins): compile-time, so nothing new reaches the APK, whose Compose comes from
    // the app's own declarations of the same BOM.
    compileOnly(platform("androidx.compose:compose-bom:2026.06.01"))
    compileOnly("androidx.compose.runtime:runtime")
    // Already in the APK through the app's Compose (owner, 2026-11-05: declared here once the engine's own code needs
    // them, at the versions the APK ships). IconSets parses symbol paths with Compose's PathParser.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui-graphics")
    // The engine's Compose host (E2 slice 5b: Layout, draw nodes, semantics), the same artifact the app ships.
    implementation("androidx.compose.ui:ui")
    // GoogleFontCatalog's previews and HomeBridge's suspend calls: the version the APK resolves (1.7.3 until T-002; Compose
    // 1.11.4's ui asks 1.9.0, so 1.9.0 since).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // ComponentView's lifecycle and saved-state owner on the shade's thread (E2 slice 6, T-004): already in the APK through
    // Compose ui, declared at the versions it resolves (dependencyInsight, 2026-11-07: lifecycle 2.9.4, savedstate 1.3.2).
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.2")
    // ShizukuLink (binding the shell service) imports Shizuku's API: already in the APK at this version, through the app,
    // which keeps api and provider (its manifest's ShizukuProvider) (E1 slice M3, D-071).
    implementation("dev.rikka.shizuku:api:13.1.5")
    // The engine's own logic tests (the KDL parser first: E2), run by `testDebugUnitTest` with the app's.
    testImplementation("junit:junit:4.13.2")
    // The Compose compiler plugin (above) refuses to compile without the runtime on the class path, tests included:
    // the same compile-only runtime, never on the test run nor in the APK.
    testCompileOnly(platform("androidx.compose:compose-bom:2026.06.01"))
    testCompileOnly("androidx.compose.runtime:runtime")
}
