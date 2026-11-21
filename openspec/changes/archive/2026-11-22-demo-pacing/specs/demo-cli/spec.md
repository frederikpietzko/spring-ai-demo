## ADDED Requirements

### Requirement: Presentation scripts are paced for unattended runs
The presentation scripts SHALL be comfortable to watch when run unattended with `kaiju demo run` or `kaiju demo start` without `--step`, using the run's default pacing or pacing lines of their own, and SHALL need no `sleep` lines between prompts for that. Script `2-prompt-injection.kaiju` SHALL leave a visible pause before its switch to the adjuster persona, so the audience sees the switch coming.

#### Scenario: Scripts parse with their pacing lines
- **WHEN** every file in `scripts/demos/presentation/` is parsed with the script parser
- **THEN** no line is rejected

#### Scenario: Persona switch is not abrupt
- **WHEN** `2-prompt-injection.kaiju` runs unattended with the default pacing
- **THEN** at least the read pause passes between the end of the previous reply and the press on the persona switch, and the persona drop-down is open for the highlight time before `Adjuster` is picked

## MODIFIED Requirements

### Requirement: Example scripts stay valid
The repository SHALL hold example demo scripts under `scripts/demos/`, and every line of every example SHALL be valid in the script syntax (a control command, a `seed` line, a pacing line, a comment or a blank line).

#### Scenario: Examples parse
- **WHEN** every file in `scripts/demos/` is parsed with the script parser
- **THEN** no line is rejected
