plugins {
    alias(libs.plugins.kotlin.jvm)
}

// FinanceService (ED-13): property financial accounts, imported transactions and the ledger.
// Uses only the `finance` schema (ED-21, ED-30); properties, ownership and HOA time zones come from
// PropertyService's and TenantService's public APIs.
dependencies {
    implementation(project(":server:db"))
    api(project(":server:platform"))
    implementation(project(":server:property"))
    implementation(project(":server:tenant"))
    implementation(libs.commons.csv)
    // Reads the HOA register workbook (.xlsx) for convert-register (ED-42).
    implementation(libs.poi.ooxml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.assertions)
    testRuntimeOnly(libs.junit.platform.launcher)
}
