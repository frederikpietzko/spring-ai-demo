# backend-test-harness Specification

## ADDED Requirements

### Requirement: The core tools are faked and no MCP server is contacted
The backend test context SHALL NOT create an MCP client and SHALL provide a fake tool provider under the bean name of the real one. The fake SHALL offer the tools `getClaim` and `issuePayout`, record every call with its arguments and tool context, and be emptied before each test (calls, a failure for the payout tool to throw, and a failure for listing the tools to throw). It SHALL NOT mark any tool as needing confirmation, so that a confirmation always comes from the committed configuration.

#### Scenario: No MCP client in the shared context
- **WHEN** the beans of the shared test context are listed
- **THEN** there is no MCP client bean and the tool provider named `mcpToolCallbacks` is the fake

#### Scenario: Fake calls are recorded and reset
- **WHEN** a test makes the model request `getClaim` and the next test starts
- **THEN** the first test could read one recorded `getClaim` call with its arguments and the second test sees no recorded call

#### Scenario: A failure set by one test does not reach the next
- **WHEN** a test makes the fake payout tool and the fake tool listing fail and the next test starts
- **THEN** the next test's payout call and tool listing succeed

#### Scenario: The fake tools carry no confirmation flag of their own
- **WHEN** the tool metadata of the fake `issuePayout` callback is read
- **THEN** it does not require confirmation
