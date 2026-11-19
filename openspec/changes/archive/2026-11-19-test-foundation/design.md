# Design

## Context

- The repository has Spring Boot 4.2.0-RC2, Spring AI 2.1.0-RC2, Exposed 1.6.1, Flyway 12.11.0 (Boot-managed), Testcontainers 2.0.5 (Boot-managed), Compose 1.12.2, JUnit 6.1.3 with power-assert, and detekt. There is no test, no table, no migration file (`db/migration` holds `.gitkeep`) and no `@Controller`. Motivation: see `proposal.md`.
- ADR-0002 "Deferred tests" T1-T9 are the agreed boundaries; the existing specs are the behaviour the tests mostly verify. `ROADMAP.md` section 8 fixes the scope; section 10 rule 2 says every later test uses the shared fake `ChatModel`.
- Library facts below were verified on 2026-11-19 against the dependency sources of the exact versions (Spring Boot 4.2.0-RC2, Spring AI 2.1.0-RC2, Exposed 1.6.1, Flyway 12.11.0, Testcontainers 2.0.5, ArchUnit 1.5.1, Compose Multiplatform test artifacts), not from memory. Items read from source but not observed are marked "likely" and are turned into early red tests in `tasks.md` instead of assumptions.
- `compose.yaml` creates the database `demo` (`POSTGRES_DB`), `application.yaml` defaults `DB_URL` to it; user and password `demo` are local-only.
- `backend/build.gradle.kts` declares `spring-boot-starter-test`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `testcontainers-junit-jupiter`. `application.yaml` sets `spring.ai.anthropic.chat.model: claude-haiku-5-5`, no key, datasource `demo` on localhost.
- Constraints from skills: `spring-testing` (one shared context, `@ServiceConnection`, fakes over mocks, no `@MockitoBean`/`@DirtiesContext`), `kotlin-engineer`, `kotlin-testing`, `archunit`, `tdd`.

## Goals / Non-Goals

**Goals:**

- One shared backend test context that later changes reuse unchanged, with a scripted fake chat model, a real PostgreSQL, an HTTP client and test isolation rules.
- Automate T1-T9 and record which existing scenario each one verifies.
- Write ADR-0003 (topology) and ADR-0004 (test strategy), and bring ROADMAP, README and `CLAUDE.md` in line with reality.
- Rename the local database to `backend` so the runtime matches ADR-0003.

**Non-Goals:**

- Test helpers for later features (table cleanup, MCP fakes, SSE assertions, embedding fakes). Each later change adds its own helper next to the harness.
- Running the real provider in any test (the `evaluation` change owns that).
- Adding the `core` database, other runtime configuration changes (ports), or adding modules.

## Decisions

### D1. Test layout and names

Backend test sources live under `backend/src/test/kotlin/dev/frederikpietzko/springaidemo/backend/`. Shared support is in the package `...backend.testsupport` (test sources only):

| Name | Role |
| --- | --- |
| `@IntegrationTest` | meta-annotation for every backend integration test class (D2) |
| `TestInfrastructure` | the one `@TestConfiguration(proxyBeanMethods = false)`: container and fake chat model (D3, D4) |
| `FakeChatModel` | the scripted chat model (D4) |
| `FakeChatModelReset` | JUnit extension that resets the fake before each test (D4) |
| `SingleContextGuard`, `SingleContext` | JUnit extension registered by `@IntegrationTest`, and the identity check it uses: a test class in a second application context fails (D2) |

Test classes mirror the behaviour they check, one concept per class, not per library. Names below (`ApplicationHealthTest`, `FakeChatModelTest`, `AnthropicWiringTest`, `FlywayStateTest`, `MigrationHistoryTest`, `SchemaAgreementTest`, `ArchitectureTest`, `TestConventionsTest`, `SecretScannerTest`, `RepositorySecretsTest`) are the intended names; the implementer may adjust them if the scenario mapping in `tasks.md` stays recognisable.

### D2. One shared context: `@IntegrationTest`

```
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = [ ...shared test properties... ])
@AutoConfigureRestTestClient
@Import(TestInfrastructure::class)
@Target(CLASS)
@ExtendWith(FakeChatModelReset::class, SingleContextGuard::class)
annotation class IntegrationTest
```

