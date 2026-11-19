# Spec Delta

## MODIFIED Requirements

### Requirement: Claimant instructions frame the conversation
The claimant agent SHALL send the model a system message with the claims-desk instructions for a claimant and a user message that carries the claimant's text exactly as typed. The claimant's text SHALL NOT itself be template-processed. When policy wording is retrieved for the message, the claimant's text SHALL reach the model verbatim inside the retrieval template of the user message; when nothing is retrieved, the user message SHALL be the claimant's text alone.

#### Scenario: System prompt reaches the model
- **WHEN** a claimant request is answered
- **THEN** the prompt received by the model has a system message containing the claimant instructions

#### Scenario: User text is passed through unchanged
- **WHEN** the claimant message contains braces such as `{claimId} {}`
- **THEN** the user message received by the model contains that text unchanged, whether or not policy wording was added around it
