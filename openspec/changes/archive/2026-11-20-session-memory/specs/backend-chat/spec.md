# backend-chat Specification

## RENAMED Requirements

- FROM: `### Requirement: Messages are not remembered`
- TO: `### Requirement: A request without a conversation id stands alone`

## MODIFIED Requirements

### Requirement: A request without a conversation id stands alone
The backend SHALL treat a chat request that names no conversation (no conversation id, or a blank one) on its own: the prompt for such a request SHALL contain only that request's message, not earlier ones. Requests that name a conversation are remembered as specified by the `conversation-memory` capability.

#### Scenario: Second request does not see the first
- **WHEN** a client posts two claimant requests without a conversation id one after the other
- **THEN** the prompt of the second request contains the second message and not the first
