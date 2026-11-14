plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Kotlin Multiplatform module shared by the backend and mobile clients (ED-08, ED-15).
// Contains API contracts and presentation-only helpers — no business rules (ED-17).
// iOS targets are added when the iOS project is set up; Android consumes the JVM target until then.
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
