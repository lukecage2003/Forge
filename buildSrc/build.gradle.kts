plugins { `kotlin-dsl` }

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.1.21")
    // Plugins Android dans le même chargeur de classes que le plugin Kotlin (sinon KotlinAndroidTarget échoue).
    implementation("com.android.tools.build:gradle:8.9.3")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.21")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.21-2.0.1")
}
