## ADDED Requirements

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

## MODIFIED Requirements

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
