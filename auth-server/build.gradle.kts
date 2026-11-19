plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.springBoot)
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // Spring Authorization Server with Boot's auto-configuration (signing key, issuer settings).
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-authorization-server")
    // Community MCP flavour of it (ADR-0009): the `mcpAuthorizationServer()` configurer.
    implementation(libs.mcp.authorization.server)

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}
