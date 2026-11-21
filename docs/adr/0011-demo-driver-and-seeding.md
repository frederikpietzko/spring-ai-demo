# ADR-0011: Demo driver and seeding

- Status: accepted
- Date: 2026-11-20
- Change: `desktop-script-driver`
- Extends ADR-0001 (multi-module layout): two new modules, the shared library module `control-protocol` and the runtime artifact `cli`. Extends ADR-0003 (data ownership) and ADR-0004 (test strategy) with the addenda named there.

## Context

Live demos and manual UI checks of the desktop app were driven by hand, and the demo data drifted (a payout is paid once). A presenter needs a rehearsable, visible script; an agent needs a way to press the app's controls, wait and read the screen; both need a known database state. Details and scenarios: `openspec/specs/desktop-control`, `demo-cli`, `demo-daemon`, `demo-seed`.

## Decision

### 1. Control endpoint inside the desktop app

`desktop.control` runs a JDK `com.sun.net.httpserver` server on `127.0.0.1`, started by `main` only when `DESKTOP_CONTROL_PORT` holds a valid port. One route, `POST /command`, body = one command line; 200 ok, 400 bad line, 403 `Origin` present, 405 other method, 408 wait timed out, 409 control not usable, 422 the awaited reply ended in an error. Commands call the same `ChatViewModel` functions the controls' clicks call; the "is this control usable" rules live in `ChatState` and are shared by `App` and the endpoint.

Rejected: driving the Compose semantics tree of a running window (internal API only).

### 2. Visible effects

The input text lives in `ChatState`, so `send` types into the real field character by character (default 40 ms, `typing-speed`). `PressFeedback` holds the one pressed control; `Modifier.pressFeedback` draws a 3 dp accent outline just outside the control (so it also shows around accent-filled buttons) plus a translucent accent tint (about 15 percent) over it, so it is easy to see from a distance, and sets the state description `pressed` for the highlight time (default 300 ms, `highlight`), then the click runs.

Rejected: OS-level automation (`java.awt.Robot`, AppleScript): needs permissions and focus, depends on pixels.

The persona switch is opened visibly: `persona <name>` highlights the switch, opens its drop-down and holds it open for the highlight time, highlights the option (`pressed` is `persona:<name>`), then picks it and closes the drop-down. `DSelect` takes a hoisted open state and an `optionModifier`; the open flag (`PersonaMenu`) is shared by `App` and the endpoint like `PressFeedback`, and a user's click toggles the same flag. The chat input is a text area that grows up to five lines (Enter sends, Shift+Enter adds a line), so a long typed prompt is visible in full.

### 3. Pacing lives in the runner

An unattended run is paced by `DemoRunner`, not by the desktop: after a `wait-idle` it pauses for the read pause (default 4 s), between two actions for the pace (default 1200 ms), otherwise not; pauses never stack and there is none after the last or a failed line. The script-only lines `pace <duration>` and `read-pause <duration>` change the values for the rest of a run; `parseCommand` rejects them, so the desktop endpoint answers 400. `demo run` and the daemon use these defaults (`Pacing.DEMO`); step mode and direct commands are not paced, so agents and the endpoint's "return once the click happened" contract stay fast. What belongs to one press (the highlight, the open persona drop-down) stays in the desktop.

Rejected: pausing inside the desktop after every action (slows agents and UI tests, needs a mode the endpoint does not have); a reading pause scaled by reply length (needs a `reply` per wait and a tuning rule).

### 5. One parser in `control-protocol`

