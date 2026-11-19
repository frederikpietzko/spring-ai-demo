# ADR-0001: Multi-module Gradle layout

- Status: accepted
- Date: 2026-11-19
- Change: `scaffold-project`

## Context

The repository demonstrates Spring AI features. It needs two runtime artifacts: a Spring Boot backend and a Compose Desktop frontend to explore the features in. Both need the same Kotlin, test and static-analysis configuration. The (currently disabled) hooks in `.claude/hooks/` discover modules as directories that hold a `build.gradle.kts` and expect a `detekt` and a `check` task in each, with the directory name mapping 1:1 to the Gradle path (`backend` -> `:backend`).

## Decision

One Gradle build with the Kotlin DSL:

```
settings.gradle.kts        includeBuild("build-logic"); include(":backend", ":desktop"); foojay toolchain resolver
build.gradle.kts           plugin aliases with `apply false`
gradle/libs.versions.toml  the only place for versions (also read by build-logic)
build-logic/               convention plugin `demo.kotlin-jvm`
backend/                   Spring Boot application
desktop/                   Compose Desktop application
config/detekt/detekt.yml   shared detekt configuration
compose.yaml               local PostgreSQL
docs/adr/                  decision records
```

- `build-logic` is an included build (not `buildSrc`). The convention plugin `demo.kotlin-jvm` applies Kotlin JVM, the JDK 25 toolchain, the four experimental compiler flags mandated by the `kotlin-engineer` skill (`-Xname-based-destructuring=complete`, `-Xcollection-literals`, `-Xintrinsic-const-evaluation`, `-Xreturn-value-checker=check`), the test tooling (JUnit 6 via the BOM, `kotlin-test`, power-assert, launcher, `java.awt.headless=true`) and detekt. `check` therefore runs detekt in every module.
- `backend` and `desktop` do not depend on each other. There is no shared contract module yet; it is decided with the first feature that crosses the boundary.
- Group `dev.frederikpietzko` and base package `dev.frederikpietzko.springaidemo` live in one value each (`projectGroup`, `basePackage` in `gradle.properties`). The group is applied by the convention plugin; the base package feeds the desktop `mainClass` and the Exposed `tablesPackage`. Renaming the source package root is one IDE rename.
- Configuration cache and build cache are enabled.

## Alternatives

- A single module with several source sets: rejected, the Spring Boot and Compose plugins and their dependency sets conflict.
- `buildSrc`: rejected, it invalidates the whole build on every change; an included build matches the hooks, which treat `build-logic/` as a global input.
- A separate `persistence` module: rejected for a demo repository. Tables will live in `<basePackage>.backend.persistence.tables`. Revisit when a second consumer of the tables appears.
- Repeating the configuration in each module: rejected, it drifts, and the hooks assume uniform tasks.

## Consequences

- Every module gets `detekt` and `check` from one place; the disabled hooks work unchanged once enabled.
- Versions are changed once in `gradle/libs.versions.toml`. Boot-managed library versions come from the Spring Boot BOM.
- Sharing code between backend and desktop needs a new module and a new ADR.
- No test is committed by the scaffold (see ADR-0002, "Deferred tests"); the tooling is declared so the first test needs no build change.
