# demo-daemon Specification

## Purpose

A small background process that owns only the currently running demo (a script file and its progress), so a presenter or an agent can start a script, step through it and ask where it is, while the desktop app stays the single source of truth for everything on screen.

## Requirements

### Requirement: The daemon starts itself and exists once
The first `kaiju demo start`, `next`, `status` or `stop` SHALL start the demo daemon when none is running, as a detached background process of the same executable (the file relaunches itself), and use it. At most one daemon SHALL run per state directory, also when several commands start at the same moment. The state directory is `.kaiju/` next to the executable, or `KAIJU_STATE_DIR`.

#### Scenario: Auto-start
- **WHEN** no daemon runs and `kaiju demo status` is run
- **THEN** a daemon is started, the command prints that no demo exists and exits with 0

#### Scenario: Reuse
- **WHEN** a daemon runs and `kaiju demo status` is run again
- **THEN** no second daemon is started

#### Scenario: Concurrent start
- **WHEN** two `kaiju demo status` commands start at the same moment with no daemon
- **THEN** exactly one daemon runs afterwards and both commands succeed

#### Scenario: Stale lock
- **WHEN** the lock file names a daemon that is not running and a demo command is run
- **THEN** the stale lock is replaced and a new daemon is started

#### Scenario: Daemon cannot be started
- **WHEN** the daemon does not answer within its start timeout
- **THEN** the command exits with 3 and the message names the daemon's log file

### Requirement: The daemon exits when idle
The daemon SHALL exit after 5 minutes, or the time in `KAIJU_DAEMON_IDLE`, during which no demo was running (a demo waiting for `next` counts as running) and no request arrived, and SHALL remove its lock file. The next demo command starts a new one.

#### Scenario: Idle exit
- **WHEN** the idle time is 200 ms and no demo runs and no request arrives for 200 ms
- **THEN** the daemon has exited and its lock file is gone

#### Scenario: A waiting demo keeps it alive
- **WHEN** a step-mode demo waits for `next` longer than the idle time
- **THEN** the daemon is still running

#### Scenario: Restart after exit
- **WHEN** the daemon has exited and `kaiju demo status` runs
- **THEN** a new daemon is started

### Requirement: A daemon of another build is replaced
A `kaiju` command SHALL compare its build identity with the running daemon's. When they differ, it SHALL stop that daemon and start one of its own build, before running the command. A demo running in the replaced daemon is lost and `status` of the new one says so.

#### Scenario: Newer build
- **WHEN** a daemon of build A runs and a `kaiju` of build B runs `demo status`
- **THEN** the daemon of build A has exited, a daemon of build B runs, and the answer comes from it

#### Scenario: Same build
- **WHEN** the same build runs `demo status` twice
- **THEN** the daemon is not restarted

### Requirement: The daemon is reachable from this machine only
The daemon SHALL listen on the loopback address only, on a port it records in its lock file, SHALL refuse requests with an `Origin` header with status 403 and SHALL answer nothing but its own paths.

#### Scenario: Loopback only
- **WHEN** the daemon runs
- **THEN** it answers on `127.0.0.1` and not on another address of the machine

#### Scenario: Request from a web page
- **WHEN** a request with the header `Origin: http://example.test` reaches the daemon
- **THEN** the answer is status 403 and nothing is started

### Requirement: A demo is started from a script file
`kaiju demo start <file>` SHALL read the file, check every line before running any (blank lines and lines starting with `#` are skipped; every other line is a control command, a `seed` line, or a pacing line `pace <duration>` or `read-pause <duration>`) and refuse the whole file when a line is invalid, naming the file and the first bad line. A valid file SHALL start running in the background at once and the command SHALL return with 0. A demo SHALL NOT be started while another one is running.

#### Scenario: Valid file starts
- **WHEN** `kaiju demo start talk.kaiju` runs with a valid file
- **THEN** the command returns with exit code 0 before the script has finished and `kaiju demo status` says `running`

#### Scenario: Invalid line
- **WHEN** line 4 of the file is `dance`
- **THEN** the exit code is 1, the message names the file and line 4, and nothing of the file ran

#### Scenario: Bad pacing duration
- **WHEN** line 2 of the file is `pace soon`
- **THEN** the exit code is 1, the message names the file, line 2 and the value `soon`, and nothing of the file ran

#### Scenario: Missing file
- **WHEN** `kaiju demo start missing.kaiju` runs
- **THEN** the exit code is 1 and the message says the file does not exist

