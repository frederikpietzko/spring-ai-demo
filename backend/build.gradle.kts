plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.springBoot)
    alias(libs.plugins.exposed)
}

val basePackage = providers.gradleProperty("basePackage").get()

// The Exposed generator only accepts images whose name starts with "postgres", so it cannot use the pgvector image
// from compose.yaml. Plain PostgreSQL 18 is enough: the pgvector DDL is hand-written in db/vector, which the generator
// never reads (ADR-0006).
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
    // Policy RAG (ADR-0006): Ollama embeddings, the pgvector store, the Markdown reader and the RAG advisor.
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-markdown-document-reader")
    implementation("org.springframework.ai:spring-ai-rag")
    // Session Memory: JDBC session repository, session advisors and their auto-configuration. The schema stays with
    // Flyway (db/session, ADR-0002); the library's own schema initialization is switched off in application.yaml.
    implementation("org.springframework.ai:spring-ai-starter-model-session-jdbc")
    // The adjuster's MCP client for the core tools of core-mcp (JDK HttpClient transport, no WebFlux stack).
    implementation("org.springframework.ai:spring-ai-starter-mcp-client")
    // The adjuster's todo list and skills (ADR-0007): only TodoWriteTool and SkillsTool are used. flexmark serves only
    // the library's web fetch tool, so it stays off the classpath.
    implementation(libs.spring.ai.agent.utils) { exclude(group = "com.vladsch.flexmark") }
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Lets Spring MVC return a Kotlin Flow (the Flux-to-Flow bridge of the chat endpoint); version from the Boot BOM.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    // Observability (ADR-0010): Micrometer tracing bridge to OpenTelemetry, OTLP span and metric export. The span
    // exporter sends over the JDK HTTP client; its OkHttp sender is excluded for every configuration below.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    runtimeOnly("io.opentelemetry:opentelemetry-exporter-sender-jdk")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly(platform(libs.spring.boot.dependencies))
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    // Brings spring-boot-starter-test plus the Boot 4 web test modules (RestTestClient).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation(libs.archunit.junit6)
    testImplementation(libs.exposed.migration.jdbc)
    // Switches span and metric export off in every test context (an in-memory meter registry instead of OTLP).
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    // The starter puts the OTLP exporter on the runtime classpath only; OtlpSenderOnClasspathTest builds one.
    testImplementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Streamed observations stop after the response is sent; tests wait for them (version from the Boot BOM).
    testImplementation("org.awaitility:awaitility")
}

// The OTLP OkHttp sender brings okhttp-jvm 5.x, a different module id than the Anthropic SDK's okhttp 4.12, so Gradle
// would put both (same okhttp3 package) on the classpath. Excluded everywhere: the OpenTelemetry test starter pulls
// the main starter in again (ADR-0010).
configurations.configureEach {
    exclude(group = "io.opentelemetry", module = "opentelemetry-exporter-sender-okhttp")
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
    // Not a credential: removed so the MCP wiring test sees the committed default URL of the core system.
    environment.remove("CORE_MCP_URL")
    // Spring relaxed binding maps SPRING_AI_ANTHROPIC_API_KEY and friends onto spring.ai.anthropic.*
    environment.keys.filter { it.startsWith("SPRING_AI_") }.forEach { environment.remove(it) }
    // Boot maps OTEL_* variables (endpoint, sampler, OTEL_SDK_DISABLED) onto management.* properties.
    environment.keys.filter { it.startsWith("OTEL_") }.forEach { environment.remove(it) }
}

tasks.test {
    // The repository scan has its own task (secretsScan) so it neither slows this Docker-backed task nor goes stale.
    useJUnitPlatform { excludeTags("secrets-scan") }
    // ObservabilityStackFilesTest reads these files from the repository root.
    inputs.files(rootProject.file("compose.yaml"), rootProject.file("compose/lgtm"))
        .withPropertyName("observabilityStackFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
