# build-and-quality Specification

## ADDED Requirements

### Requirement: The community library decision is recorded
The repository SHALL contain an architecture decision record for community libraries that states the pinned version of `spring-ai-agent-utils`, why it is used and not copied, how it was verified against the Spring AI version in use, the accepted gaps and the upgrade path. It SHALL have a section for the later `mcp-security` amendment.

#### Scenario: Community library ADR present
- **WHEN** the `docs/adr/` directory is listed after this change
- **THEN** it contains an ADR for community libraries with context, decision, alternatives and consequences, and it names the pinned version `0.13.0` of `spring-ai-agent-utils`, the upgrade path, and the roadmap deviation for the skill descriptions

### Requirement: The chat protocol ADR carries the todo and deny decisions
The chat protocol ADR SHALL record the `todo-update` event, the rule that `Done` follows every reply whose last event is not terminal, and that a denying decision stops only the calls that wait for a decision.

#### Scenario: Chat protocol ADR carries the addendum
- **WHEN** the status line and the addenda of the chat protocol ADR are read
- **THEN** the status line names this change, and the addenda state the `todo-update` event, the `Done` rule, and that deny runs the calls that do not wait for a decision
