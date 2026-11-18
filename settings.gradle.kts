pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Forge"

// Cœur multiplateforme (JVM pur) : orchestrateur, mémoire, preuves, éditorial, voix (logique).
include(":forge-core")

// Application Android : incluse seulement si un SDK Android est configuré,
// afin que le cœur reste compilable et testable sur n'importe quelle machine.
val sdkConfigured = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (sdkConfigured) {
    include(":app")
} else {
    logger.lifecycle("FORGE: aucun SDK Android détecté — module :app ignoré (seul :forge-core est construit).")
}
