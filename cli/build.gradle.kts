import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.graalvmNative)
    application
}

dependencies {
    implementation(project(":control-protocol"))
    implementation(libs.clikt.core)
    implementation(libs.kotlinx.coroutinesCore)

    // JUnit 6 runs `@Test suspend fun` through kotlin-reflect.
    testRuntimeOnly(libs.kotlin.reflect)
}

application {
    mainClass = "dev.frederikpietzko.springaidemo.cli.MainKt"
    applicationName = "kaiju"
}

// The seed SQL is owned and tested by core-mcp (ADR-0011): bundled as plain files, no dependency on core-mcp code.
tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("core-mcp/src/main/resources/demo-seed")) {
        into("demo-seed")
    }
}

// The bundled seed SQL is registered for the native image in META-INF/native-image/.../reachability-metadata.json.
// Only :cli:nativeCompile (and the tasks on top of it) need GraalVM; compile, test and check use the JDK 25 toolchain.
// With GRAALVM_HOME set, that installation is used instead of a toolchain (the fallback when provisioning fails).
val graalvmHome: String? = providers.environmentVariable("GRAALVM_HOME").orNull

graalvmNative {
    toolchainDetection = false
    testSupport = false
    metadataRepository { enabled = true }
    binaries {
        named("main") {
            imageName = "kaiju"
            mainClass = application.mainClass
            if (graalvmHome == null) {
                javaLauncher = javaToolchains.launcherFor {
                    languageVersion = JavaLanguageVersion.of(25)
                    vendor = JvmVendorSpec.GRAAL_VM
                    nativeImageCapable = true
                }
            }
        }
    }
}

val nativeBinary = layout.buildDirectory.file("native/nativeCompile/kaiju")
val installedBinary = rootProject.layout.projectDirectory.file("kaiju")

tasks.register("installKaiju") {
    group = "distribution"
    description = "Builds the native kaiju executable and copies it to ./kaiju in the project root."
    dependsOn(tasks.named("nativeCompile"))
    inputs.file(nativeBinary)
    outputs.file(installedBinary)
    val source = nativeBinary
    val target = installedBinary
    doLast {
        val from = source.get().asFile.toPath()
        val to = target.asFile.toPath()
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        check(to.toFile().setExecutable(true)) { "cannot make $to executable" }
    }
}

tasks.test {
    useJUnitPlatform { excludeTags("native") }
}

// Runs the built executable; not part of `check` (needs GraalVM and minutes of native compile).
tasks.register<Test>("nativeSmokeTest") {
    group = "verification"
    description = "Runs the native-tagged smoke tests against the native kaiju executable."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("native") }
    dependsOn(tasks.named("nativeCompile"))
    inputs.file(nativeBinary)
    systemProperty("kaiju.binary", nativeBinary.get().asFile.absolutePath)
    systemProperty("kaiju.composeFile", rootProject.layout.projectDirectory.file("compose.yaml").asFile.absolutePath)
}
