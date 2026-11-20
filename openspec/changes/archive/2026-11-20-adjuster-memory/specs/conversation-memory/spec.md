## MODIFIED Requirements

### Requirement: Conversations are listed, newest first, with a title
The backend SHALL answer `GET /conversations` with the conversations of one persona, newest first, each with its id, a title and its start time. The persona SHALL come from the optional query parameter `persona` (`CLAIMANT` or `ADJUSTER`, default `CLAIMANT`). The title SHALL be the beginning of the first message as typed (at most 60 characters), not the retrieval template. Conversations of other agents SHALL NOT be listed.

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

#### Scenario: The adjuster's conversations are listed for the adjuster
- **WHEN** an adjuster posts "Process claim #4711" with conversation id Y and `GET /conversations?persona=ADJUSTER` is requested
- **THEN** the list contains an entry with id Y and the title "Process claim #4711", and `GET /conversations` without the parameter does not contain Y

## ADDED Requirements

### Requirement: The adjuster remembers its conversations
The adjuster SHALL see the earlier user and assistant messages of its conversation in every turn, and SHALL store each turn in the session store under the agent name `adjuster`: the user message when the turn starts, the assistant text when the turn's stream ends, also for a turn resumed after a confirmation. Tool calls and tool results SHALL NOT be stored.

#### Scenario: A second adjuster turn sees the first
- **WHEN** the adjuster gets "Process claim #4711" with conversation id Y, answers "4711 is flagged", and then gets "Approve it anyway" with the same id
- **THEN** the model's second prompt contains "Process claim #4711" and "4711 is flagged" before "Approve it anyway"

#### Scenario: A resumed turn is remembered
- **WHEN** an adjuster turn pauses for a confirmation, the decision resumes it and the resumed turn answers "Payout issued"
- **THEN** the transcript of the conversation ends with an assistant message containing "Payout issued"