- The whole context cache key lives in this one annotation: `@Import`, properties and the auto-configure annotation are part of the key, `@ExtendWith` is not. Every integration test class carries exactly `@IntegrationTest` and nothing else that changes the key. An ArchUnit rule enforces it (D10), and `SingleContextGuard` checks the outcome at run time: `SingleContext` keeps the first application context in an `AtomicReference`, compares later ones by identity (`===`) and, whatever the class order, fails every class whose context differs from the first one registered, naming it and the first class (it cannot tell which of the two customises the context; the architecture rules in D10 name the offending class). `SingleContextTest` unit-tests that check; there is no separate test class that "proves" one context.
- Dependencies: `spring-boot-starter-test` is replaced by `org.springframework.boot:spring-boot-starter-webmvc-test`, which brings `spring-boot-webmvc-test`, `spring-boot-resttestclient` and `starter-test`. Plain `@SpringBootTest` registers no HTTP test client in Boot 4, so `@AutoConfigureRestTestClient` is required.
- **`webEnvironment = RANDOM_PORT` with `RestTestClient` bound to the real server** (chosen over MOCK/MockMvc). With a `LocalTestWebServer` present the client binds to the server. Why: the `chat-streaming` change tests Server-Sent Events, and MockMvc buffers and handles async streaming differently from a real server, so event order and flushing are only trustworthy over a real socket; the desktop will also talk to a real server. Cost: a slightly slower first start and no `@Transactional` rollback per test (the `spring-testing` policy already forbids relying on it; tests isolate through unique data and cleanup). Because the choice is part of the cache key, later changes inherit it; changing it later means touching every test.
- Shared test properties (single place, in the annotation): `spring.ai.model.chat=none`; `spring.ai.anthropic.api-key=test-placeholder`; `spring.ai.anthropic.base-url=http://localhost:1`; `spring.docker.compose.enabled=false` (redundant today because `spring-boot-docker-compose` is `developmentOnly`, kept as cheap insurance and commented as such). With `chat=none` the Anthropic auto-configuration backs off entirely; the key and URL properties only matter if someone flips that, and then the call goes nowhere.
- Tests run sequentially (JUnit's default). The shared fake and the shared database make parallel test execution unsafe; the KDoc of `@IntegrationTest` and `TestConventionsTest` say so.

### D3. PostgreSQL for tests

`TestInfrastructure` declares `@Bean @ServiceConnection fun postgres(): PostgreSQLContainer` using `org.testcontainers.postgresql.PostgreSQLContainer("pgvector/pgvector:pg18")` (Testcontainers 2.x FQN, not the deprecated `org.testcontainers.containers` one). The declared return type must be the concrete container: for `@Bean` methods Boot cannot deduce the connection name from the image, and the JDBC factory matches on container type only, so the image name is irrelevant to the datasource and Flyway. `PostgreSQLContainer` accepts the `pgvector/pgvector` repository without `asCompatibleSubstituteFor`, so ADR-0002's "image declared compatible with postgres" note is obsolete. Flyway and Exposed receive the container through the connection details, exactly like production wiring.

The image string is duplicated in `compose.yaml` and the test infrastructure. Accepted: one constant, commented with a pointer to `compose.yaml`; the Exposed generator already uses a third image (`postgres:18`).

`TestDatabaseTest` asserts that the shared datasource URL is the container's (`@ServiceConnection` applied), which is what makes the tests independent of the compose database. The database is shared by all tests and lives as long as the cached context. Isolation rules for later tests: tests that write data use unique keys and clean up after themselves; schema-level experiments use a uniquely named scratch schema (D9).

### D4. `FakeChatModel`

Spring AI 2.1.0-RC2 ships no fake (`spring-ai-test` only has provider IT base classes), so we write one in test sources. Facts it relies on:

- `ChatModel` has one abstract method, `call(Prompt)`; `stream(Prompt)` throws by default and must be overridden; `getOptions()` (not the deprecated `getDefaultOptions()`) must return a `ToolCallingChatOptions`, otherwise returned tool calls are silently not executed and become the final answer (the first fake self-test guards this).
- Structured output is prompt based: the chat client appends format instructions to the last user message, and the fake answers with bare JSON (no code fences, unverified for fences).
- A `ChatClient.Builder` bean exists whenever one `ChatModel` bean exists and registers `ToolCallingAdvisor` (blocking and streaming loops) automatically. `ToolCallingAdvisor` also loops for `stream()`, aggregating chunks; the exact chunk shape it tolerates for tool calls is "likely" and is settled by the streaming-tool red test, not assumed.

Behaviour (the indicative API; names may change, behaviour may not):

```
fake.reply("text")                               // one text reply
fake.replyJson("""{"claimId":"c-1"}""")          // alias of reply for readability
fake.requestTool("getClaim", """{"id":"4711"}""")// answer = AssistantMessage with one ToolCall (id generated, type "function")
fake.replyChunks("Hel", "lo")                    // text delivered as several chunks when streamed, joined when called
fake.prompts                                     // every Prompt received, in order (thread-safe copy)
fake.reset()                                     // empties script and prompts, restarts generated tool-call ids
```

- One ordered script (a thread-safe queue of steps) serves both `call()` and `stream()`; a step is text (with optional chunks) or a list of tool calls. A tool-call step is streamed as a final chunk carrying the tool calls. Streaming and the tool loop run on other threads, hence thread-safe state.
- An empty script fails loudly: `call()` throws and `stream()` returns `Flux.error`, with a message that names "script exhausted" and prints the unexpected prompt (scenario "Script exhausted").
- The fake is test state in a cached context, so isolation is explicit: `FakeChatModelReset` (a `BeforeEachCallback` registered through `@IntegrationTest`; extensions do not change the context key) fetches the bean from the test's `ApplicationContext` and calls `reset()` (scenario "Isolation between tests"). Verified by an ordered pair of tests that leave state behind and then assert emptiness, red first by disabling the extension.
- The fake never calls out and has no model-specific behaviour (no usage metadata, no latency). Later changes that need token usage extend it then.
- Self-tests (`FakeChatModelTest`) go through the real `ChatClient`, not the fake's internals: text, recorded prompts, exhaustion, typed reply plus format instructions in the prompt, tool round trip with a `@Tool` POJO (second recorded prompt contains the tool result), streaming text, streaming tool loop, and the guard that `getOptions()` is a `ToolCallingChatOptions`. Text and typed-reply tests need no Spring context (`ChatClient.create(fake)`); tool and streaming tests use the injected `ChatClient.Builder` of the shared context, so they exercise the real wiring later changes use.

### D5. T1b split: the Anthropic wiring leaves the shared context

The dependency sources show that keeping the real `AnthropicChatModel` next to a fake needs `@Primary` and still leaves a model in the context that could be called; with `spring.ai.model.chat=none` the context has exactly one model, the fake. So:

- **Shared context** (`@IntegrationTest`): a `ChatClient.Builder` builds a client backed by the fake; the application exposes only actuator mappings and `/error`; `GET /` and `GET /chat` return 404; no `AnthropicChatModel` bean exists. The mapping check is behavioural where possible (404s through the client; every `RequestMappingInfoHandlerMapping` pattern starts with `/actuator` or is `/error`) because exact handler-mapping bean names were read from source, not observed.
- **`AnthropicWiringTest`**: an `ApplicationContextRunner` with `AutoConfigurations.of(AnthropicChatAutoConfiguration, ToolCallingAutoConfiguration)`. The model property comes from the real committed `application.yaml` through `ConfigDataApplicationContextInitializer` (loads `application.yaml` from the classpath; test resources contain no `application.yaml` that would shadow it). Asserts `getBean<AnthropicChatModel>().options.model == "claude-haiku-5-5"` and that construction with no key and `base-url=http://localhost:1` succeeds without network. "No network at construction" is read from source (`likely`); the test is the proof, and a failing construction means the guard URL is hit or a key is validated eagerly, which the task treats as a finding to report.
- The model id test is meaningful only because `application.yaml` sets the property; the library default is `claude-haiku-4-5`. This also catches the silently ignored 1.x property name.
- **Gap, accepted**: the full application with the real Anthropic model and no key is no longer started anywhere; the construction-without-key test plus the green full context stand in for it (see traceability).

### D6. Test environment hygiene in Gradle

`backend/build.gradle.kts` removes `ANTHROPIC_API_KEY`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_BASE_URL` and every `SPRING_AI_*` variable (relaxed binding maps them onto `spring.ai.*`) from the test task environment so a developer's key never reaches a test JVM (Spring AI and the SDK read them as fallbacks). Proof: `CredentialIsolationTest` asserts the test JVM has none of them; red by running `ANTHROPIC_API_KEY=leak ./gradlew :backend:test` before the removal. Whether `environment.remove(...)` suffices with the configuration cache is verified by that run; fallback is setting the variables to empty strings only if the guard test accepts blank.

### D7. T2: Flyway state (shared context)

The Boot-configured `Flyway` bean is injected: `info().pending()` is empty and `migrate()` reports `migrationsExecuted == 0` and `success`. While no migration file exists, the test also asserts that the `public` schema holds no application table (everything except `flyway_schema_history`), which is the existing scenario "No application tables without migrations". The change that adds the first migration replaces that assertion with the schema agreement check (D8). Both assertions are trivial until then; the tests say so in comments, and the empty-table test is skipped (`assumeTrue`) once a migration exists, so nothing forces its replacement: the change adding the first migration must do it.

### D8. T3: schema agreement

`SchemaAgreementTest` runs in the shared context after Flyway has migrated (startup). Table discovery: ArchUnit `ClassFileImporter` over `<root>.persistence.tables` (without `DoNotIncludeTests`: the fixture tables below are test classes and must be discoverable; the architecture rule of D10 is what keeps tables out of other packages) (the root is derived from `BackendApplication::class.java.packageName`, so no Gradle plumbing), keep the concrete classes assignable to `org.jetbrains.exposed.v1.core.Table` (abstract base tables with shared columns are skipped), and take `Class.forName(...).kotlin.objectInstance`. Guard: a discovered class without an `objectInstance` fails loudly with its name, so a table can never silently escape the check. The guard itself is tested against a fixture package in test sources (a non-object `Table` subclass, and an abstract base table with its concrete object that discovery must return alone) by passing the package name to the discovery function, so the discovery code takes the package as a parameter.

Assertion: `MigrationUtils.statementsRequiredForDatabaseMigration(*tables, withLogs = false)` is empty (`org.jetbrains.exposed:exposed-migration-jdbc:1.6.1`, `org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils`, not experimental; unlike `SchemaUtils` it includes DROP statements; the dependency sources). It must run inside a transaction (`Q1b`); the test uses Spring's `TransactionTemplate` over the Exposed `SpringTransactionManager` that the starter registers, fallback `transaction(Database.connect(dataSource))`. Vacuous until the first table exists: with an empty package the result is empty by construction. A committed test (`a table without a migration is detected`) points discovery at a fixture package (`testsupport.fixtures.unmigrated.UnmigratedProbeTable`, a table without migration) and asserts the required statements are not empty, so the detection path is exercised although the real package is empty. The documented vacuity goes in the test comment.

New dependency: catalog entry `exposed-migration-jdbc` (`version.ref = "exposed"`), `testImplementation` in `:backend` only.

### D9. T4: Flyway replay and immutability on a scratch schema

`MigrationHistoryTest` (shared context, injected `DataSource`) builds its own `Flyway` via the API with `schemas(<unique scratch schema>)` and a test-only location `classpath:db/test-migration` holding a trivial `V1`/`V2` (test resources; the real `db/migration` stays empty), plus a second case replaying the real `classpath:db/migration` from empty (vacuous now, meaningful with the first migration).

1. `migrate()` succeeds and `info().applied()` lists every migration.
2. Tamper: `UPDATE <scratch>.flyway_schema_history SET checksum = checksum + 1` for version 1, then `validate()` must throw `FlywayValidateException` (`validate()` fails on unapplied migrations, so it is called after `migrate()`).
3. Cleanup in a `finally`: `DROP SCHEMA <scratch> CASCADE` by SQL, because Flyway `clean` is disabled by default. The scratch name is unique per test (random suffix), and cleanup runs even when assertions fail.

The test-only location is not on the main classpath (`db/test-migration` is not scanned by Boot, which reads `db/migration`), so it can never reach production behaviour.

### D10. T5 and the one-context rule: ArchUnit

- `archunit-junit6` 1.5.1 (catalog entry `archunit-junit6`; aggregator for `archunit-junit6-api/-engine`, built against the pinned JUnit platform, runs next to Jupiter under `useJUnitPlatform()`). `src/test/resources/archunit.properties` sets `junit.displayName.replaceUnderscoresBySpaces=true`; `archRule.failOnEmptyShould` stays at its default.
- `ArchitectureTest` (`@AnalyzeClasses(packagesOf = [BackendApplication::class], importOptions = [ImportOption.DoNotIncludeTests::class])`): `noClasses().that().resideOutsideOfPackage("<root>.persistence.tables..").should().beAssignableTo(Table::class.java)` (anchored to the root package derived from `BackendApplication`). This form is non-vacuous from day one because the `that()` set (all backend classes) is never empty, unlike "Table subclasses should reside in the package" (which needs `allowEmptyShould` today). It is proven red once with a throwaway `Table` object in a wrong package.
- `TestConventionsTest` (imports test classes too, restricted to `..backend..`) makes the single-context decision executable (`spring-testing`, scenario "A test that customises the context is rejected"): outside `@IntegrationTest`, nothing is meta-annotated with `BootstrapWith` or `ContextConfiguration` (rejects slice annotations and `@SpringJUnitConfig`); no class or field carries `@MockitoBean(s)`, `@MockitoSpyBean(s)`, `@TestBean`, `@DirtiesContext`, `@TestPropertySource`/`@TestPropertySources`, `@ActiveProfiles`, `@ContextConfiguration`, `@ContextHierarchy`, `@ContextCustomizerFactories`, `@WebAppConfiguration` or `@SpringBootTest` (`@TestBeans` does not exist in Spring Framework 7.1) (except the `@IntegrationTest` annotation itself); no method carries `@DirtiesContext` or `@DynamicPropertySource`; no `@IntegrationTest` class carries another annotation that is, or is meta-annotated with, `@Import`, `@ImportAutoConfiguration` or `@PropertyMapping` (a custom `ArchCondition`; rejects `@AutoConfigureMockMvc`, `@AutoConfigureTestDatabase`, `@EnableXxx`), and no `@Configuration` or `@TestConfiguration` class is nested in an `@IntegrationTest` class (the rule selects every `@Configuration`-annotated or meta-annotated class and requires it not to be nested in an `@IntegrationTest` class, so the selected set is never empty, with no `allowEmptyShould`). The `@IntegrationTest` side checks every context-configuring or customising annotation type (`@Import`, `@ImportAutoConfiguration`, `@PropertyMapping`, the customisation list and `@BootstrapWith`), matching equivalent or meta-annotated types, so `@IntegrationTest @SpringJUnitConfig` is rejected. Its KDoc also states that tests must run sequentially. Red once with a throwaway test class carrying `@DirtiesContext`, and once with a throwaway `@IntegrationTest @SpringJUnitConfig` class and one with a nested `@TestConfiguration` (round 3).
- Naming: rules are `@ArchTest val` fields in snake_case (`tables_live_in_tables_package`), because ArchUnit uses the field name as the test name and `archunit.properties` turns the underscores into spaces; `config/detekt/detekt.yml` sets `naming: VariableNaming: ignoreAnnotated: ['ArchTest']` as the one exception. The `@ArchTest val` pattern without `@JvmField` is taken from the `archunit` skill, not independently compiled; the first rule is the proof.
- The rule `that().resideOutsideOfPackage` is evaluated on compiled classes; Kotlin file facades and companions are in scope and harmless here.

### D11. T9: secrets scan

Mechanism: JUnit, but as its own Gradle task so the scan does not slow the Docker-backed test task, and never goes stale because it always runs.

- `SecretScanner` (test sources, scanner over a directory tree, tested against temp directories: `scanTree(root)` walks, reads and returns findings with file and line) with its own `SecretScannerTest`: each pattern is detected in a temp-directory fixture, placeholders and allow-listed values are not. Patterns are built at runtime in the test (`"sk-ant-" + "x".repeat(40)`) so the test source does not match its own scan.
- `RepositorySecretsTest`, tagged `secrets-scan`, scans the real repository. In `backend/build.gradle.kts`, `tasks.test` excludes the tag and a registered `secretsScan` task of type `Test` includes only it, reusing the test source set, with `doNotTrackState(...)` and the repo root (computed once at configuration time) passed as a system property. The scanned set cannot be declared as inputs exactly (Gradle's file trees apply Ant default excludes such as `.gitignore` that the scanner does read), so the task tracks no state and runs on every build; it takes about a second. `tasks.check` depends on `secretsScan`.
- File set: every text file in the working tree (tracked or not; git is never asked) except the directories `build`, `.gradle`, `.git`, `.idea`, `.kotlin`, `node_modules` and `.claude/worktrees`, a deny-list of binary extensions, files with a NUL byte in the first 8 KiB (checked before the rest of the file is read), and the local-only `.env`, `*.key` and `.claude/settings.local.json` (they hold local secrets by design). Excluded directories are skipped as subtrees while walking. Files are decoded leniently as UTF-8.
- Patterns: Anthropic-shaped keys (`sk-ant-` plus 20+ key characters), generic `sk-` keys (32+ characters), AWS access key ids (`AKIA` + 16), GitHub tokens (`gh[pousr]_` + 36+), PEM private-key headers, JWT-shaped triples (`eyJ...`.`eyJ...`.`...`, the signature may be short or empty), `Bearer` followed by a 16+ character token, Spring Security encoded-password prefixes (`{noop}`, `{bcrypt}`, `{pbkdf2}`, `{scrypt}`, `{sha256}`, `{argon2}`) followed by a literal, and assignments to names containing `api key`, `secret`, `token` or `password` (`:` or `=`) (also `passwd` and `credential`) whose value is one quoted literal (whitespace allowed) or a bare token at the end of the line, including `{noop}...` values (so prose and calls are not read as credentials). Shape rules (keys, JWTs, private-key headers, URL userinfo with a literal password, also with a whole `${...}` user part, with or without default) apply to every file whatever the key. The assignment rule depends on the file kind: files that are not source check quoted and bare values, and in yaml, yml, properties and env files a name at the start of a line takes the rest of the line (up to a ` #` comment) as its value; source files (`.kt`, `.kts`, `.java`, `.gradle`, other code) check only quoted string literals, so `val token: String`, `lateinit var apiKey: String` and `val token = chunk.result.output.text` are never values while a quoted literal assigned to a `password` property is, also with a Kotlin type annotation (`val password: String = "..."`); a quoted value that is itself an env-var name or a property path of three or more segments containing a credential word (`"ANTHROPIC_API_KEY"`, `"spring.ai.anthropic.api-key"`) is a reference, not a value. A value is harmless when it is empty, `true`/`false`, a well-known word (`Bearer`, `Basic`, `Authorization`, `client_secret_basic`, `client_secret_post`, `private_key_jwt`, `none`), a plain URL (no userinfo, query or fragment: `?client_secret=...` is still found, and the rest of the line is scanned after a harmless match), a number or duration (`5m`, `PT5M`) next to a name without a `passw`, `secret`, `api key` or `credential` part (`DB_PASSWORD=20251119` is a finding), or the allow-listed literal for that name. Credentials embedded in a URL are a finding of their own, whatever the key. Real shapes (`sk-ant-`, JWT) are found regardless of the key. The plural `tokens` is not a credential name (`max-tokens: 1024`), and next to a token-only name (no password, secret, api key or credential part) a value shorter than 12 characters or with whitespace is model token text (`"token":"Hel"`; the future `TextDelta` fixtures), while `access_token: abc123def456` is a finding. A `${VAR}` placeholder is harmless; a `${VAR:default}`/`${VAR:-default}` default (greedy, nested braces allowed) is checked recursively with the same value rules (`${TOKEN_URI:http://localhost:9000/oauth2/token}` and `${TTL:5m}` pass, `${DB_PASSWORD:${LEGACY:hunter2}}` and `${DB_PASSWORD:Pr0dPass}` do not). Deliberately not detected: names containing a space, multi-line values, escaped JSON inside a Kotlin string, bare values in source files, a value with spaces or punctuation after a name that is not at the start of a line (or in a file that is not yaml, yml, properties or env), a short value (under 12 characters) next to a token-only name, and a Kotlin type annotation that is not a plain, generic or nullable type name.
- Allow-list: the documented local-development defaults, exactly the literal `demo` for `DB_USER`/`DB_PASSWORD`/`POSTGRES_USER`/`POSTGRES_PASSWORD` (`application.yaml`, `compose.yaml`, `README.md`), and nothing more. The allow-list is a small constant in the scanner, each entry with a reason.
- Red first: the repository test is run once with a planted key in a temp copy of a scanned file path (or a throwaway file) and must name file and line, then the plant is removed; the always-run behaviour is checked by running `secretsScan` twice (both execute) and once with a key planted in `.gitignore`, a file Gradle's own file trees would skip by default.
- Alternative considered: a Gradle verification task in the root build (apply the `base` plugin, wire into `check`). Equivalent coverage, but the scanner would be untestable code in a build script or `build-logic`; decided by the user (Resolved decisions 3).

### D12. Desktop tests

All under `desktop/src/test/kotlin/dev/frederikpietzko/springaidemo/desktop/`. Existing build already provides `compose.desktop.currentOs` (skiko natives), `kotlinx-coroutines-swing` (needed for `Dispatchers.Main` in `ui-test`) and `ui-test`; no build change is expected beyond optionally `jvmArgs("--enable-native-access=ALL-UNNAMED")` for the test task if skiko warns on JDK 25, and removing the "Declared for the tests that follow" comment.

- **Seam**: `fun App(themeOverride: ThemeMode? = null)` forwarding to `DemoTheme(override = themeOverride)`. The call site `App()` in `main.kt` stays unchanged. The deprecated internal `LocalSystemTheme` is not used.
- **T8 `DemoThemeTest`** (pure, no Compose): `resolveDarkTheme(null, systemDark)` follows the system; LIGHT/DARK override beats the system; light and dark colour sets differ in background and ink and keep the same accent gradient (check the `DemoColors` fields when writing it). Ported from oculi2's `OculiThemeTest` (MIT, same author).
- **T7 render**: port oculi2's `ScreenRendering.kt` helper (`renderComposeScene(width, height) { ... }` to a PNG under `build/screenshots/`) and tighten its blank check from "distinct colours or PNG larger than 5000 bytes" to distinct sampled pixel colours greater than one (the PNG-size escape hatch is a weak signal). `AppRenderTest`: `App(ThemeMode.LIGHT)` and `App(ThemeMode.DARK)` render non-blank; a background pixel equals the corresponding colour token (`toArgb()`) and the two differ. `ComponentsRenderTest` (`components draw with the active theme's colours`, parameterised over `dark in [false, true]`, ported from oculi2): a gallery of every `D*` component (`DText`, `DButton` in all styles incl. disabled, `DCard`, `DChip`, `DTextField`, `DToggle` on/off, `DSelect`, `DIconButton`, `DSpinner`) rendered under `DemoTheme(dark)`, non-blank, light pixel differs from dark pixel, and the pixel at a known position inside the `DCard` padding equals the theme's `surface` colour (a partial check that components take colours from the theme); no file-size assertion. The spinner animates through a `LaunchedEffect`, which a single synchronous frame does not settle, so for it the assertion is only "renders without throwing".
- **T6 semantics** (`AppSemanticsTest`): `@OptIn(ExperimentalTestApi::class)` and `androidx.compose.ui.test.v2.runDesktopComposeUiTest` (the non-deprecated v2 entry; it uses a standard test dispatcher so the test calls `waitForIdle()` after `setContent`): the application name and the placeholder are displayed, and there are no nodes with click action, text-set action or toggle semantics. No `ui-test-junit4` or JUnit 4.
- Render scenes are documented as not thread-safe; tests stay sequential.
- Linux CI note: skiko needs fontconfig and freetype on the runner; background-colour assertions do not depend on fonts, text-pixel assertions would. No CI exists in the repository, so this is recorded as a risk, not solved.

### D13. Documents

- **ADR-0003 "Domain and module topology"** (new, `docs/adr/0003-domain-and-module-topology.md`, Status accepted, Change `test-foundation`), content from `ROADMAP.md` sections 2, 3, 5, 6: the domain (kaiju insurance claims desk, glossary in the roadmap), the four modules (`backend`, `desktop` exist; `core-mcp`, `auth-server` planned, each added by a later change with its own `include`), data ownership (the backend never reads the core database; claims data reaches the agent only through MCP), one PostgreSQL instance with the databases `backend` and `core` (`backend` exists after this change, D15; `core` is added by `core-mcp-claims`), top-level packages per module under the base package (`...backend`, `...desktop`, `...coremcp`, `...authserver`; hyphen-free because Kotlin packages cannot contain hyphens), ports (backend 8080, core-mcp 8081, auth-server 9000), and the fallback for mcp-security on Spring AI 2.0 recorded as a pointer to ADR-0007 (renumbered, below). It states that ADR-0001's layout is extended, not contradicted (new modules go through `settings.gradle.kts` as in ADR-0001).
- **ADR-0004 "Test strategy"** (new, `docs/adr/0004-test-strategy.md`, Status accepted, Change `test-foundation`). It **supersedes** these parts of ADR-0002 and names them: the section "Deferred tests" (T1-T9 with their outcomes: T1b split into shared-context checks and a context-runner test, T1 no longer needs an image declared compatible with `postgres`, T2-T9 as built), the paragraph on Anthropic in tests (replaced by the fake-model decision: `spring.ai.model.chat=none`, scripted `FakeChatModel`, placeholder key, unroutable base URL, credentials removed from the test environment), and the consequences "`bootRun` and `generateMigrations` need Docker; `build` and `check` do not" (now `check` and `build` need Docker) "Without tests, regressions are caught only by the command checks ...", the statement "no `api-key` property exists in any file" (tests commit the placeholder `spring.ai.anthropic.api-key=test-placeholder`; production files still carry none; ADR-0002 gets only this status-line mention, its body is untouched), and the Quality gate statement "ArchUnit is not added yet; ...". It also supersedes the ADR-0001 consequence "No test is committed by the scaffold ...". It also records: the single shared context and `RANDOM_PORT` (D2), the Testcontainers pgvector container (D3), the fake chat model and its guarantees (D4), isolation rules for the shared database, the architecture rules (D10), the secrets scan (D11), the desktop test approach (D12), the new test dependencies (D14), and that `check` includes the tests and needs Docker (a separate source set or tag was considered and rejected: it splits the definition of done). Alternatives, consequences and the superseded-sections list are explicit.
- **ADR-0002**: only its status line changes, to say it is partially superseded by ADR-0004 and to name the superseded sections (the six listed above, including the Quality gate statement about ArchUnit). No other edit; the original text stays as history.
- **ADR-0001**: only its status line changes, to say it is partially superseded by ADR-0004 and to name the consequence "No test is committed by the scaffold ...". No other edit.
- **ROADMAP.md**: ADR-0004 is now taken, so every reserved later ADR number moves up by one: 0004 chat protocol -> 0005, 0005 vector store -> 0006, 0006 community libraries -> 0007, 0007 model routing -> 0008, 0008 security -> 0009, in sections 4, 6, 8 and 9 (the section 4 table references changes, not numbers, so only sections 6, 8, 9 are expected to change; verify by search). The section 6 table gets a new ADR-0004 row ("Test strategy", written by `test-foundation`, wave 0). The ROADMAP checkbox for `test-foundation` is ticked at archive. Only ROADMAP.md and the artifacts of this change reference those numbers today.
- **README**: link to `ROADMAP.md`, intro no longer says "no tests yet", `check` comment and Prerequisites say Docker is needed for `check`, the env table and commands name the database `backend`, a "Migrating an existing local database" note (`POSTGRES_DB` applies only to an empty volume; the old volume holds no tables, so `docker compose down -v` then `docker compose up -d`), and a short "Testing" section (shared context, fake chat model, `secretsScan`, how to run one test).
- **CLAUDE.md**: status line replaced (tests exist; `check` needs Docker); the pointer to `scaffold-project` becomes `test-foundation`.

### D15. Local database renamed to `backend`

Changes: `compose.yaml` `POSTGRES_DB: backend` and the healthcheck `pg_isready -U demo -d backend`; `application.yaml` default `DB_URL` `jdbc:postgresql://localhost:5432/backend`; README (env table, commands, the migration note of D13). User and password stay `demo` (local-only, allow-listed by the secrets scan). Any other mention found by `ide search --query 'localhost:5432/demo'` and `--query '-d demo'` is updated. `core` is not created here.

`POSTGRES_DB` is read only when the data directory is initialised, so an existing volume keeps the database `demo` and the backend would fail to connect to `backend`. Developers recreate the local volume; it holds no tables, so nothing is lost. The implementing task must **not** delete or recreate the developer's own `spring-ai-demo` compose volume or project. Verification uses an isolated throwaway: a uniquely named compose project (`docker compose -p <unique> -f compose.yaml -f <override in a temp directory>` with an override that publishes a free host port, or an equivalent `docker run` of `pgvector/pgvector:pg18` with the same environment), then `pg_isready`/`psql -l` shows the database `backend`; the backend started with `DB_URL` pointing at the throwaway port, `SPRING_DOCKER_COMPOSE_ENABLED=false` (so Boot does not touch the developer's project) and a free `SERVER_PORT` reports `/actuator/health` `UP`; finally only the throwaway project is removed (`docker compose -p <unique> down -v`). Red state: before the rename, the same throwaway has no database `backend`.

