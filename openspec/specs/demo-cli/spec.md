# demo-cli Specification

## Purpose

The `kaiju` command line: one executable file that sends single commands straight to the running desktop app, reads back its state, and seeds the demo data, with help on every command and exit codes an agent or a shell script can rely on.

## Requirements

### Requirement: One self-contained executable file built by Gradle
The command `./gradlew :cli:installKaiju` SHALL produce the single executable file `kaiju` in the project root, a native executable that contains everything it needs and runs without a Java installation. The file SHALL NOT be committed, and `./gradlew check` SHALL NOT need the tools that build it.

#### Scenario: Built file runs
- **WHEN** `./gradlew :cli:installKaiju` has run and `./kaiju --help` is started
- **THEN** the help text is printed and the exit code is 0

#### Scenario: No Java needed
- **WHEN** `./kaiju state --json` runs with no `java` on the `PATH` and no `JAVA_HOME`
- **THEN** it behaves as with Java installed

#### Scenario: Seed files inside
- **WHEN** `./kaiju seed` runs from a directory other than the project root
- **THEN** it finds the seed SQL and applies it

#### Scenario: Not under version control
- **WHEN** the file `kaiju` exists in the project root
- **THEN** `git status` does not list it

### Requirement: Every command has help
`kaiju --help` SHALL list every command with a one-line description, and `kaiju <command> --help` SHALL describe that command, its arguments and its options, for the direct commands, `seed`, and every `demo` subcommand. Started without arguments, `kaiju` SHALL print the help.

#### Scenario: Root help lists the commands
- **WHEN** `kaiju --help` runs
- **THEN** the output lists `persona`, `new-conversation`, `open`, `send`, `prepare-claim`, `confirm-claim`, `approve`, `deny`, `typing-speed`, `highlight`, `wait-idle`, `sleep`, `reply`, `state`, `seed` and `demo`

#### Scenario: Every command describes itself
- **WHEN** `--help` is given to each command and to each `demo` subcommand
- **THEN** each prints a description and its usage with exit code 0

#### Scenario: Wrong usage
- **WHEN** `kaiju persona wizard` runs
- **THEN** the exit code is 1 and the message names the allowed values `claimant` and `adjuster`

### Requirement: Direct commands go straight to the desktop app
Each of the commands `persona`, `new-conversation`, `open`, `send [--instant]`, `prepare-claim`, `confirm-claim`, `approve`, `deny`, `typing-speed`, `highlight`, `wait-idle`, `sleep` and `reply` SHALL send the matching control command to the desktop app's endpoint, with no other process involved, and print the answer. An argument is one value, whatever spaces or quotes it holds. The address is `KAIJU_CONTROL_URL`, default `http://127.0.0.1:8765`.

#### Scenario: Argument with spaces
- **WHEN** `kaiju send "Process claim #4711, please"` runs against a running desktop app
- **THEN** the message sent to the backend is `Process claim #4711, please` and the exit code is 0

#### Scenario: Option
- **WHEN** `kaiju send --instant "Hi"` runs
- **THEN** the endpoint receives the command `send --instant "Hi"`

#### Scenario: Quotes inside an argument
- **WHEN** `kaiju send 'He said "hi"'` runs
- **THEN** the message sent is exactly `He said "hi"`

#### Scenario: Reply is printed
- **WHEN** the newest assistant message is "Paid" and `kaiju reply` runs
- **THEN** standard output is `Paid`

#### Scenario: Other address
- **WHEN** `KAIJU_CONTROL_URL=http://127.0.0.1:9999` is set and the app listens there
- **THEN** the command reaches it

#### Scenario: No daemon needed
- **WHEN** a direct command runs while no demo daemon is running
- **THEN** it succeeds and no daemon is started

