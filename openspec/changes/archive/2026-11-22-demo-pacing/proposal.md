## Why

Rehearsing the presentation scripts showed two problems. Long prompts do not fit the single-line chat input, so the audience cannot read what is typed. And an unattended run (`kaiju demo run`, `kaiju demo start` without `--step`) goes by too fast to follow, most visibly at the persona switch in script 2.

Why it feels instant although every press is already highlighted for 300 ms:

- Nothing pauses between lines. `DemoRunner.run` starts the next line the moment the previous one returns, so `wait-idle` ends and the next `send` is already typing, and `persona adjuster`, `new-conversation` and `send` follow each other with only the 300 ms highlights in between.
- The persona press highlights the closed select and then flips it: the drop-down is never opened, so the audience sees no choice being made.
- The outline (2 dp, thin, accent) is easy to miss on a projector, and a highlight that is gone before the viewer has looked is no cue.

## What Changes

- The chat input becomes a text area that grows with its content (1 to 5 lines, then scrolls). Enter sends, Shift+Enter inserts a newline. Typing through `send` shows in it as before.
- `persona <name>` visibly opens the persona select's drop-down, highlights the target option for the highlight time, picks it and closes the menu.
- The press highlight gets more prominent (thicker outline plus a translucent accent tint); the default stays 300 ms.
- Unattended runs are paced by the `DemoRunner` (CLI side): a pause between two consecutive actions (default 1200 ms) and a reading pause after each `wait-idle` (default 4 s), both settable per script with the new script lines `pace <duration>` and `read-pause <duration>`. Step mode and direct commands get no pauses.
- The four presentation scripts use the pacing where it helps, and each is verified by automatic runs against the real stack.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `desktop-chat`: the message input is a growing text area; Enter and Shift+Enter.
- `desktop-control`: the persona switch is opened visibly; the press highlight is more prominent.
- `demo-daemon`: valid script lines include `pace` and `read-pause`; unattended runs are paced, step mode is not.
- `demo-cli`: example scripts may use the pacing lines; presentation scripts are paced for unattended runs.

## Impact

- `desktop`: `DTextField` (line limits), `DSelect` (hoisted open state, per-option modifier), `App` (`InputRow`, persona select), `control/` (`PressTarget`, `PressFeedbackModifier`, `DesktopControl`), tests.
- `control-protocol`: two script-only steps (`ScriptStep.Pace`, `ScriptStep.ReadPause`); `parseCommand` stays unchanged, so the desktop endpoint still answers 400 for them.
- `cli`: `DemoRunner` pacing, `demo run`, `DemoDaemon`/`DemoSession`, help text, tests. Native `./kaiju` rebuilt (`:cli:installKaiju`); the desktop must be restarted to pick up the UI changes.
- `scripts/demos/presentation/*.kaiju`, README demo section, ADR-0011 addendum.

## Out of scope / follow-ups

- Scaling the reading pause with the reply length (see design, open questions).
- A `--pace` flag or environment variable on `demo run`; a drawn mouse pointer; a visible caret.
- A line-ending or scrolling indicator inside the text area.
- Pacing in step mode (the presenter paces it) and for direct commands (agents want speed).
