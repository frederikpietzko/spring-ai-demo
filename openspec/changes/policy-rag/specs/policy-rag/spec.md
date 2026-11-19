# Spec Delta

## Purpose

Answers coverage questions from the fictional policy wording: the wording is ingested into a vector store at startup, the relevant clauses are retrieved for a question and added to the prompt, and the answer names the clauses it rests on.

## ADDED Requirements

### Requirement: Policy wording corpus contains the exclusions
The backend SHALL ship the policy wording as Markdown documents on its classpath. The wording SHALL describe the coverages (vehicle, building, contents, garden), the claim and deductible rules, and the exclusions, among them the Act of Kaiju exclusion for class 5 kaiju events and the exclusion for provoking the kaiju. Each exclusion SHALL name itself in its own text.

#### Scenario: Act of Kaiju exclusion is present
- **WHEN** the policy wording documents are read
- **THEN** one section names the Act of Kaiju exclusion and states that damage from a class 5 kaiju event is not covered

#### Scenario: Provoking exclusion is present
- **WHEN** the policy wording documents are read
- **THEN** one section names the exclusion for provoking the kaiju and states that damage caused by the policyholder provoking the kaiju is not covered

#### Scenario: Coverages are described
- **WHEN** the policy wording documents are read
- **THEN** each of the coverages vehicle, building, contents and garden is described in a section of its own

### Requirement: Ingestion stores the wording as labelled chunks
The backend SHALL ingest the policy wording at startup into the vector store as chunks, each with a stable id, the corpus name `policy-wording`, the source file name and the section title, so that a retrieved chunk can be cited.

#### Scenario: Chunks carry citation metadata
- **WHEN** the backend has started
- **THEN** every stored chunk of the policy wording corpus has the corpus `policy-wording`, a source file name, a section title and an id that starts with `policy-wording/` and names its source file

#### Scenario: Every document is ingested
- **WHEN** the backend has started
- **THEN** the stored chunks of the corpus cover every policy wording document

### Requirement: Ingestion is idempotent
Running the ingestion again SHALL leave the stored chunks of the corpus unchanged: the same ids, the same number of rows, no duplicates.

#### Scenario: Re-run adds nothing
- **WHEN** the ingestion runs again after the backend has started
- **THEN** the number of stored policy wording chunks and their ids are the same as before

### Requirement: Ingestion needs the embedding provider
Ingestion SHALL embed the chunks through the configured embedding provider and SHALL fail, and so abort startup, when the provider cannot embed them. Partial or empty ingestion SHALL NOT be reported as success.

#### Scenario: Embedding failure aborts ingestion
- **WHEN** ingestion runs while the embedding provider fails
- **THEN** ingestion fails with that error and stores nothing from the failing run

### Requirement: Retrieval finds the relevant clause
Retrieval SHALL search only the policy wording corpus and SHALL return the clauses most similar to the question, at most four, without a minimum similarity.

#### Scenario: Class 5 question returns the Act of Kaiju exclusion
- **WHEN** the policy wording is searched with "Is my car covered after a class 5 kaiju event?"
- **THEN** the results include the section that names the Act of Kaiju exclusion

#### Scenario: Provoking question returns the provoking exclusion
- **WHEN** the policy wording is searched with a question about damage after provoking the kaiju
- **THEN** the results include the section that names the exclusion for provoking the kaiju

#### Scenario: Other corpora are not searched
- **WHEN** a chunk of another corpus that matches the question closely is stored and the policy wording is searched
- **THEN** the results contain no chunk of that other corpus

### Requirement: Coverage answers are grounded in retrieved wording
A coverage question SHALL be answered by the chat model with the retrieved clauses added to the prompt, each labelled with its source file and section title, and with an instruction to cite those labels when the message asks about coverage and to answer normally otherwise. The answer SHALL carry the clauses that were retrieved for it, which are not necessarily the clauses the model relied on.

#### Scenario: Retrieved wording is in the prompt
- **WHEN** a coverage question about a class 5 kaiju event is answered
- **THEN** the prompt the model receives contains the text of the Act of Kaiju exclusion preceded by its label naming the source file and section title, and the original question

#### Scenario: Prompt asks for citations
- **WHEN** a coverage question is answered and retrieval returns clauses
- **THEN** the prompt the model receives instructs it to cite the labels of the clauses it relies on when the message asks about coverage, and to answer normally otherwise

#### Scenario: Answer carries its citations
- **WHEN** a coverage question about a class 5 kaiju event is answered and the model replies with a scripted text
- **THEN** the answer text is that reply and its citations include the source file and section title of the Act of Kaiju exclusion

#### Scenario: No matching wording
- **WHEN** a question is answered and retrieval returns no clause
- **THEN** the model is still called with the original question unchanged, the answer is returned, and its citations are empty

### Requirement: Retrieval sits between memory and tools
The retrieval advisor SHALL be ordered after the conversation memory advisors and before the tool-calling advisor, so that retrieval runs once for a user turn, outside the tool loop, and not again for each tool iteration.

#### Scenario: Advisor order lies between memory and tools
- **WHEN** the order of the policy wording advisor is read
- **THEN** it lies strictly between the conversation memory advisor precedence order and the default order of the tool-calling advisor

### Requirement: Claimant chat answers from the policy wording
The claimant chat SHALL use the same retrieval as the coverage service, so that a coverage question sent through the chat endpoint is answered from the policy wording.

#### Scenario: Coverage question in the claimant chat is grounded
- **WHEN** a claimant sends a coverage question about a class 5 kaiju event through the chat endpoint
- **THEN** the prompt the model receives contains the Act of Kaiju exclusion with its label and the streamed answer is the scripted reply
