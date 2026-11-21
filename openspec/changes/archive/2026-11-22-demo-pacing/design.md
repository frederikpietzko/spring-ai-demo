## Context

Observed in `DemoRunner.run` (no pause between lines), `DesktopControl.press` (highlight for `highlight` time, then click), `PressFeedbackModifier` (2 dp outline, 3 dp gap), `DSelect` (internal `expanded` state, so the control cannot open it) and `DTextField` (`singleLine = true`). ADR-0011 binds the layout: the desktop is a driver without policy, the daemon owns only the demo, `DemoRunner` is the one executor of script lines.

## Decisions

### 1. Pacing lives in the `DemoRunner`, not in the desktop

`DemoRunner.run(lines, paced = ...)` pauses between lines; `demo run` and the daemon pass `paced = true` unless the demo is in step mode. Two script-only lines set the values for the rest of the run: `pace <duration>` and `read-pause <duration>` (`0ms` turns one off). They are parsed by `parseScript` like `seed` and are not control commands, so `parseCommand` rejects them and the desktop endpoint answers 400.

Why the runner: pacing is a policy of an unattended script, not of the screen. Direct `kaiju send ...` from an agent and the desktop endpoint keep their "return once the click happened" contract (desktop-control), step mode needs no flag on the desktop, and the pause is testable with a recording `pause` function without a UI. Rejected: pausing inside the desktop after every action; it would slow agents and manual UI tests too, and would need a mode switch the endpoint does not have.

Exception that stays in the desktop: what is part of one press (the highlight and, for the persona switch, the open menu), because it is what the audience sees while the control acts.

### 2. Gap rule

After a line has succeeded, the runner pauses before the next line:

| Finished line | Next line | Pause |
| --- | --- | --- |
| `wait-idle` (idle, also a pending confirmation) | any | read pause (default 4 s) |
| an action (`persona`, `new-conversation`, `open`, `send`, `prepare-claim`, `confirm-claim`, `approve`, `deny`) | another action | pace (default 1200 ms) |
| anything else (`reply`, `state`, `sleep`, `seed`, `typing-speed`, `highlight`, `pace`, `read-pause`) | any | none |
| the last line, or any failed line | | none |

So the pauses never stack (`wait-idle`, `reply`, `send` pauses once, for the read time), an action is not delayed before its own `wait-idle`, and the answer on screen gets the reading time. Defaults: `Pacing.DEMO` = 1200 ms and 4 s; `DemoRunner` itself defaults to `Pacing.NONE`, so existing runner and daemon tests stay fast and only the production wiring (`demo run`, `DemoDaemon`) passes `Pacing.DEMO` (the daemon takes it as a constructor argument; tests pass `NONE` or short values).

Reading pause: a fixed value, settable. Rejected for now: scaling by reply length (needs a `reply` call per wait and a tuning rule; see open questions).

### 3. Highlight

The cause is the missing gaps, the closed persona select and a faint outline, not the 300 ms. The default stays 300 ms (`highlight` still changes it). The outline becomes 3 dp plus a translucent accent tint (about 15 percent) over the control, still drawn outside the layout.

### 4. Persona switch opens visibly

`DSelect` gets an optional hoisted open state (`expanded`, `onExpandedChange`; unset keeps today's internal state) and `optionModifier: (T) -> Modifier`. A new `PersonaMenu` open flag (a `StateFlow<Boolean>` next to `PressFeedback`, passed to `App` with default) feeds it; user clicks toggle the same flag. New target `PressTarget.PersonaOption(persona)` with label `persona:<name>`.

`persona <name>` runs: refuse if busy (409, nothing happens, as now), highlight `Persona` (field), open the menu and keep it open for the highlight time, highlight the option, pick it (`viewModel.selectPersona`), close the menu. Total about three highlight times. The same two hooks make any future select drivable; the persona switch is the only `DSelect` in the app today.

Rejected: opening the menu through a synthetic click on the field (the Compose semantics of a running window are not reachable, ADR-0011).

### 5. Text area

`DTextField` gets `minLines` and `maxLines` (default 1 and 1, so every other use is unchanged; `singleLine` is `maxLines == 1`). The chat input uses 1 and 5: the field grows with its content and scrolls inside after five lines. Enter (no modifier) sends when `canSend` and the text is not blank and is always consumed; Shift+Enter is not consumed and inserts a newline (if the default key mapping does not do that, the handler inserts it explicitly). `send` still types character by character into the same field.

Rejected: Ctrl/Cmd+Enter to send (every demo viewer expects Enter to send in a chat).

## Test boundaries

- **Compose UI (`runDesktopComposeUiTest` on `App`)**: text area (long text fully laid out, grows, capped at five lines, Enter and Shift+Enter) and the persona drop-down (menu open with option pressed while the field still shows `Claimant`, then closed and `Adjuster` shown). Catches layout and semantics; does not catch real-window popup placement (covered by the live runs).
- **`DesktopControl` with the fake `ChatApi`** (existing `ControlFixture`): refusal order and the persona sequence through `state.pressed`; catches sequencing and 409 rules, not pixels.
- **`control-protocol` parser tests**: `pace` and `read-pause` parse as script steps, are rejected as commands, bad durations name the value. Catches syntax drift between runner and endpoint.
- **`DemoRunner` with a recording `pause` function** (no real sleeping) and the fake control endpoint: the gap rule, defaults, per-script settings, step mode and failure behaviour; plus one daemon test that a non-step demo pauses and `stop` interrupts a pause. Catches pacing logic, not the felt speed.
- **Live runs against the real stack** (task 6): each presentation script twice, then all four in sequence; catches what only a human sees (felt speed, outline visibility, popup in a real window).
- No new ArchUnit rule: no new package or dependency direction.

## Risks

- Popups in a real window can differ from the test rendering (placement, focus): the live runs check it.
- LLM replies are not deterministic: a script failing on a model answer (script 2 guardrail, script 3 confirmations) is re-run once and reported, not hidden.

## Open questions

See the list in the report and below; defaults are what the tasks implement.

1. Reading pause rule. Default: fixed 4 s. Alternative: scaled by reply length (about 20 characters per second, between 3 s and 10 s), needs the runner to read the reply.
2. Default pace 1200 ms (recommended range 1 to 1.5 s). Default read pause 4 s. Confirm or change.
3. Highlight default stays 300 ms. Raise to 500 ms if the live runs still feel hurried.
4. Do `pace` and `read-pause` lines consume a `next` in step mode, like `typing-speed` and `highlight` do today? Default: yes (consistent, no special case).
5. Shift+Enter inserts a newline; Enter on a blank input or while streaming does nothing (not even a newline). Default as written.
