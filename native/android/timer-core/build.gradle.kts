plugins {
    kotlin("jvm")
}

repositories { mavenCentral() }

dependencies {
    testImplementation(kotlin("test"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

tasks.test { useJUnitPlatform() }
