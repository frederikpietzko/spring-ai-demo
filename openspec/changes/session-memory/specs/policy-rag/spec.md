# policy-rag Specification

## MODIFIED Requirements

### Requirement: Retrieval sits between memory and tools
The retrieval advisor SHALL be ordered after the session turn advisor and before the tool-calling advisor, so that retrieval runs once for a user turn, outside the tool loop, and not again for each tool iteration. The session memory advisor runs inside the tool loop, after retrieval, and therefore stores the user message as the retrieval advisor left it.

#### Scenario: Advisor order lies between memory and tools
- **WHEN** the order of the policy wording advisor is read
- **THEN** it lies strictly between the order of the session turn advisor and the default order of the tool-calling advisor
