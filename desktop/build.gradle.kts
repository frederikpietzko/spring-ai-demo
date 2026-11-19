plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val basePackage = providers.gradleProperty("basePackage").get()

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)

    // Declared for the tests that follow; this change commits none.
    testImplementation(libs.compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "$basePackage.desktop.MainKt"
        // Run with the JDK 25 toolchain the code is compiled for, not the JDK that runs Gradle.
        javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath
    }
}
