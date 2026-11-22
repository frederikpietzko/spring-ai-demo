plugins {
    id("demo.kotlin-jvm")
}

dependencies {
    // A library module without runtime (ADR-0012): Spring AI's chat client and session types appear in the DSL
    // signatures, so they are API dependencies; versions from the Spring AI BOM.
    api(platform(libs.spring.ai.bom))
    api("org.springframework.ai:spring-ai-client-chat")
    api("org.springframework.ai:spring-ai-session")
    api("org.springframework.ai:spring-ai-session-advisor")
    // FlowAdvisor bridges Flux and Flow.
    api(libs.kotlinx.coroutinesReactor)

    // TestObservationRegistry for the observation test of FlowAdvisor; version from the Boot BOM, test scope only.
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("io.micrometer:micrometer-observation-test")
    // JUnit runs `suspend` test functions only with kotlin-reflect on the classpath.
    testRuntimeOnly(libs.kotlin.reflect)
}
