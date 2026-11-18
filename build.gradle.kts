// Plugins Kotlin JVM/Android/sérialisation : fournis par buildSrc (convention forge.kotlin-library).
// Plugins propres à Android, déclarés ici pour le module :app (chargés seulement s'il est inclus).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
