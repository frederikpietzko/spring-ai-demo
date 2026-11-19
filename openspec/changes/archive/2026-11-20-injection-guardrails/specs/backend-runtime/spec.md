# backend-runtime Specification

## MODIFIED Requirements

### Requirement: Chat and embedding providers are selected explicitly
The committed configuration SHALL select Anthropic as the chat provider, Ollama as the embedding provider, with the embedding model `nomic-embed-text` producing 768 dimensions, and Ollama as the decision provider, with the decision model `d1-omni:600m`, and SHALL switch off every other model type the Ollama starter would auto-configure, so that exactly one chat model, one embedding model and one decision model exist. Creating the decision model SHALL NOT need a network connection.

#### Scenario: One chat model and one embedding model
- **WHEN** the model auto-configurations of both providers, including the Ollama decision auto-configuration, are applied on the committed configuration without a network
- **THEN** exactly one chat model exists and it is the Anthropic model, exactly one embedding model exists and it is the Ollama embedding model, the configured embedding model name is `nomic-embed-text`, and exactly one decision model exists, it is the Ollama decision model and its model is `d1-omni:600m`

#### Scenario: Vector table dimension matches the embedding model
- **WHEN** the committed configuration is read
- **THEN** the configured vector dimension is 768 and schema initialization by the vector store library is off
