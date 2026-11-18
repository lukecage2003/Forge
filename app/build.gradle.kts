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
        versionCode = 1
        versionName = "0.1.0"
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
}

dependencies {
    implementation(project(":forge-reasoning"))
    implementation(project(":forge-voice"))
    implementation(project(":forge-editorial"))
    implementation(project(":forge-evaluation"))
    implementation(project(":forge-research"))
    implementation(project(":forge-actions"))
    implementation(project(":forge-academy"))

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
}
