plugins {
    id("demo.kotlin-jvm")
    alias(libs.plugins.kotlinSerialization)
}

dependencies {
    // The only library of the protocol module (ADR-0005): no Spring, Compose or Ktor type can be referenced here.
    api(libs.kotlinx.serialization.json)
}
