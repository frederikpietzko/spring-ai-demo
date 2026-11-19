plugins {
    // Loaded once here so the plugins are not loaded again in every subproject's classloader.
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSpring) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.springBoot) apply false
    alias(libs.plugins.exposed) apply false
}