### Requirement: The state is readable by people and by agents
`kaiju state` SHALL print a readable summary: the persona, whether an answer is streaming, the text in the input field, the highlighted control, the pending confirmation (tool and arguments), the todo items, the conversation titles, the messages in order (author, text, error, claim card and analytics table as one line each) and which controls are enabled. `kaiju state --json` SHALL print the endpoint's JSON unchanged and nothing else.

#### Scenario: Summary
- **WHEN** the user message "Hi" was answered with "Hello" and `kaiju state` runs
- **THEN** the output has the lines `persona: CLAIMANT`, `streaming: no`, `user: Hi` and `assistant: Hello`

#### Scenario: Pending confirmation in the summary
- **WHEN** a confirmation for `issuePayout` with the arguments `{"claimNumber":4711}` is pending
- **THEN** the summary shows `confirmation pending: issuePayout {"claimNumber":4711}` and `approve` and `deny` among the enabled controls

#### Scenario: JSON for agents
- **WHEN** `kaiju state --json` runs
- **THEN** standard output is the JSON snapshot only, parseable as one JSON object

### Requirement: Failures are told apart by exit code
A direct command SHALL exit with 0 when the endpoint answers with status 200, with 1 and the endpoint's message on standard error when it refuses (status 400, 409, 422 or 408), and with 2 when no endpoint answers, with a hint to start the desktop app with `DESKTOP_CONTROL_PORT` set.

#### Scenario: Refused command
- **WHEN** `kaiju approve` runs while no confirmation is pending
- **THEN** the exit code is 1 and standard error holds the reason

#### Scenario: Wait timed out
- **WHEN** `kaiju wait-idle 100ms` runs while the answer keeps streaming
- **THEN** the exit code is 1 and standard error holds `timeout`

#### Scenario: Wait ended in an error reply
- **WHEN** `kaiju wait-idle` runs and the reply ends in the error "The claims desk is unavailable."
- **THEN** the exit code is 1 and standard error holds `error: The claims desk is unavailable.`

#### Scenario: Pending confirmation is not a failure
- **WHEN** `kaiju wait-idle` runs and the reply ends in a confirmation request for `issuePayout`
- **THEN** the exit code is 0 and standard output starts with `idle: confirmation pending (issuePayout)`

#### Scenario: Desktop app not running
- **WHEN** `kaiju state` runs and nothing listens at the endpoint address
- **THEN** the exit code is 2 and standard error mentions `DESKTOP_CONTROL_PORT`

### Requirement: Seeding from the command line
`kaiju seed [reset|desk-queue]` SHALL apply the named `core-mcp` seed set to the `core` database of the compose PostgreSQL, or `reset` and then `desk-queue` when no name is given. The SQL SHALL be the files of `core-mcp`, taken into the executable when it is built, so the executable runs from any directory and needs no `core-mcp` code. It SHALL exit with 1 and a message that the database is not running, and how to start it, when the compose PostgreSQL is not up.

#### Scenario: Default seed
- **WHEN** `kaiju seed` runs with the compose database up
- **THEN** the sets `reset` and `desk-queue` are applied in that order and the exit code is 0

#### Scenario: One set
- **WHEN** `kaiju seed reset` runs
- **THEN** only the set `reset` is applied

#### Scenario: Unknown set
- **WHEN** `kaiju seed nonsense` runs
- **THEN** the exit code is 1 and the message lists `reset` and `desk-queue`

#### Scenario: Database not running
- **WHEN** `kaiju seed` runs while the compose PostgreSQL is stopped
- **THEN** the exit code is 1 and the message says the database is not running and how to start it

#### Scenario: The SQL is core-mcp's
- **WHEN** the executable is built
- **THEN** the bundled seed sets are byte for byte the files of `core-mcp`, and no other set is bundled

### Requirement: Example scripts stay valid
The repository SHALL hold example demo scripts under `scripts/demos/`, and every line of every example SHALL be valid in the script syntax (a control command, a `seed` line, a comment or a blank line).

#### Scenario: Examples parse
- **WHEN** every file in `scripts/demos/` is parsed with the script parser
- **THEN** no line is rejected
