plugins {
    alias(libs.plugins.kotlin.jvm)
}

// PropertyService (ED-13): properties and ownership. Uses only the `property` schema (ED-21).
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
}
