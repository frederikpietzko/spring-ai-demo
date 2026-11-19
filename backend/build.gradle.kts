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
    implementation(project(":chat-protocol"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.exposed.spring.boot.starter)
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Lets Spring MVC return a Kotlin Flow (the Flux-to-Flow bridge of the chat endpoint); version from the Boot BOM.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly(platform(libs.spring.boot.dependencies))
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Brings spring-boot-starter-test plus the Boot 4 web test modules (RestTestClient).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit.junit6)
    testImplementation(libs.exposed.migration.jdbc)
}

exposed {
    migrations {
        tablesPackage.set("$basePackage.backend.persistence.tables")
        testContainersImageName.set(migrationGeneratorImage)
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
    }
}

// A developer's provider credentials must never reach a test JVM (Spring AI and the SDK read them as fallbacks).
tasks.withType<Test>().configureEach {
    listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL").forEach { environment.remove(it) }
    // Spring relaxed binding maps SPRING_AI_ANTHROPIC_API_KEY and friends onto spring.ai.anthropic.*
    environment.keys.filter { it.startsWith("SPRING_AI_") }.forEach { environment.remove(it) }
}

tasks.test {
    // The repository scan has its own task (secretsScan) so it neither slows this Docker-backed task nor goes stale.
    useJUnitPlatform { excludeTags("secrets-scan") }
}

// The scan reads the whole working tree, which no input declaration can describe exactly (Gradle's file trees apply
// Ant default excludes that the scanner does not), so the task simply runs on every build; it takes about a second.
val repositoryRoot = rootProject.projectDir.absolutePath

val secretsScan = tasks.register<Test>("secretsScan") {
    description = "Scans the text files of the repository's working tree for secrets."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("secrets-scan") }
    doNotTrackState("scans the working tree, which is not a declarable input")
    systemProperty("secrets.scan.root", repositoryRoot)
}

tasks.check { dependsOn(secretsScan) }
