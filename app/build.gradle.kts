plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ai.forge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.forge.app"
        // Galaxy S25 : Android 15 (One UI 7) et ultérieur. minSdk 31 conserve une marge raisonnable.
        minSdk = 31
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.1"
        // Galaxy S25 = arm64-v8a. Ajouter « x86_64 » pour un émulateur de développement.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            // Version optimisée (non débogable : Compose y est bien plus fluide). R8 désactivé pour
            // éviter tout risque avec les bibliothèques natives (LiteRT-LM, MediaPipe, ML Kit).
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":forge-reasoning"))
    implementation(project(":forge-voice"))
    implementation(project(":forge-editorial"))
    implementation(project(":forge-evaluation"))
    implementation(project(":forge-research"))
    implementation(project(":forge-actions"))
    implementation(project(":forge-academy"))
    implementation(project(":forge-browser"))
    implementation(project(":forge-documents"))
    implementation(project(":forge-market"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Inférence locale sur l'appareil (modèles Gemma au format .task).
    implementation(libs.mediapipe.genai)
    // LiteRT-LM : moteur de Google pour Gemma 4 sur GPU (plus rapide que MediaPipe).
    implementation(libs.litertlm)
    // Voix neuronale hors-ligne (Pocket TTS) via la tâche MediaPipe TextToSpeech.
    implementation(libs.mediapipe.audio)
    // Autorisation Google (OAuth, portée minimale) pour Google Drive.
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    // OCR sur l'appareil (alphabet latin, modèle embarqué : fonctionne hors-ligne).
    implementation(libs.mlkit.text)
    // Tâches de fond : indexation de la bibliothèque, briefing du matin.
    implementation(libs.work.runtime)
    // Mot d'appel « Forge » hors-ligne (Vosk, Apache 2.0) et détection de l'app au premier plan.
    implementation(libs.vosk.android)
    // Reconnaissance vocale hors-ligne (sherpa-onnx, Apache 2.0) : AAR officiel réduit à arm64-v8a.
    implementation(files("libs/sherpa-onnx-1.12.15-arm64.aar"))
    implementation(libs.lifecycle.process)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

// Robolectric : l'image Android est résolue par Gradle (cache, proxy, reproductible) plutôt que
// téléchargée à l'exécution, puis fournie en mode hors-ligne.
val robolectricRuntime: Configuration by configurations.creating
dependencies { robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7") }
val robolectricDir = layout.buildDirectory.dir("robolectric-jars")
val prepareRobolectric by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectric)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDir.get().asFile.absolutePath)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
