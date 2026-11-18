plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ai.forge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.forge.app"
        // Galaxy S25 : Android 15 (One UI 7) et ultérieur. minSdk 31 conserve une marge raisonnable.
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
        // Galaxy S25 = arm64-v8a. Ajouter « x86_64 » pour un émulateur de développement.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
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
    // Autorisation Google (OAuth, portée minimale) pour Google Drive.
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    // OCR sur l'appareil (alphabet latin, modèle embarqué : fonctionne hors-ligne).
    implementation(libs.mlkit.text)

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