### D16. Kotlin reified extensions for `ChatClient`

Spring AI 2.1.0-RC2 ships no Kotlin extension files (no `*Extensions*` sources in any `org.springframework.ai` artifact on `:backend:compileClasspath`), so Kotlin code must write `.entity(Verdict::class.java)` and cannot express generics without a `ParameterizedTypeReference`. We add `backend/src/main/kotlin/dev/frederikpietzko/springaidemo/backend/springai/ChatClientExtensions.kt` in the package `...backend.springai`, deliberately not Spring AI's own package (no split packages). It follows the idea of Spring AI 1.x's `ChatClientExtensions.kt`: `inline fun <reified T> ChatClient.CallResponseSpec.entity()` and `responseEntity<T>()`, implemented through the `ParameterizedTypeReference` overloads so `entity<List<Verdict>>()` works (for example with a `object : ParameterizedTypeReference<T>() {}`). The implementer verifies the exact 2.1.0-RC2 `CallResponseSpec` overloads and their JSpecify nullability against the sources and lets the Kotlin return types follow them (no `!!`). Only extensions with a 2.1 Java counterpart are added; nothing speculative (no streaming or `Flux` variants).

Why: later features (`claim-intake` uses `entity<ClaimReport>()`) read better and type-safer with it, and it lives with the first tests that need it. It is a thin language adapter over an existing API, not an architectural abstraction or a new shared top-level concept, so **no ADR** is written (CLAUDE.md requires an ADR for new top-level packages and shared abstractions; the package is a small helper package under `backend` whose only content is extension functions, and it is recorded here and in the spec). Boundary: `ChatClient` over `FakeChatModel` (public API), covering a plain class, a generic list and the response entity. Misses: native provider structured output (not used).

