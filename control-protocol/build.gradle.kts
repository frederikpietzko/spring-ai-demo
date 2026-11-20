plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The command syntax and the state snapshot shared by `desktop` and `cli` (ADR-0011): no runtime, only the
    // chat-protocol types the snapshot embeds and the serialization runtime.
    api(project(":chat-protocol"))
    api(libs.kotlinx.serialization.json)
}
