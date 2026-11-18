plugins { id("forge.kotlin-library") }

dependencies {
    api(project(":forge-core"))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
