plugins {
    alias(libs.plugins.kotlin.jvm)
}

// IdentityService (ED-13): users, sign-in identities and invitations. Uses only the `identity`
// schema (ED-21); ownership changes go through PropertyService's public API.
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
    implementation(project(":server:property"))
}
