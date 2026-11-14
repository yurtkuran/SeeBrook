plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// Single deployable backend (ED-13): HTTP API, admin CLI commands and development tooling.
application {
    mainClass.set("cc.seebrook.app.MainKt")
    applicationName = "seebrook"
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":server:db"))
    implementation(project(":server:platform"))
    implementation(project(":server:property"))
    implementation(project(":server:identity"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.nimbus.jose.jwt)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<JavaExec>("run") {
    environment("SEEBROOK_ENV", System.getenv("SEEBROOK_ENV") ?: "development")
}
