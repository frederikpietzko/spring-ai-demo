plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val basePackage = providers.gradleProperty("basePackage").get()

dependencies {
    implementation(project(":chat-protocol"))
    implementation(project(":control-protocol"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    // The chat client (SSE into a Flow); kotlinx.serialization comes through chat-protocol's api.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    testImplementation(libs.compose.uiTest)
    // JUnit 6 runs `@Test suspend fun` through kotlin-reflect (kotlinx-coroutines-core is already on the classpath).
    testRuntimeOnly(libs.kotlin.reflect)
}

tasks.test {
    // Silences the JDK native-access warning from skiko's library loader.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "$basePackage.desktop.MainKt"
        // Run with the JDK 25 toolchain the code is compiled for, not the JDK that runs Gradle.
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
    }
}
