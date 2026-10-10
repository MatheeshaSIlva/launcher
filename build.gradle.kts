plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // The settings app (docs/PLAN_SETTINGS.md) is Jetpack Compose: the compiler plugin of this Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
