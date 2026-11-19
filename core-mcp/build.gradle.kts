plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSpring)
    // Also turns on -java-parameters, which MCP tool parameter names depend on.
    alias(libs.plugins.springBoot)
    alias(libs.plugins.exposed)
}

val basePackage = providers.gradleProperty("basePackage").get()

// The Exposed generator only accepts images whose name starts with "postgres"; plain PostgreSQL 18 matches compose.
val migrationGeneratorImage = "postgres:18"

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.ai.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.exposed.spring.boot.starter)
    implementation(libs.exposed.java.time)
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    // No spring-boot-docker-compose: its connection details (database `backend`) would override the `core` URL.

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit.junit6)
    testImplementation(libs.exposed.migration.jdbc)
}

exposed {
    migrations {
        tablesPackage.set("$basePackage.coremcp.persistence.tables")
        testContainersImageName.set(migrationGeneratorImage)
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
    }
}
