plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jooq.codegen)
}

// Owns the database schema: Flyway migrations and the jOOQ classes generated from them (ED-21).
dependencies {
    api(libs.jooq)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)

    jooqCodegen(libs.jooq.meta.extensions)
}

val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedDir = layout.buildDirectory.dir("generated-sources/jooq")

// Generate type-safe jOOQ classes by parsing the Flyway scripts, so no database is needed at build time.
jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.JavaGenerator"
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property {
                        key = "scripts"
                        value = migrationsDir.asFile.absolutePath + "/*.sql"
                    }
                    property {
                        key = "sort"
                        value = "flyway"
                    }
                    property {
                        key = "defaultNameCase"
                        value = "lower"
                    }
                    property {
                        key = "parseIgnoreComments"
                        value = "true"
                    }
                }
            }
            generate {
                isJavaTimeTypes = true
                isRecords = true
                isPojos = false
                isDaos = false
            }
            target {
                packageName = "cc.seebrook.db.generated"
                directory = generatedDir.get().asFile.absolutePath
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.dir(migrationsDir)
    outputs.dir(generatedDir)
}

sourceSets.main {
    java.srcDir(generatedDir)
}

tasks.named("compileJava") { dependsOn("jooqCodegen") }
tasks.named("compileKotlin") { dependsOn("jooqCodegen") }