#### Scenario: One demo at a time
- **WHEN** a demo is running and `kaiju demo start other.kaiju` runs
- **THEN** the exit code is 1 and the message says to stop the running demo first

### Requirement: The demo runs the script's lines in order and stops at the first failure
The daemon SHALL run the lines of the script in order: a control command is sent to the desktop app's endpoint (the daemon holds no UI state), a `seed` line is applied by the daemon like `kaiju seed`. The demo SHALL stop at the first line that fails (status 400, 409, 422, 408, an unreachable desktop app, or a failed seed) with the state `failed` and the error kept; a `wait-idle` that ends in an error reply fails like any other line. Lines after it SHALL NOT run.

#### Scenario: Whole script
- **WHEN** a file holds `persona adjuster`, `send "Hi"`, `wait-idle` and `reply` and each succeeds
- **THEN** the four commands reach the desktop app in that order and `status` ends as `done`

#### Scenario: Seed line
- **WHEN** a file starts with `seed` followed by `persona adjuster`
- **THEN** the seed is applied first, then the persona command runs

#### Scenario: Stops at a refusal
- **WHEN** the second line of a three-line script is refused with status 409
- **THEN** the third line does not run, `status` says `failed` at line 2 and shows the refusal

#### Scenario: Stops at an error reply
- **WHEN** a script holds `send "Hi"`, `wait-idle` and `reply` and the reply ends in an error
- **THEN** `reply` does not run and `status` says `failed` at the `wait-idle` line with the error message

#### Scenario: Desktop app not running
- **WHEN** a demo starts and nothing listens at the control endpoint
- **THEN** `status` says `failed` at the first command line with a hint to start the app with `DESKTOP_CONTROL_PORT`

### Requirement: Step mode runs one line per `next`
`kaiju demo start <file> --step` SHALL start the demo paused (state `waiting-step`, current line = the first command line). Each `kaiju demo next` SHALL run exactly that line, wait until it has finished, print its output and exit like a direct command would (0, 1 or 2), then leave the demo paused at the next command line, or `done` after the last. Blank and comment lines are skipped without a `next`.

#### Scenario: Paused at the start
- **WHEN** `kaiju demo start talk.kaiju --step` runs
- **THEN** nothing has reached the desktop app and `status` says `waiting-step` with the first command line as the current line

#### Scenario: Next runs one line
- **WHEN** `kaiju demo next` runs twice
- **THEN** exactly the first two command lines reached the desktop app, in order, and `status` says `waiting-step` at the third

#### Scenario: Last line
- **WHEN** `kaiju demo next` runs the last command line
- **THEN** `status` says `done`

#### Scenario: Next prints the output
- **WHEN** the line to run is `reply` and the newest assistant message is "Paid"
- **THEN** `kaiju demo next` prints `Paid` and exits with 0

#### Scenario: Next fails with the line
- **WHEN** the line to run is refused with status 409
- **THEN** `kaiju demo next` exits with 1 with the reason and `status` says `failed`

#### Scenario: Next without a paused demo
- **WHEN** no demo is paused and `kaiju demo next` runs
- **THEN** the exit code is 1 and the message says there is nothing to run

### Requirement: The demo's progress can be asked for and stopped
`kaiju demo status` SHALL print the file, the current line (number and text), the state (`none`, `running`, `waiting-step`, `failed`, `done` or `stopped`) and, when failed, the error; `--json` SHALL print the same as one JSON object. `kaiju demo stop` SHALL stop a running or paused demo, also during a wait, and set the state `stopped`; it SHALL succeed when no demo runs.

#### Scenario: Status while running
- **WHEN** a script is at its `wait-idle` on line 5 and `kaiju demo status` runs
- **THEN** the output shows the file, `line 5: wait-idle` and `state: running`

#### Scenario: Status as JSON
- **WHEN** `kaiju demo status --json` runs
- **THEN** standard output is one JSON object with the file, line, state and error

#### Scenario: Stop during a wait
- **WHEN** a demo waits in `wait-idle 5m` and `kaiju demo stop` runs
- **THEN** the demo stops without running further lines and `status` says `stopped`

#### Scenario: Stop when nothing runs
- **WHEN** no demo runs and `kaiju demo stop` runs
- **THEN** the exit code is 0

### Requirement: The daemon owns only the demo, never the screen
The daemon SHALL keep nothing about the desktop's conversation, input or controls. A direct command or `kaiju state` SHALL give the same answer whether or not a daemon or demo exists, and a daemon that is stopped or replaced mid-demo SHALL leave the desktop app exactly as it is.