The command and script syntax (tokenising with `\"`/`\\`, durations, the command set, `render(command)` as the parser's inverse, `parseScript(text)` with line numbers and the client-side `seed [set]` line) and the `state` snapshot type live in the library module `control-protocol`. It has no runtime and depends only on `chat-protocol` and the serialization runtime (ADR-0005 pattern). `desktop` and `cli` use it, so quoting cannot drift and a script is checked before its first line runs.

Rejected: the CLI forwards lines blindly to the app (a typo on line 12 surfaces in front of the audience).

### 6. The `kaiju` command line: module `cli`, one native executable

`cli` uses the `demo.kotlin-jvm` convention plugin, Clikt (`clikt-core`, no terminal library), kotlinx.serialization, coroutines and the JDK `HttpClient`/`HttpServer`. Direct commands render a typed `Command` and POST it to `KAIJU_CONTROL_URL` (default `http://127.0.0.1:8765`). Exit codes: 0 ok, 1 refused or wrong usage, 2 desktop app not reachable, 3 daemon could not start.

The GraalVM native image (`org.graalvm.buildtools.native`) is the deliverable: `./gradlew :cli:installKaiju` writes the git-ignored `./kaiju`, which needs no JDK. GraalVM 25 is used for `nativeCompile` only, through a Gradle toolchain (`vendor = GRAAL_VM`, `nativeImageCapable`, provisioned by the foojay resolver); with `GRAALVM_HOME` set, that installation is used instead. Everything else builds with the JDK 25 toolchain. Native-image metadata lives in `cli/src/main/resources/META-INF/native-image/`. The build identity is the SHA-256 of the executable.

Rejected: a fat jar with a shell stub (needs a JDK, slow start). Recorded backup: a self-extracting `jlink` runtime image.

### 7. The demo daemon owns only the running demo

The same executable relaunches itself detached as `kaiju daemon` (output to `.kaiju/daemon.log`); on the JVM the launcher refuses. State directory `.kaiju/` next to the executable (git-ignored), or `KAIJU_STATE_DIR`. Single instance: the daemon holds an exclusive `FileChannel` lock on `daemon.lock` for its lifetime and writes `daemon.json` `{pid, port, build}`; a CLI checks and starts under `start.lock`. `GET /health` answers the build; another build is shut down (`POST /shutdown`) and replaced. Idle exit after 5 minutes (`KAIJU_DAEMON_IDLE`); a running or `waiting-step` demo counts as active. Loopback HTTP on an ephemeral port, JSON, `Origin` refused. `DemoRunner` is the one executor of script lines, used by the daemon and by `demo run`; a demo stops at the first failing line. The daemon keeps nothing about the screen: direct commands and `state` never touch it.

Rejected: a unix domain socket (macOS path limit, the JDK `HttpClient` cannot speak it).

### 8. Seeding: SQL owned by `core-mcp`, applied by `./kaiju seed`

`core-mcp/src/main/resources/demo-seed/reset.sql`, `desk-queue.sql` and `back-office.sql` are plain idempotent SQL, authored and tested by `core-mcp`. The `cli` build copies them into its resources (a file-level input, no dependency on `core-mcp` code); a test asserts the bundled files equal `core-mcp`'s byte for byte and the `SeedSet` enum lists exactly them. The `SeedRunner` adapter runs `docker compose -f <compose.yaml> exec -T postgres psql -v ON_ERROR_STOP=1 -U demo -d core` with the SQL on standard input (`compose.yaml` next to the executable, else `KAIJU_COMPOSE_FILE`).

Rejected: seeding through MCP tools (cannot restore `FILED` or remove a payout).

### 10. Security stance

The endpoint can approve a payout, so it is loopback-only, off by default, refuses requests with an `Origin` header and answers no other method or path. The daemon has the same posture. No token, as for the other local endpoints (ADR-0009); the payout itself stays behind the backend's confirmation flow, which the control only presses like a user.

Rejected: a token on the endpoint (no gain for a local, same-user tool; every script would need it).

## Consequences

- `settings.gradle.kts` includes `control-protocol` and `cli`. `./gradlew check` runs their JVM tests and needs no GraalVM; `:cli:nativeSmokeTest` (tag `native`) needs it and is not part of `check`.
- The CLI is dev tooling like Flyway: it is not a runtime path from `backend` or `desktop` into the database `core` (ADR-0003 stays intact).
- Follow-ups: screenshots for agents, assertion commands, a drawn pointer, a visible caret, clearing the backend's stored conversations, a Windows build.
