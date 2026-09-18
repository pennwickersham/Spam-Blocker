// Top-level build file. Plugin versions are declared here and applied in app/.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 moved the Compose compiler out of the Kotlin plugin and into
    // its own plugin. Its version must track the Kotlin version exactly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
