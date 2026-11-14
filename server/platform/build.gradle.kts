plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Cross-cutting infrastructure shared by all backend services: configuration, database access,
// and later audit, outbox and authorization context (ED-13).
dependencies {
    api(libs.jooq)
    api(libs.hikari)
    implementation(libs.nimbus.jose.jwt)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
