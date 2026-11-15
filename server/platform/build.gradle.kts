plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Cross-cutting infrastructure shared by all backend services: configuration, database access,
// audit, background jobs, the email gateway and authorization context (ED-13).
dependencies {
    api(libs.jooq)
    api(libs.hikari)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.angus.mail)
    implementation(platform(libs.aws.sdk.bom))
    // S3 over the JDK's HTTP client; the Netty and Apache clients are left out (Ktor brings its own Netty).
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(project(":server:db"))
    api(libs.kotlinx.serialization.json)
    runtimeOnly(libs.postgresql)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
