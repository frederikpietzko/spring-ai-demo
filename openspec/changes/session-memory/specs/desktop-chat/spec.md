# desktop-chat Specification

## ADDED Requirements

### Requirement: Every conversation has its own id
The desktop application SHALL give each conversation its own conversation id, SHALL send it with every chat request of that conversation, and SHALL offer a "New conversation" action that clears the conversation and starts a new one with a different id. A new conversation SHALL NOT be started while an answer is still streaming.

#### Scenario: Requests of one conversation share an id
- **WHEN** the user sends "Hi" and then "Again" in the same conversation
- **THEN** both chat requests carry the same, non-blank conversation id

#### Scenario: New conversation clears the screen and changes the id
- **WHEN** the user has sent "Hi", chooses "New conversation" and sends "Hello"
- **THEN** the conversation shows only "Hello" and its reply, and the second request carries a different conversation id than the first

#### Scenario: No new conversation while streaming
- **WHEN** the user chooses "New conversation" while an answer has not reached done or error
- **THEN** the conversation and its id are unchanged

### Requirement: Past conversations are listed
The desktop application SHALL show the conversations known to the backend, newest first, by title, when it starts and again after each answer has ended. An unreachable or failing backend SHALL leave the list empty and the application running.

#### Scenario: List is loaded at start
- **WHEN** the backend knows two conversations titled "Shed" and "Gnome" and the application starts
- **THEN** the list shows "Shed" and "Gnome" in the order the backend returned them

#### Scenario: List is refreshed after an answer
- **WHEN** the user sends a message and the answer ends, and the backend now knows one more conversation
- **THEN** the list shows the additional conversation

#### Scenario: Backend is not reachable
- **WHEN** the application starts while nothing listens at the backend address
- **THEN** the list is empty and the application keeps running

### Requirement: A past conversation can be opened and continued
The desktop application SHALL show the messages of a conversation chosen from the list, using the transcript from the backend, and SHALL send later messages with that conversation's id. Choosing a conversation while an answer is streaming SHALL do nothing.

#### Scenario: Opening shows the transcript
- **WHEN** the user chooses the conversation "Gnome" whose transcript is a user message "my garden gnome is gone" and an assistant message "Noted."
- **THEN** the conversation shows those two messages in that order

#### Scenario: Continuing uses the opened conversation's id
- **WHEN** the user opens the conversation with id "c-7" and sends "when was that?"
- **THEN** the chat request carries conversation id "c-7"

#### Scenario: Transcript cannot be loaded
- **WHEN** the user chooses a conversation whose transcript the backend does not return
- **THEN** the conversation shows no messages and the application keeps running
