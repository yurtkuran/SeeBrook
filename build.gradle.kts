plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.jooq.codegen) apply false
}

// Every JVM module builds with Eclipse Temurin 21 (ED-20).
subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.ADOPTIUM)
            }
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// ED-13/ED-21: a service module may use only its own schema's generated jOOQ classes; other
// services are reached through their public Kotlin API. `server:app` is the composition root and
// may not touch tables directly, except its development tooling (devtools/).
val schemaOwnership = mapOf(
    "server/platform" to setOf("audit"),
    "server/tenant" to setOf("tenant"),
    "server/property" to setOf("property"),
    "server/identity" to setOf("identity"),
    "server/app" to emptySet(),
)
val checkSchemaOwnership by tasks.registering {
    group = "verification"
    description = "Fails if a backend module uses another service's database schema (ED-13, ED-21)."
    val sources = schemaOwnership.mapValues { (module, _) ->
        layout.projectDirectory.dir("$module/src/main/kotlin").asFileTree.matching { include("**/*.kt") }
    }
    sources.values.forEach { inputs.files(it) }
    val rootDir = layout.projectDirectory.asFile
    doLast {
        val generated = Regex("""cc\.seebrook\.db\.generated\.([a-z_]+)\.""")
        val violations = sources.flatMap { (module, files) ->
            val allowed = schemaOwnership.getValue(module)
            files.files
                .filterNot { module == "server/app" && it.invariantSeparatorsPath.contains("/devtools/") }
                .flatMap { file ->
                    generated.findAll(file.readText()).map { it.groupValues[1] }.distinct()
                        .filterNot { it in allowed }
                        .map { schema -> "${file.relativeTo(rootDir)} uses schema '$schema'" }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Schema ownership violations (ED-21):\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkSchemaOwnership) }
}
