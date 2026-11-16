plugins {
    alias(libs.plugins.kotlin.jvm)
}

// FinanceService (ED-13): property financial accounts, imported transactions and the ledger.
// Uses only the `finance` schema (ED-21, ED-30); properties come from PropertyService's public API.
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
    implementation(project(":server:property"))
    implementation(libs.commons.csv)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
