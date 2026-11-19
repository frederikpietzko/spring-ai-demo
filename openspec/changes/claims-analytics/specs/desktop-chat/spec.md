# Spec Delta

## ADDED Requirements

### Requirement: An analytics table is shown in the reply
The desktop application SHALL store an analytics table event on the assistant message of the reply and show it as a table under the reply text: the title, one header line with the columns and one line per row, each cell as text. A table event SHALL NOT end the reply, SHALL NOT produce the "connection closed" error, and a reply that holds only a table SHALL NOT show the "…" placeholder. The reply ends at the done event that follows.

#### Scenario: Table then text then done
- **WHEN** the backend streams a table with the title `Payouts by district`, the columns `District` and `Payouts`, the row `HARBOUR`, `3`, the text delta "Harbour leads" and done
- **THEN** the reply holds the table and the text, shows no error, and the conversation is ready for the next message

#### Scenario: The cells are visible
- **WHEN** a reply with that table is displayed
- **THEN** the screen shows the title, the headers `District` and `Payouts`, and the cells `HARBOUR` and `3`

#### Scenario: Table only
- **WHEN** the backend streams only a table and done
- **THEN** the reply shows the table and no "…" placeholder
