plugins { id("forge.kotlin-library") }

dependencies {
    api(project(":forge-core"))
    api(project(":forge-models"))
    api(project(":forge-memory"))
    api(project(":forge-security"))
    api(project(":forge-editorial"))
}
