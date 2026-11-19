# Design

## Context

Greenfield: the repo holds only agent/skill/hook configuration (`.claude/`), `CLAUDE.md` and an empty `openspec/`. No Gradle build, no code, no `docs/adr/`. See proposal.md for motivation and scope.

Constraints that shape the approach:

- CLAUDE.md: Kotlin, Spring Boot, Spring AI 2.1; ADR required for new top-level modules and shared abstractions; provider keys come from the environment; `./gradlew check` is the definition of done.
- `.claude/hooks/hooks.disabled.json` (not active): `detekt_changed_file.py` runs `:<module>:detekt` for the module that owns an edited file, `check_changed_modules.py` runs `check` for changed modules. Modules are discovered as directories holding a `build.gradle.kts` (`gradle_modules.py`); `build-logic/` and `buildSrc/` are skipped as modules but count as global inputs. So every module needs a `detekt` task and a `check` task, and module directories map 1:1 to Gradle paths (`backend` -> `:backend`).
- Spring AI 2.1 Anthropic wiring, verified against the v2.1.0-RC2 sources (`AnthropicChatAutoConfiguration`, `AnthropicChatProperties`, `AnthropicSetup`): the model is chosen by `spring.ai.anthropic.chat.model` (not `...chat.options.model`), the key by `spring.ai.anthropic.api-key`; when no key property is set the SDK reads `ANTHROPIC_API_KEY` from the environment, and when there is none the model bean is still constructed (credentials resolve to none) and fails only when a request is made. Sampling options (`temperature`, `top-p`, `top-k`) are rejected by the 2026-10-21 API and must stay unset.
- Project skills prescribe: Kotlin policy with experimental compiler flags (`kotlin-engineer`), JUnit 6 + power-assert (`kotlin-testing`), Exposed v1 with the Boot 4 starter and Flyway applied separately from generation (`exposed-orm`). The testing skills (`spring-testing`, `archunit`, `spring-ai` fakes) apply to the follow-up tests, not to this change.
- User decisions that bound this change: work happens on `main`; this is build setup only, so no tests, no database model and no features; verification is by commands.
- Reference project `~/Workspace/oculi2` (same author, MIT) provides the Compose Desktop theme and components; its `build-logic` is Swift/macOS specific and is not reused, only the structure idea (included build with `libs` catalog shared from `gradle/libs.versions.toml`).

Versions verified on 2026-11-19 (Gradle MCP `lookup_maven_versions`, project skills); the implementer re-checks before pinning:

