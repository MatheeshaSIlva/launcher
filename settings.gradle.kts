pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Launcher"
include(":app")

// The step-0 benchmarks (docs/BENCH_STEP0.md): built only with -Pbench, so CI, scripts/check.sh and the launcher's APK never
// see them or their libraries. kdl4j is published on JitPack only.
if (providers.gradleProperty("bench").isPresent) {
    include(":bench", ":bench-desktop")
    dependencyResolutionManagement.repositories {
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.kdl-org") }
        }
    }
}