### D14. Dependency and build summary

| Change | Where | First needed by |
| --- | --- | --- |
| `spring-boot-starter-test` -> `spring-boot-starter-webmvc-test` | `backend/build.gradle.kts` | shared context task |
| `archunit-junit6` (catalog) | backend `testImplementation` | T5 task |
| `exposed-migration-jdbc` (catalog) | backend `testImplementation` | T3 task |
| remove provider env vars (`ANTHROPIC_*`, `SPRING_AI_*`) from `tasks.test`; `secretsScan` task; tag exclusion | backend | guard test task; T9 tasks |
| optional `--enable-native-access=ALL-UNNAMED` | desktop `tasks.test` | desktop render task, only if noisy |

`spring-boot-testcontainers`, `testcontainers-postgresql` stay; `testcontainers-junit-jupiter` is no longer needed once only `@ServiceConnection` beans start containers, and is removed with the shared-context task if nothing uses it.

## Test boundaries

| Behaviour | Boundary | Catches | Misses |
| --- | --- | --- | --- |
| Backend wiring, health (T1) | HTTP `GET /actuator/health` through `RestTestClient` on the shared context (real server port, real PostgreSQL) | Exposed starter vs transaction manager conflicts, Flyway ordering, datasource binding, container wiring | runtime database outage, a real full-provider start (the context runs with `chat=none`) |
| Chat client builder, endpoints (T1b-a) | Beans plus HTTP 404 for `/` and `/chat`, handler-mapping patterns, in the shared context | stray endpoint, missing builder bean | model behaviour |
| Anthropic wiring (T1b-b) | `ApplicationContextRunner` with the two auto-configurations and the real `application.yaml` | wrong or renamed model property, eager key validation, construction touching the network | request behaviour, the rest of the application |
| Fake chat model | `ChatClient` (public API) over the fake: text, typed, tool loop, streaming | a fake that does not execute tools, mis-shaped streams, leaked state | real provider semantics |
| Flyway state (T2) | `Flyway` bean of the shared context | disabled Flyway, pending migrations, accidental DDL | content of future migrations |
| Schema agreement (T3) | `MigrationUtils` inside a transaction on the migrated database | table without migration, drifted column | vacuous until the first table |
| Migration history (T4) | Flyway API on a scratch schema of the same container | rewritten history, disabled validation, broken replay | the Flyway library itself |
| Architecture (T5, conventions) | ArchUnit on compiled classes | tables outside the package, a second test context, forbidden test annotations | behaviour |
| Secrets (T9) | scanner over a directory tree (tested against temp directories) plus a scan of the real working tree | a key, token or private key in a text file | secrets in unscanned or encoded form |
| Desktop theme logic (T8) | pure function and colour tokens | override/system precedence | rendering |
| Desktop semantics (T6) | Compose UI test on the semantics tree | accidental feature controls | pixels |
| Desktop render (T7) | off-screen render, pixel comparison | composition crash, blank screen, theme not applied | pixel-exact visual regressions |

