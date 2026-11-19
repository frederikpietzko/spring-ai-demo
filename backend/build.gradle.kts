plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.springBoot)
    alias(libs.plugins.exposed)
}

val basePackage = providers.gradleProperty("basePackage").get()

// The Exposed generator only accepts images whose name starts with "postgres", so it cannot use the pgvector image
// from compose.yaml. Plain PostgreSQL 18 is enough for generation while no vector column exists.
val migrationGeneratorImage = "postgres:18"

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.spring.ai.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.exposed.spring.boot.starter)
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("tools.jackson.module:jackson-module-kotlin")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly(platform(libs.spring.boot.dependencies))
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Declared for the tests that follow; this change commits none.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
}

exposed {
    migrations {
        tablesPackage.set("$basePackage.backend.persistence.tables")
        testContainersImageName.set(migrationGeneratorImage)
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
    }
}
