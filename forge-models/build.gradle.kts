plugins { id("forge.kotlin-library") }

dependencies {
    api(project(":forge-core"))
    api(project(":forge-security"))
    implementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
}
