plugins {
    alias(libs.plugins.kotlin.jvm)
}

// TenantService (ED-13): HOAs. Uses only the `tenant` schema (ED-21, ED-30).
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
}
