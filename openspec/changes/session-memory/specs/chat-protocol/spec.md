# chat-protocol Specification

## MODIFIED Requirements

### Requirement: Chat request carries persona and message
The protocol SHALL define the chat request as a JSON object with a `persona` field (`CLAIMANT` or `ADJUSTER`), a `message` field and an optional `conversationId` field. A request without a conversation id SHALL encode without the field and SHALL decode with no id.

#### Scenario: Request wire form
- **WHEN** a request for persona `CLAIMANT` with message "Hi" and no conversation id is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi"}` and decoding it gives the same request

#### Scenario: Request with a conversation id
- **WHEN** a request for persona `CLAIMANT` with message "Hi" and conversation id "c-1" is encoded
- **THEN** the JSON is `{"persona":"CLAIMANT","message":"Hi","conversationId":"c-1"}` and decoding it gives the same request

#### Scenario: Unknown persona is rejected
- **WHEN** the JSON `{"persona":"AUDITOR","message":"Hi"}` is decoded as a chat request
- **THEN** decoding fails

## ADDED Requirements

### Requirement: Conversation summaries and messages have a stable JSON form
The protocol SHALL define a conversation summary (`id`, `title`, `startedAt` as an ISO-8601 instant text) and a conversation message (`role`, `USER` or `ASSISTANT`, and `text`), each serialized as a JSON object with exactly those fields, so that backend and desktop agree on the conversation list and the transcript.

#### Scenario: Summary wire form
- **WHEN** a summary with id "c-1", title "Shed" and start "2026-11-20T10:00:00Z" is encoded
- **THEN** the JSON is `{"id":"c-1","title":"Shed","startedAt":"2026-11-20T10:00:00Z"}`

#### Scenario: Message wire form
- **WHEN** a message with role `USER` and text "Hi" is encoded
- **THEN** the JSON is `{"role":"USER","text":"Hi"}`

#### Scenario: Round trip
- **WHEN** a list of summaries and a list of messages, with non-ASCII text and quotes, are encoded and decoded
- **THEN** the decoded lists equal the originals