#### Scenario: State without a daemon
- **WHEN** no daemon runs and `kaiju state` runs
- **THEN** the state comes from the desktop app and no daemon is started

#### Scenario: Daemon replaced mid-demo
- **WHEN** a demo has typed "Hel" into the input and the daemon is replaced by another build
- **THEN** `kaiju state` still shows the input and the conversation as they are, and `kaiju demo status` says no demo exists

### Requirement: A script can also run in the foreground
`kaiju demo run <file>` SHALL check and run the script in the calling process without a daemon, print each command before it runs followed by its output, and stop at the first failing line with the exit code that line's direct command would give (1 or 2), naming the file and the line on standard error. Seed lines are applied like `kaiju seed`.

#### Scenario: Foreground run
- **WHEN** a file holds `persona adjuster`, `send "Hi"`, `wait-idle` and `reply`
- **THEN** each command is printed and run in order, the output ends with the reply text and the exit code is 0

#### Scenario: Stops at the first failure
- **WHEN** the second line of a three-line file is refused with status 409
- **THEN** the third line does not run, the exit code is 1 and standard error names the file and line 2

#### Scenario: Comments and blank lines
- **WHEN** a file holds only comments and blank lines
- **THEN** nothing is sent and the exit code is 0

### Requirement: Unattended runs pause between lines
A script run by `kaiju demo run`, or started by `kaiju demo start` without `--step`, SHALL pause between its lines. After a succeeded `wait-idle` (also one ending with a pending confirmation) the pause is the read pause, default 4 s. After an action (`persona`, `new-conversation`, `open`, `send`, `prepare-claim`, `confirm-claim`, `approve`, `deny`) followed by another action it is the pace, default 1200 ms. After any other line, the last line or a failed line there is none. Pauses do not add up.

#### Scenario: Pause between two actions
- **WHEN** an unattended script runs `persona adjuster` and `new-conversation`
- **THEN** the pace (1200 ms by default) passes between the end of the first and the start of the second

#### Scenario: Reading pause after an answer
- **WHEN** an unattended script runs `send "Hi"`, `wait-idle`, `reply` and `send "More"`
- **THEN** no pause follows `send "Hi"`, the read pause follows `wait-idle`, no further pause follows `reply`, and `send "More"` starts after the read pause only

#### Scenario: Confirmation after a wait
- **WHEN** an unattended script runs `wait-idle` that ends with `idle: confirmation pending (issuePayout)` and then `approve`
- **THEN** the read pause passes before `approve`

#### Scenario: No pause after the last line or a failure
- **WHEN** the last line of an unattended script is `wait-idle`, or a line is refused with status 409
- **THEN** the run ends at once, without a further pause

#### Scenario: Stop during a pause
- **WHEN** an unattended demo is pausing between two lines and `kaiju demo stop` runs
- **THEN** the demo stops without running the next line and `status` says `stopped`

#### Scenario: A paused demo is still running
- **WHEN** an unattended demo is in a pause and `kaiju demo status` runs
- **THEN** the state is `running`

### Requirement: A script sets its own pacing
The script lines `pace <duration>` and `read-pause <duration>` SHALL set the pace and the read pause for the rest of that run; `0ms` means no pause, and a duration is a whole number followed by `ms`, `s` or `m`. The two lines SHALL be script lines only: they are not control commands, so the desktop endpoint answers them with status 400.

#### Scenario: Script sets its own values
- **WHEN** an unattended script starts with `pace 2s` and `read-pause 500ms`
- **THEN** the later action gaps are 2 s and the pause after each `wait-idle` is 500 ms

#### Scenario: Switched off
- **WHEN** an unattended script sets `pace 0ms` and `read-pause 0ms`
- **THEN** its lines run one after the other without a pause

#### Scenario: Pacing lines are not control commands
- **WHEN** the line `pace 1s` is sent to the desktop endpoint
- **THEN** the answer is status 400 naming `pace`

### Requirement: Step mode and direct commands are not paced
A demo started with `--step` SHALL NOT be paused by the runner, because the presenter paces it with `kaiju demo next`, and a direct command SHALL NOT be paused at all. In step mode the pacing lines run as lines that change nothing visible and take one `next` like any other line.

#### Scenario: Step mode is not paced
- **WHEN** a demo started with `--step` has `kaiju demo next` run `persona adjuster`
- **THEN** the command returns as soon as the line has finished, and the next line waits for the next `next` only

#### Scenario: Direct commands are not paced
- **WHEN** `kaiju send "Hi"` and then `kaiju approve` run as direct commands
- **THEN** neither is delayed by the runner and the desktop endpoint holds no pace
