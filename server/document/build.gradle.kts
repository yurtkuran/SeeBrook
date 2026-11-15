plugins {
    alias(libs.plugins.kotlin.jvm)
}

// DocumentService (ED-13): HOA document library — categories, documents and their PDF versions.
// Uses only the `document` schema (ED-21, ED-30); files live in private object storage (ED-38).
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
    implementation(project(":server:property"))
    implementation(libs.pdfbox)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
