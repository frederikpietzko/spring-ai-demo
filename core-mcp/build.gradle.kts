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

// A resolvable runtime classpath of :auth-server for the test JVMs; nothing in core-mcp compiles against it.
val authServerRuntime: Configuration = configurations.create("authServerRuntime") {
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(
            TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment.STANDARD_JVM),
        )
    }
}

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
    // OAuth 2 resource server (ADR-0009): Boot's JWT decoder and the community MCP configurer on top of it.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation(libs.mcp.server.security)
    runtimeOnly("org.postgresql:postgresql")

    // No spring-boot-docker-compose: its connection details (database `backend`) would override the `core` URL.

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit.junit6)
    testImplementation(libs.exposed.migration.jdbc)
    // AuthServerProcess waits for the forked auth-server to answer (version from the Boot BOM).
    testImplementation("org.awaitility:awaitility")
    // Only the tests need the auth-server: they start it as a separate JVM with this classpath (ADR-0009).
    authServerRuntime(project(":auth-server"))
}

/** Hands the auth-server classpath to the test JVM, resolved only when the test task runs (and tracked as input). */
class AuthServerClasspath(@get:Classpath val classpath: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Dauth-server.classpath=${classpath.asPath}")
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(AuthServerClasspath(authServerRuntime))
    // The tests fork their own auth-server with generated secrets; a developer's values must not leak in.
    listOf("AUTH_SERVER_URL", "CLAIMANT_AGENT_SECRET", "ADJUSTER_AGENT_SECRET").forEach { environment.remove(it) }
}

exposed {
    migrations {
        tablesPackage.set("$basePackage.coremcp.persistence.tables")
        testContainersImageName.set(migrationGeneratorImage)
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/migration"))
    }
}