## Traceability: ADR-0002 deferred tests (superseded by ADR-0004) to scenarios

| Test | Verifies (existing scenarios unless marked new) | Not covered |
| --- | --- | --- |
| T1 | `backend-runtime`: Healthy backend; Database is part of the report. Harness: Tests run with the compose database stopped (`TestDatabaseTest`: the datasource is the container's); database is pgvector-capable | `Starts with the compose database` (runtime, manual) |
| T1b-a | `backend-runtime`: Chat client builder available; No chat endpoint | |
| T1b-b | `backend-runtime`: Chat model configured with Haiku; Starts without a key (construction level). Harness: Provider wiring scenarios | full application start with the real model and no key |
| T2 | `backend-runtime`: Empty migration directory (state part: no pending, `migrate()` no-op); No application tables without migrations. `schema-migrations` (new): Startup leaves no pending migration | "startup log reports that no migration was applied" (log text) |
| T3 | `schema-migrations` (new): Migrations cover all tables; A table without a migration is detected (committed test with the `UnmigratedProbeTable` fixture); Every table is a discoverable object; Abstract base table is skipped (committed test with the `AuditedProbeTable` fixture) | |
| T4 | `schema-migrations` (new): Replay from empty; Edited migration is rejected | |
| T5 | `schema-migrations` (new): Table outside the package fails the architecture check. Harness: A test that customises the context is rejected | |
| T6 | `desktop-shell`: Window shows the empty screen (content and absence of controls; the window itself is not opened); Starts with nothing else running (the screen composes without backend) | window title and size |
| T7 | `desktop-shell`: Dark/Light system appearance (via forced appearance, new scenarios); Reusable themed components (render) | "Components are available" (compile), "No colour is hard-coded in a component" (partly covered: the gallery asserts the theme `surface` colour at a known position inside `DCard`) |
| T8 | `desktop-shell`: Light/Dark appearance resolution; Default follows the system (new) | |
| T9 | `backend-runtime`: No key in committed configuration. `build-and-quality` (new): Repository is clean; Planted key is detected; Scan is not skipped as up to date | |
| rename (D15) | `build-and-quality`: Local database starts (database `backend`); Default connection targets the compose database. Verified once by the throwaway project of task 8.1, not by an automated test | an automated consistency check between `compose.yaml` and `application.yaml` |
| reified extensions (D16) | `backend-runtime` (new): Typed reply for a plain class; Typed reply for a generic list; Typed response entity (task 3.5) | native provider structured output |
| fake self-tests | Harness: all fake-chat-model scenarios | |
| guards | Harness: Developer key does not leak; Test properties guard the provider | |
| shared context | Harness: Two test classes, one context (`SingleContextGuard`, unit-tested by `SingleContextTest`); build-and-quality: Check passes on the repository; Failing test fails the gate; Both modules contain tests | |

## Risks / Trade-offs

- [Docker becomes a hard requirement of `check` and `build`] -> stated in README and the spec; decided by the user, see Resolved decisions.
- [Release-candidate libraries: `getOptions()`, `ToolCallingAdvisor`, `RestTestClient` auto-configuration may move before GA] -> everything depends on one fake and one annotation, so a change is local; fake self-tests fail first.
- [Streaming tool loop shape was verified from source only] -> the streaming-tool red test is its own task early in the fake section; if the chunk shape needs adjusting the fake absorbs it.
- [`ApplicationContextRunner` no-network claim and `ConfigDataApplicationContextInitializer` loading `application.yaml` are source-read, not run] -> the wiring test is the first proof; report instead of working around a failure.
- [Shared database across tests] -> unique keys and cleanup rules (D3); scratch schemas for schema tests; sequential execution.
- [T2, T3 and the real-directory replay in T4 are vacuous until the first table] -> documented in the tests; T5 is non-vacuous; the first table change must replace the T2 empty-table assertion and may tighten T3 with a discovery-count check.
- [The context-runner test cannot see the full application] -> accepted gap, see D5.
- [Scanner false positives on future docs] -> the allow-list is explicit and small; a new legitimate value is added with a reason, never by loosening a pattern.
- [Skiko on Linux needs fonts and native libs; desktop render tests are macOS-evidenced only] -> background-colour assertions avoid fonts; no CI exists yet; note in README testing section.
- [`@ServiceConnection` bean must return the concrete container type] -> a comment on the bean; a wrong type would fail the first context start, not pass silently.
- [`kotlin-spring` and detekt on ArchUnit fields and test names] -> snake_case rule names (the field name is the test name) with the single detekt exception `VariableNaming: ignoreAnnotated: ['ArchTest']`; run `:backend:detekt` in the architecture task.

## Resolved decisions (user, 2026-11-19)

All questions raised by the planner are answered; no open questions remain.

1. **Database naming.** The user chose to rename now, overriding the planner's recommendation (leave it to `core-mcp-claims`): the compose database becomes `backend` in this change (D15, tasks 8.x). Adding `core` stays with `core-mcp-claims`. Existing local volumes must be recreated (README note).
2. **Docker for `check`.** Accepted as recommended: tests stay in `check`, Docker is required; only `evaluation` (needs a real key) will be separate.
3. **T9 mechanism.** The planner's design stands: JUnit-based `secretsScan` Gradle task with its own tag, wired into `check` (D11); as built it tracks no inputs and runs on every build instead of declaring inputs.
4. **ADR-0002.** The user chose a new superseding ADR, overriding the planner's recommendation (amend in place): ADR-0004 (test strategy) supersedes the named sections of ADR-0002, whose status line gets a partial-supersession note and nothing else; ROADMAP's reserved ADR numbers 0004-0008 move to 0005-0009 (D13).
5. **Names.** Kept: `@IntegrationTest`, `TestInfrastructure`, `FakeChatModel`, package `...backend.testsupport`, module packages `coremcp` and `authserver`.
