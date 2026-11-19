import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.power-assert")
    id("dev.detekt")
}

private val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = providers.gradleProperty("projectGroup").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xname-based-destructuring=complete",
            "-Xcollection-literals",
            "-Xintrinsic-const-evaluation",
            "-Xreturn-value-checker=check",
        )
    }
}

@OptIn(ExperimentalKotlinGradlePluginApi::class)
powerAssert {
    functions.addAll(
        "kotlin.assert",
        "kotlin.test.assertTrue",
        "kotlin.test.assertFalse",
        "kotlin.test.assertEquals",
        "kotlin.test.assertNotEquals",
        "kotlin.test.assertNull",
        "kotlin.test.assertNotNull",
        "kotlin.test.assertIs",
        "kotlin.test.assertContentEquals",
    )
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("kotlin-test").get())
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly(libs.findLibrary("junit-platformLauncher").get())
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
