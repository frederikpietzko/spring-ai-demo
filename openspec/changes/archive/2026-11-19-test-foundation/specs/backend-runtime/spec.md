# Spec Delta

## ADDED Requirements

### Requirement: Kotlin callers request typed replies with reified type parameters
The backend SHALL provide Kotlin extensions on the chat client's call response so that code requests a typed reply, or a typed response entity, by type argument instead of a class literal, including generic types such as lists.

#### Scenario: Typed reply for a plain class
- **WHEN** a model reply is JSON for a data class and the chat client's reply is requested as that type by type argument
- **THEN** the returned object has the values of the JSON

#### Scenario: Typed reply for a generic list
- **WHEN** a model reply is a JSON array and the reply is requested as a list of a data class by type argument
- **THEN** the returned list has one element per array entry with their values

#### Scenario: Typed response entity
- **WHEN** a model reply is JSON for a data class and the response entity is requested as that type by type argument
- **THEN** the entity holds the parsed object and the underlying chat response of the call
