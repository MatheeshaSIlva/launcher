// The phone half of the step-0 benchmarks (docs/BENCH_STEP0.md). Its own app (dev.launcher.bench), never shipped; built only
// with -Pbench. Two flavours because LuaJ and Luak share their packages: luaj (LuaJ, Rembulan, kdl4j, glass, deck) and luak.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.launcher.bench"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.launcher.bench"
        // AGSL (RuntimeShader) and the image fence need 33; the S24 is 36.
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "bench"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("launcher-debug.keystore")
            storePassword = "android"
            keyAlias = "launcher"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Not debuggable, as the launcher: a debuggable app runs interpreted until hot, and the numbers would be ART's
        // interpreter's (CLAUDE.md, "The build is not debuggable").
        getByName("debug") {
            isDebuggable = false
        }
    }

    flavorDimensions += "lua"
    productFlavors {
        create("luaj") { dimension = "lua" }
        create("luak") {
            dimension = "lua"
            applicationIdSuffix = ".luak"
        }
    }

    sourceSets {
        getByName("main").java.srcDirs("../bench-shared/src", "../bench-shared/kdl")
        getByName("luaj").java.srcDirs("../bench-shared/luaj", "../bench-shared/rembulan")
        getByName("luak").java.srcDirs("../bench-shared/luaj")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // Compose 1.11 is built with a newer Kotlin than the project's 2.0.21.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    packaging {
        resources.excludes += listOf("META-INF/*.kotlin_module", "META-INF/versions/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Compose for the deck spike: 1.11.4, the first whose accessibility semantics run on the view's own thread
    // (AndroidComposeUiFlags.isViewBasedSemanticsHandlerEnabled, on by default). The launcher's 1.7.5 posts them to the main
    // looper and crashes a Compose view on another thread whenever an accessibility service is on (docs/BENCH_STEP0.md).
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.github.kdl-org:kdl4j:v1.0.1")
    "luajImplementation"("org.luaj:luaj-jse:3.0.1")
    "luajImplementation"("com.jukusoft:rembulan-compiler:1.0.3")
    "luajImplementation"("com.jukusoft:rembulan-stdlib:1.0.3")
    "luakImplementation"("com.soywiz.korlibs.luak:luak-android:4.0.0-alpha-2")
}
