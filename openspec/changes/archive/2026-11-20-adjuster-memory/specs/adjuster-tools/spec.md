## ADDED Requirements

### Requirement: Text of consecutive model rounds is separated
When a later model round of an adjuster turn produces text after an earlier round already did, the stream SHALL send a text delta with a paragraph break (`"\n\n"`) before the later round's text.

#### Scenario: Two rounds with text
- **WHEN** the first round answers "I'll query both numbers." with a tool call and the second round answers "We received 4 claims."
- **THEN** the streamed text is "I'll query both numbers.\n\nWe received 4 claims."
