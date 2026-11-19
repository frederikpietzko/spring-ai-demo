# conversation-memory Specification

## Purpose

Lets a claimant conversation remember its earlier turns, survive a backend restart, be listed and read back, and be searched by the model.

## Requirements

### Requirement: A conversation remembers its earlier turns
The claimant chat SHALL give the model the earlier messages of the conversation named by the request's conversation id, the claimant's messages and the assistant's replies, in order, before the new message. A reply SHALL be part of the conversation once its stream has ended.

#### Scenario: Second turn sees the first
- **WHEN** a claimant posts "my garden gnome is gone" with conversation id X, the model replies "Noted.", and the claimant then posts "when was that?" with the same id X
- **THEN** the prompt of the second request contains a user message containing "my garden gnome is gone", an assistant message "Noted." and the new message after them

#### Scenario: Conversations stay apart
- **WHEN** a claimant posts "my garden gnome is gone" with conversation id A and then posts "hello" with another conversation id B
- **THEN** the prompt of the second request does not contain "my garden gnome is gone"

#### Scenario: Tool results are remembered with the conversation
- **WHEN** a claimant message makes the model call the recall tool and the model then answers
- **THEN** the stored conversation contains the tool's result as well as the claimant message and the final reply

### Requirement: Conversations survive a restart
A conversation SHALL be stored in the backend database, so that a backend that starts later, or a newly built session service over the same database, continues it with its full history.

#### Scenario: History is read from the database
- **WHEN** a claimant has had one turn in conversation X and a newly built session service over the same database reads conversation X
- **THEN** it holds the claimant's message and the assistant's reply of that turn

#### Scenario: A new claimant chat continues the conversation
- **WHEN** a claimant chat client built anew over that newly built session service answers a second message for conversation X
- **THEN** the prompt contains the first turn

### Requirement: Conversations are listed, newest first, with a title
The backend SHALL answer `GET /conversations` with the claimant conversations, newest first, each with its id, a title and its start time. The title SHALL be the beginning of the claimant's first message as typed (at most 60 characters), not the retrieval template. Conversations of other agents SHALL NOT be listed.

#### Scenario: A started conversation is listed
- **WHEN** a claimant posts the message "Kaiju stepped on my shed" with conversation id X and the conversation list is requested
- **THEN** the list contains an entry with id X and the title "Kaiju stepped on my shed"

#### Scenario: Newest first
- **WHEN** conversation A is started and then conversation B
- **THEN** B comes before A in the list

#### Scenario: The title is the typed text
- **WHEN** the first message of a conversation is a coverage question that makes the retrieval advisor wrap it in policy wording
- **THEN** the listed title starts with the question and contains no policy wording

#### Scenario: Long titles are cut
- **WHEN** the first message of a conversation has 61 or more characters
- **THEN** the listed title is exactly its first 60 characters

#### Scenario: Other agents' sessions are not listed
- **WHEN** a session of another agent exists in the session store
- **THEN** the conversation list does not contain it

### Requirement: A conversation can be read as a transcript
The backend SHALL answer `GET /conversations/{id}/messages` with the messages of the conversation in order, each with its role (user or assistant) and text. The user text SHALL be what the claimant typed, not the retrieval template, and tool calls and tool results SHALL NOT appear. An unknown id SHALL be answered with status 404.

#### Scenario: Transcript of a finished turn
- **WHEN** a claimant posts a coverage question with conversation id X, the model replies "Not covered", and the transcript of X is requested
- **THEN** it is two messages: the user message exactly as typed and the assistant message "Not covered"

#### Scenario: Transcript includes turns removed from the prompt by compaction
- **WHEN** a conversation has been compacted and its transcript is requested
- **THEN** it still contains every user and assistant message from the start

#### Scenario: Unknown conversation
- **WHEN** the transcript of an id that no conversation has is requested
- **THEN** the response status is 404

### Requirement: A request without a usable conversation id starts a fresh conversation
A chat request with no conversation id, or a blank one, SHALL be answered in a fresh conversation of its own and SHALL NOT fail.

#### Scenario: No id
- **WHEN** a claimant posts a message without a conversation id
- **THEN** the stream answers normally and the conversation list contains one more conversation

#### Scenario: Blank id
- **WHEN** a claimant posts a message with the conversation id "  "
- **THEN** the stream answers normally and no conversation has the blank id

### Requirement: Long conversations are compacted but never lose their log
When a conversation has more than ten user turns, the backend SHALL compact it after the turn that crossed the limit: the next prompt SHALL carry only the five most recent turns (no model call is made to summarize), while every earlier message stays stored and readable.

#### Scenario: Old turns leave the prompt
- **WHEN** a conversation of ten stored turns receives an eleventh turn and then a twelfth
- **THEN** the prompt of the twelfth request does not contain the first turn but does contain the most recent turns

#### Scenario: Compacted turns stay in the log
- **WHEN** the same conversation is read through the transcript
- **THEN** the first turn is still there

### Requirement: The model can search the whole conversation
The claimant chat SHALL offer the model a recall tool named `conversation_search` that takes a word or phrase and returns up to the five most recent messages of the current conversation containing it, ignoring case, including messages that compaction removed from the prompt. It SHALL search only the conversation of the request, and it SHALL search the text the claimant typed.

#### Scenario: Finds an earlier message
- **WHEN** a conversation contains the claimant message "my garden gnome is gone" and a later message makes the model request `conversation_search` for "gnome"
- **THEN** the tool result that goes back to the model contains "my garden gnome is gone"

#### Scenario: Finds a turn that compaction removed
- **WHEN** the first turn of a compacted conversation contains "gnome" and the model requests `conversation_search` for "gnome"
- **THEN** the tool result contains that first turn's message

#### Scenario: Reports at most the five most recent messages
- **WHEN** six messages of the conversation contain "gnome" and the model requests `conversation_search` for "gnome"
- **THEN** the tool result contains five messages, the most recent five of the six, and not the oldest

#### Scenario: Stays inside the conversation
- **WHEN** another conversation contains "gnome" and the model requests `conversation_search` for "gnome" in a conversation without it
- **THEN** the tool result contains no message of the other conversation

### Requirement: Claimant instructions describe the memory
The claimant system message SHALL say that the conversation is remembered and that older messages may have left the model's context and can be recalled with `conversation_search`. It SHALL NOT say that only the current message is visible.

#### Scenario: Instructions mention recall
- **WHEN** a claimant request is answered
- **THEN** the system message received by the model mentions `conversation_search` and does not say the model only sees the current message
