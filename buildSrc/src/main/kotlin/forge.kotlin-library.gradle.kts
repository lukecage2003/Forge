// Convention commune à tous les modules Kotlin/JVM purs de Forge.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Versions alignées sur gradle/libs.versions.toml.
dependencies {
    "api"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    "api"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    "testImplementation"(kotlin("test"))
    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.withType<Test> { useJUnitPlatform() }
