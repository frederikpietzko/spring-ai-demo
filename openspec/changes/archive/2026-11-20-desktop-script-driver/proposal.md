# Proposal

## Why

Live demos and manual UI checks of the desktop app are driven by hand: switch the persona, click "New conversation", type "Process claim #4711", wait for the answer, click "Approve". A speaker cannot rehearse that reliably, and an agent that should run a manual UI test has no way to press anything or to see what the screen shows. The data the demo needs (claims in a known state) also drifts: a payout is paid once, so a second run of the same demo starts from a different database.

## What Changes

- The desktop app gets an opt-in, loopback-only control endpoint. It acts **visibly**, because the demo is shared live: `send` types the text into the chat input character by character at a configurable pace and then presses the Send button; every other action (persona switch, "New conversation", opening a conversation, "Prepare claim", "Confirm", "Approve", "Deny") briefly highlights the control it presses and then runs the control's own click code, so the audience sees what a user would do. It also waits (`wait-idle` for the end of an answer, `sleep` for pacing) and reports what the screen shows as JSON.
- A line-based script format, one command per line, for demos and agents, and a Kotlin command line, `./kaiju` (new Gradle module `cli`, Clikt, help on every command), built by Gradle (GraalVM native image) into one self-contained executable file in the project root, git-ignored, no JDK needed to run it. Direct commands go straight to the desktop app; `kaiju state` prints a readable summary (`--json` for agents); exit codes tell refusal (1), unreachable app (2) and daemon failure (3) apart. An error reply fails `wait-idle`.
- A demo daemon (the same native executable relaunching itself, auto-started, single instance, idle exit after 5 minutes, replaced when the build differs) that owns only the running demo: `kaiju demo start <file> [--step]`, `demo next`, `demo status`, `demo stop`, plus a foreground `demo run <file>`. A demo stops at the first failing line. The desktop app stays the single source of truth for the screen.
- One command-syntax parser, shared by the desktop endpoint and the CLI in a new library module `control-protocol` (like `chat-protocol`), so a script is validated before the first line runs.
- Demo seeding: SQL seed sets owned by `core-mcp` (`reset` restores the migration-seeded scenario state, `desk-queue` adds claims in several states), applied by `kaiju seed` (the SQL is bundled into the executable at build time) and also usable as a line of a script file.
- ADR-0011 records the control endpoint, the two new modules, the executable, the daemon and the seed tooling; the README documents them.

## Capabilities

### New Capabilities

- `desktop-control`: the opt-in control endpoint of the desktop app: commands, the state snapshot, waiting, the script line syntax and the access rules.
- `demo-cli`: the `kaiju` executable: build, help, direct commands, `state`, `seed`, exit codes, example scripts.
- `demo-daemon`: the demo daemon and the `kaiju demo` commands: auto-start, single instance, idle exit, version replacement, start/step/next/status/stop, run in the foreground.
- `demo-seed`: the `core-mcp` seed sets `reset` and `desk-queue` and what each leaves in the `core` database.

### Modified Capabilities

None. `desktop-chat` is unchanged: the control endpoint calls the same view-model actions and adds no screen behaviour.

## Impact

- `desktop`: new package `desktop.control` (`DesktopControl`, press feedback, HTTP server using the JDK's `com.sun.net.httpserver`, no new dependency); `main.kt` starts it when `DESKTOP_CONTROL_PORT` is set; `App` accepts a prepared `ChatViewModel` and a press-feedback holder; the input text moves from local Compose state into `ChatState` so a command can fill the visible field; the "can the user do this now" rules move from `App` into `ChatState` so the buttons and the control endpoint share them; each controllable control carries a small highlight modifier.
- `core-mcp`: `src/main/resources/demo-seed/*.sql`; no Kotlin main code, no new table, no migration.
- New library module `control-protocol` (the command parser and syntax, durations, the state snapshot type; depends on `chat-protocol`; the parser written in `desktop.control` moves here) and new module `cli` (Clikt, kotlinx.serialization, coroutines; GraalVM native-image plugin; tasks `nativeCompile`, `installKaiju`, `nativeSmokeTest`; bundles `core-mcp`'s `demo-seed` files as resources at build time). `.gitignore` gains `/kaiju` and `.kaiju/`. Example scripts in `scripts/demos/`.
- `docs/adr/0011-demo-driver-and-seeding.md` (also the two new modules, ADR-0001), short addenda to ADR-0003 and ADR-0004, README section.
- Out of scope / follow-ups: assertion commands (`expect ...`), screenshots, OS-level mouse and keyboard events (the typing and the press feedback are drawn by the app itself), a visible mouse pointer, opening the persona drop-down list, clearing the backend's stored conversations, a Windows build of the CLI (the sources are portable, the build and the smoke test are only tried on macOS and Linux), a jlink self-extracting variant (recorded as the backup), driving anything other than the desktop app.
