// Standalone build so the pure-Kotlin timer core can be unit-tested without the Android SDK:
//   cd native/android/timer-core && gradle test
// When built as part of native/android this file is ignored.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { kotlin("jvm") version "2.1.0" }
}
rootProject.name = "timer-core"
