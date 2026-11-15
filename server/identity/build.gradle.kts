plugins {
    alias(libs.plugins.kotlin.jvm)
}

// IdentityService (ED-13): users, sign-in identities, roles, invitations and owner imports. Uses only
// the `identity` schema (ED-21, ED-30); property changes go through PropertyService's public API.
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
    implementation(project(":server:tenant"))
    implementation(project(":server:property"))
    implementation(libs.commons.csv)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
