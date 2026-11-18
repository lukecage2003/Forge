plugins { id("forge.kotlin-library") }

dependencies {
    api(project(":forge-core"))
    api(project(":forge-security"))
    api(project(":forge-research"))
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    compileOnly(libs.jspecify)
    testImplementation(libs.okhttp.mockwebserver)
}