| Item | Version | Note |
| --- | --- | --- |
| Gradle wrapper | 9.9.1 | latest per Gradle MCP; oculi2 uses 9.5.1 |
| Kotlin | 2.4.21 | latest stable; 2.5.0-RC exists, not used |
| JDK toolchain | 25 | LTS, same as oculi2; local JDK is 27, so the toolchain (foojay resolver) pins it. Developers select it with SDKMAN: `.sdkmanrc` contains `java=25.0.5.0+8-tem`. As built: the SDKMAN list shows `25.0.5.1+2-tem` as default, but that identifier returns 404 on macOS arm64/x64 and exists only for Linux x64, so the macOS-installable `25.0.5.0+8-tem` is pinned |
| Spring Boot | 4.2.0-RC2 | required by Spring AI 2.1; move to 4.2.0 GA when released |
| Spring AI BOM | 2.1.0-RC2 | 2.1.0 GA not yet released; 2.0.2 is the latest GA but is not 2.1 |
| Exposed (libs and Gradle plugin `org.jetbrains.exposed.plugin`) | 1.6.1 | >= 1.5.1 is the CVE floor; starter `exposed-spring-boot4-starter` |
| Flyway | Boot-managed (12.11.0 with Boot 4.2.0-RC2) | `spring-boot-starter-flyway` + `flyway-database-postgresql` |
| Compose Multiplatform | 1.12.2 | stable; 1.13.0-rc01 exists, not used |
| detekt | `dev.detekt` 2.0.0-alpha.6 | the 1.23.x line (`io.gitlab.arturbosch.detekt`) ends at 1.23.8 (2025); see Risks |
| ArchUnit | `archunit-junit6` 1.5.1 | not added now; with the first rule (follow-up) |
| Testcontainers | 2.0.x via Boot BOM (`spring-boot-testcontainers`) | declared as test dependency only; module names changed in 2.x (`testcontainers-postgresql`) |
| PostgreSQL image | `pgvector/pgvector:pg18` | tag verified on the registry; Postgres 18 with pgvector for the planned RAG demos. As built, two values: `compose.yaml` uses `pgvector/pgvector:pg18`, while the Exposed migration generator uses `postgres:18` because the plugin rejects other image prefixes (D4). Later tests will need `DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")` |
| Chat model | Anthropic, `claude-haiku-5-5` | `spring-ai-starter-model-anthropic`; model id as given by the project environment (Spring AI's own documented default is the older `claude-haiku-4-5`, so it is set explicitly) |

## Goals / Non-Goals

**Goals:**
- A build that is green (`./gradlew build` and `./gradlew check`, detekt included) on the first commit, and a backend and a desktop app that start, so every later change can add one feature slice.
- Test tooling declared up front so the first test needs no build change.
- One source of truth for versions, group/package, the Postgres image and the Java version.

**Non-Goals:**
- Tests of any kind, any database model, any feature, any endpoint beyond Actuator health.
- A shared contract module between backend and desktop (decided with the first feature that crosses the boundary).
- Production hardening (security, packaging, CI, containerizing the backend).

## Decisions

### D1. Module layout: root + `build-logic` + `backend` + `desktop`

```
settings.gradle.kts        includeBuild("build-logic"); include(":backend", ":desktop"); foojay toolchain resolver
build.gradle.kts           plugin aliases with `apply false` (as oculi2)
gradle/libs.versions.toml  the only place for versions (also consumed by build-logic via `from(files(...))`)
build-logic/               convention plugins: `demo.kotlin-jvm` (toolchain 25, compiler flags, JUnit platform, detekt, power-assert)
backend/                   Spring Boot application
desktop/                   Compose Desktop application
config/detekt/detekt.yml   shared detekt config (buildUponDefaultConfig)
compose.yaml               local Postgres
docs/adr/                  ADR-0001 module layout, ADR-0002 stack
```

Rationale: two runtime artifacts need two modules; an included `build-logic` build keeps Kotlin/test/detekt configuration in one place without `buildSrc` cache invalidation and matches the hooks (`build-logic/` is treated as a global input). Backend and desktop do not depend on each other (spec: build-and-quality).

Alternatives: a single module with source sets (rejected: Spring Boot and Compose plugins and dependency sets conflict); `buildSrc` (rejected: invalidates the build on every change, oculi2 already moved to `build-logic`); a `persistence` module split from `backend` (rejected for a demo repo: tables will live in a `..persistence.tables..` package of the backend, and the Exposed plugin classpath is the backend's main runtime classpath). Revisit when a second consumer of the tables appears.

Packages and coordinates: group `dev.frederikpietzko` (as oculi2), base package `dev.frederikpietzko.springaidemo`, `...backend` and `...desktop` below it, Gradle root project name `spring-ai-demo`. Confirmed by the user. To keep a later change cheap, the group lives in one value (`projectGroup` in `gradle.properties`, applied by the convention plugin) and the base package in one value (`basePackage` in `gradle.properties`, used for `mainClass` and the Exposed `tablesPackage`); the source package root is renamed with one IDE rename (`ide rename`/`ide move-file`). The README documents the procedure.

### D2. Convention plugin `demo.kotlin-jvm` (build-logic)

Applies Kotlin JVM, `jvmToolchain(25)`, the mandatory experimental flags from the `kotlin-engineer` skill (`-Xname-based-destructuring=complete`, `-Xcollection-literals`, `-Xintrinsic-const-evaluation`, `-Xreturn-value-checker=check`; `allWarningsAsErrors` only if it does not fight the experimental-feature warnings, otherwise left off), the **test tooling** (`useJUnitPlatform()`, JUnit 6 via the junit BOM, `kotlin-test`, the power-assert plugin for tests, `junit-platform-launcher` at runtime, `java.awt.headless=true` on the test task), and detekt with `config/detekt/detekt.yml`. Backend additionally applies the Spring (`kotlin-spring`) compiler plugin and the Boot plugin in its own `build.gradle.kts`; desktop applies the Compose plugin and the Compose compiler plugin. Configuration cache and build cache are enabled in `gradle.properties` (as oculi2). With no test sources, `test` is a no-op and `check` runs detekt only.

Alternative: repeating the configuration in each module (rejected: drifts; the hooks and `check` gate assume uniform tasks).

### D3. Backend stack and wiring

- Dependencies: `spring-boot-starter-webmvc`, `spring-boot-starter-actuator`, `spring-boot-starter-flyway` + `flyway-database-postgresql`, `exposed-spring-boot4-starter` (1.6.1), `org.postgresql:postgresql` (runtime), `spring-ai-bom` platform + `spring-ai-starter-model-anthropic` (brings the Anthropic model and the `ChatClient.Builder` auto-configuration; verify with `inspect_dependencies` that no separate `spring-ai-client-chat` is needed), `tools.jackson.module:jackson-module-kotlin`, `spring-boot-docker-compose` (developmentOnly). Test dependencies declared and unused: `spring-boot-starter-test` (and the Boot 4 web test starter if separate), `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`. Exact Boot 4 / Testcontainers 2 artifact names are resolved against the BOM, not from memory. No ArchUnit.
- BOMs as built: the Spring Boot and Spring AI BOMs are applied as `implementation(platform(...))` (and `developmentOnly(platform(...))`) instead of the `io.spring.dependency-management` plugin, which keeps the build on plain Gradle platform resolution.
- Exposed owns transactions through Spring: `@ImportAutoConfiguration(ExposedAutoConfiguration::class, exclude = [DataSourceTransactionManagerAutoConfiguration::class])` exactly as in the `exposed-orm` skill (Boot 4 package `org.jetbrains.exposed.v1.spring.boot4...`); `spring.exposed.generate-ddl=false`. Flyway runs on the same `DataSource` through Boot's Flyway auto-configuration.
- Configuration: `application.yaml` with `spring.datasource.url/username/password` bound from `DB_URL`, `DB_USER`, `DB_PASSWORD` with local-dev defaults that match `compose.yaml` (user/password/db all `demo`, documented as local-only); `management.endpoints.web.exposure.include=health`, `management.endpoint.health.show-components=always`; Spring AI: `spring.ai.anthropic.chat.model=claude-haiku-5-5` and nothing else. In particular no `spring.ai.anthropic.api-key` (the SDK reads `ANTHROPIC_API_KEY` from the environment, so no key ever sits in a file or placeholder), and no `temperature`/`top-p`/`top-k`. `max-tokens` keeps its default (4096).
- No tables and no `persistence.tables` package: nothing to scan yet. `db/migration/` exists with a `.gitkeep`.
- Chat wiring only: no `ChatClient` bean, controller or service; the auto-configured `AnthropicChatModel` and the prototype `ChatClient.Builder` are the whole surface. Without `ANTHROPIC_API_KEY` the app starts and is healthy (see the `AnthropicSetup` finding above; confirmed empirically by the `bootRun` check in task 2.3) and a chat call would fail at call time. README documents that a real call needs the variable.
- Local run: `./gradlew :backend:bootRun` starts compose Postgres via Boot Docker Compose support (`spring.docker.compose.file=../compose.yaml`, lifecycle `start_only`). Tests will later set `spring.docker.compose.enabled=false` in their shared properties.

Alternatives: Spring Data JDBC/JPA (rejected by the request: Exposed); R2DBC (rejected: the Boot starter and migration plugin are JDBC-based and demos do not need non-blocking persistence yet); Flyway run by a Gradle plugin instead of at startup (rejected: the startup path is what Spring Boot demos exercise).

### D4. Schema workflow: generate with Exposed, apply with Flyway

- `exposed { migrations { tablesPackage, testContainersImageName = <image>, fileDirectory = "src/main/resources/db/migration" } }` in `backend/build.gradle.kts` with `tablesPackage` derived from `basePackage` (`<basePackage>.backend.persistence.tables`); command `./gradlew :backend:generateMigrations` (needs Docker; the plugin replays existing scripts in a Testcontainers Postgres). It is never a dependency of `build`/`check`.
- Flyway applies the same directory at startup. Naming `V<timestamp>__<description>.sql` (plugin default `TIMESTAMP_ONLY`). Applied files are immutable; changes are new files.
- The scaffold ships **no migration and no table**. With an empty history Flyway starts cleanly. The first feature that adds a table also creates the `persistence.tables` package, generates and commits its first migration, and adds the schema-agreement check (D8).
- Generator image, as built: the Exposed plugin starts the container itself and rejects `pgvector/pgvector:pg18` ("Unsupported database container image... Supported prefixes are: mysql, mariadb, postgres, ..."). `backend/build.gradle.kts` therefore sets `testContainersImageName` to `postgres:18`, the fallback the user pre-approved, for migration generation only; `compose.yaml` keeps `pgvector/pgvector:pg18`. The two images are not one shared value. This is harmless while no vector column exists; revisit it with the first vector column (generate against a pgvector-capable database through the plugin's direct JDBC mode, or keep the vector DDL hand-written). The empty `tablesPackage` did not need a workaround.
- ADR-0002 records: Exposed generates, Flyway applies; `SchemaUtils`/`generate-ddl` forbidden. No vector extension, no baseline migration.

### D5. Desktop: `kotlin("jvm")` + Compose Multiplatform plugin, desktop target only

Same shape as oculi2's `desktopApp`: `kotlinJvm`, `composeMultiplatform`, `composeCompiler` plugins; `compose.desktop.currentOs`, `kotlinx-coroutines-swing`, compose `runtime/foundation/ui`; `compose.desktop.application { mainClass = "...desktop.MainKt" }`; no native distributions yet. Plain `Window` (no JetBrains Runtime title bar tricks, no `jbr-api`, no macOS-specific code). The module is named `desktop` (not `composeApp`) and stays independent of the backend.

Alternative: the Kotlin Multiplatform Gradle plugin with a `jvm("desktop")` target and `commonMain`/`desktopMain` (the wizard layout). More faithful to "multiplatform", but adds source-set indirection with no second target and a different test setup; migrating later is mechanical. Decided by the user: `kotlin("jvm")`, desktop only, no other targets planned.

Empty screen (`App()` composable): `DemoTheme(override = null)` (follows the system) wrapping a `Column` with the theme background: a 60 dp header (surface colour, app name in the `display` text style) and a content box centred on a muted placeholder text. Window title `spring-ai-demo`, default size 1280 x 800. No state, no viewmodel, no navigation. As built, `compose.desktop.application.javaHome` points at the JDK 25 toolchain launcher; without it `:desktop:run` uses Gradle's own JDK and fails with `UnsupportedClassVersionError`.

### D6. What to copy from oculi2 (adapt, do not import)

Source root: `~/Workspace/oculi2/desktopApp/src/main/kotlin/dev/frederikpietzko/oculi2/`. Target root: `desktop/src/main/kotlin/dev/frederikpietzko/springaidemo/desktop/`. Rename the `Oculi*` identifiers to a neutral `Demo*` prefix and the `O*` components to `D*` (`OculiTheme` -> `DemoTheme`, `Oculi` accessor object -> `Demo`, `OButton` -> `DButton`, ...). Keep MIT attribution in the ADR (same author).

| Source file | Action | Why |
| --- | --- | --- |
| `theme/Colors.kt` (78 lines) | copy, rename `OculiColors` -> `DemoColors`, drop `previewBackdrop` | the light/dark palette and the Kotlin purple-to-pink `accentGradient` are the look to keep |
| `theme/Typography.kt` (39 lines) | copy, rename types | text styles and `OculiShapes` (6/10/16 dp and pill) |
| `theme/OculiTheme.kt` (50 lines) | copy -> `DemoTheme.kt`; remove `@Serializable` from `ThemeMode` (no serialization dependency); keep `resolveDarkTheme`, both `DemoTheme` overloads and the accessor object | theme provider, light/dark/override |
| `components/OText.kt` | copy | base text + `Modifier.oculiOutline` focus/error outline (rename `demoOutline`) |
| `components/OButton.kt` | copy | primary (gradient), secondary, ghost, danger |
| `components/OCard.kt` | copy | surface card with gradient highlight outline |
| `components/OChip.kt` | copy | pill chip, selected/warning |
| `components/OTextField.kt` | copy | themed single-line field with label/supporting text |
| `components/OToggle.kt` | copy | gradient switch |
| `components/OSelect.kt` | copy | themed dropdown (uses `Popup`) |
| `components/OSpinner.kt` | copy | gradient indeterminate indicator |
| `components/OIconButton.kt` | copy the `DIconButton` composable only; drop the `OIcons` object (Expand/Collapse/Edit/Sidebar are app icons) but keep the private `strokeIcon` helper as an internal `DIcons` builder with no icons | reusable icon button without oculi icons |

The oculi2 tests (`ScreenRendering.kt`, `OculiThemeTest.kt`, `ComponentsRenderTest.kt`) are not copied now; they are the templates for the deferred desktop tests (D8). Explicitly not copied: `components/OLogo.kt` (oculi mark), `ui/*` (MainWindow, SceneEditor, SourceLibrary, RecordingBar, `Motion.kt`, `LoadingQuips.kt`, ...), `scenes/`, `recording/`, `preview/`, `permissions/`, `settings/`, `persistence/`, `BackendConnection.kt`, `main.kt` (JetBrains Runtime title bar, Dock icon, Settings menu), `build-logic` (Swift/macOS plugins), `recordingbackend/`, resources and icons.

`ui/Motion.kt` is generic (`subtleTween`, animated list helpers) but unused by an empty screen; leave it behind and copy it with the first feature that animates.

### D7. Quality gate: detekt only

- Every module gets `detekt` (from `demo.kotlin-jvm`) and `check` depends on it, so the disabled hooks work unchanged once enabled. `config/detekt/detekt.yml` uses `buildUponDefaultConfig`; no baseline is needed on a fresh repo.
- As built, `config/detekt/detekt.yml` sets `FunctionNaming.ignoreAnnotated: ['Composable']` so PascalCase composables pass, and declarations are laid out for `MatchingDeclarationName` (`DButtonStyle` and `SelectOption` live in their own files).
- ArchUnit is not added; its dependency and first rule come with the first architecture decision that needs one (the `archunit` skill).
- The hooks stay disabled (decided by the user); the scaffold only guarantees that `:module:detekt` and `:module:check` exist.

### D8. Test boundaries: deliberately deferred

By user decision this change contains no tests: it is build setup only, and verification is by commands (task list). Test tooling is declared (D2, D3) so the planned tests below can be added by later changes without touching the build. The boundaries agreed for them:

| # | Boundary | Planned test | Catches | Misses |
| --- | --- | --- | --- | --- |
| T1 | HTTP `GET /actuator/health` on the full Spring context with Testcontainers Postgres (`@ServiceConnection` bean in one shared `@TestConfiguration`, image constant declared compatible with `postgres`, `spring.docker.compose.enabled=false`) | 200/`UP`, `db` component `UP`, starts with no Anthropic key | wiring errors (Exposed starter vs Boot transaction manager, Flyway ordering, datasource binding, Spring AI failing without a key) | runtime database outage, real LLM calls |
| T1b | Beans in the same shared context | the model bean is the Anthropic model with default model `claude-haiku-5-5` (check the 2.1 accessor name), a `ChatClient.Builder` builds a client, only actuator mappings exist | wrong property name (the 1.x `...chat.options.model` form is silently ignored), stray endpoint | model behavior |
| T2 | Flyway state through the shared context | no pending migrations, tables equal those created by migrations, a second `migrate()` is a no-op | disabled Flyway, accidental DDL | content of future migrations |
| T3 | Schema agreement: Exposed definitions vs migrated schema, inside a transaction (`MigrationUtils`, verify the 1.6.1 API) | no statements required | table without migration | vacuous until the first table exists |
| T4 | Flyway API on a scratch schema of the same container | replay from empty succeeds; editing an applied migration fails validation | disabled validation, rewritten history | the library itself |
| T5 | ArchUnit on compiled backend classes (`archunit-junit6`) | `Table` subclasses only in `..persistence.tables..` | tables outside the scanned package | behavior |
| T6 | Compose semantics test on `App()` (`runComposeUiTest`/`runDesktopComposeUiTest`, API to verify for 1.12.2) | app name and placeholder shown, no controls | accidental feature controls | pixel styling |
| T7 | Off-screen render (`renderComposeScene` helper from oculi2's `ScreenRendering.kt`) of `App()` and the component gallery in both themes | non-blank, light differs from dark | composition crashes, blank screen | visual regressions |
| T8 | Pure test of `resolveDarkTheme` and colour tokens (oculi2's `OculiThemeTest`) | override beats system, gradient endpoints | theme logic | rendering |
| T9 | Secrets scan over committed config | no key/token values | accidental key commit | |

Policy for those follow-up tests: `spring-testing` (one shared context, `@ServiceConnection`, fakes over mocks) and `kotlin-testing`. For Anthropic the recommended shape, recorded here so it is not lost: keep the real auto-configured `AnthropicChatModel` in the shared context for T1/T1b, remove `ANTHROPIC_API_KEY` from the test task environment and point `spring.ai.anthropic.base-url` at an unroutable address as a guard; when a feature needs deterministic replies, switch the shared configuration to `spring.ai.model.chat=none` plus a `FakeChatModel` whose options builder is a `ToolCallingChatOptions` (per the `spring-ai` skill).

### D9. Documentation and ADRs

- `docs/adr/0001-multi-module-gradle-layout.md`: D1, D2 (module and build-logic layout; backend and desktop independent; no shared module yet).
- `docs/adr/0002-technology-stack.md`: D3, D4, D5, D6 summary: Boot 4.2 + Spring AI 2.1 with Anthropic Haiku (keyless startup, env-only key), Exposed generates + Flyway applies, Postgres via compose/Testcontainers, Compose Desktop with the adapted oculi2 theme (MIT, same author), detekt gate, deferred tests, version table above.
- `.sdkmanrc` at the root (`java=25.0.5.0+8-tem`, see the version table), mentioned in the README prerequisites (`sdk env`).
- `README.md`: prerequisites (JDK 25 via `sdk env` or the Gradle toolchain, Docker, optional `ANTHROPIC_API_KEY` for real model calls and what happens without it), how to change group and base package, commands (`./gradlew check`, `:backend:bootRun`, `:desktop:run`, `docker compose up -d`, `:backend:generateMigrations`), env vars.
- CLAUDE.md: replace the "Status: project bootstrap. No Gradle build" line with the module list and commands; leave the hooks line as is (they stay disabled).

## Risks / Trade-offs

- [Spring Boot 4.2.0-RC2 and Spring AI 2.1.0-RC2 are release candidates] -> pin exactly, centralize in the catalog, move to GA when published; ADR-0002 states why (2.1 requires 4.2).
- [detekt 2.0 is alpha and may not parse Kotlin 2.4 experimental syntax (collection literals, name-based destructuring) or may lag behind Kotlin 2.4] -> task 1.3 proves detekt runs on a file that uses those features; if it does not, keep detekt applied but exclude only the affected construct/files via config, record it in ADR-0002 and report it to the orchestrator. Do not downgrade the Kotlin flags. The user accepted the alpha dependency.
- [No tests means regressions are caught only by the manual command checks] -> accepted by the user; the checks are listed per task and in the final gate, and D8 records what to automate first (T1, T1b).
- [Spring AI Anthropic keyless startup is inferred from RC2 source (`AnthropicSetup`/SDK `AnthropicBackend` resolve to no credential and fail only on request); a later RC could add eager validation] -> task 2.3 starts the backend without the key; if startup fails, stop and report so the decision can be revisited (fallback: document the key requirement).
- [The Exposed generator rejects the `pgvector/pgvector` image (confirmed during implementation), so migrations are generated against plain `postgres:18` while the running database is pgvector] -> accepted by the user while no vector column exists; revisit at the first vector column, since generated DDL could then miss or mis-render vector types.
- [`claude-haiku-5-5` is taken from the project environment, not from Spring AI docs, which still show `claude-haiku-4-5` as the default] -> the id is a single property value.
- [Spring Boot 4.2 test and starter module names differ from 3.x (`spring-boot-starter-webmvc`, `spring-boot-starter-flyway`, `spring-boot-testcontainers`)] -> resolve via Gradle MCP `inspect_dependencies`/`read_dependency_sources` rather than memory.
- [Docker is required for `generateMigrations` and for local `bootRun`; `build` and `check` need no Docker now] -> stated in README.
- [Renaming copied oculi2 identifiers can drift from upstream] -> accepted; the demo owns its copy.

## Migration Plan

Not applicable: greenfield. Rollback is deleting the new top-level files; nothing existing is modified except CLAUDE.md status text. Work is committed on `main` as the user decided.

## Open Questions

All questions raised in earlier revisions are answered by the user (2026-11-19):

1. ~~Provider starter~~ Anthropic starter with Haiku (`claude-haiku-5-5`), wiring only (D3).
2. ~~Backend/frontend communication~~ deferred, out of scope.
3. ~~Package naming / group id~~ `dev.frederikpietzko` / `dev.frederikpietzko.springaidemo` confirmed (D1).
4. ~~JDK~~ 25 via SDKMAN `.sdkmanrc` and the Gradle toolchain.
5. ~~Enable the disabled hooks~~ no.
6. ~~detekt line~~ `dev.detekt` 2.0.0-alpha.6 accepted.
7. ~~Frontend Kotlin plugin~~ `kotlin("jvm")` + Compose, desktop only.
8. ~~Postgres flavour~~ `pgvector/pgvector:pg18` for `compose.yaml`, image only; migration generation uses `postgres:18` (see 13).
9. ~~Initial migration~~ none.
10. ~~Empty screen content~~ header plus placeholder.
11. ~~Accent~~ keep the Kotlin purple-to-pink gradient.
12. ~~Docker for `check`~~ moot now: `check` needs no Docker without tests.
13. ~~pgvector image rejected by the Exposed plugin~~ plain `postgres:18` for generation is acceptable; this is what was built.
14. ~~Test-time Anthropic guard~~ moot: no tests in this change; the recommended shape is recorded in D8.
15. ~~Tests, DB model, ArchUnit~~ deferred by the user (D8, proposal follow-ups).

No open questions remain.
