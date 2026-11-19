# evaluation-suite Specification

## ADDED Requirements

### Requirement: Coverage answers are judged relevant to the policy wording
The evaluation suite SHALL ask the real coverage service four fixed questions (a trampled rooftop garden, a class 5 kaiju damaging a house, fireworks aimed at the kaiju, and how much the claimant pays themselves) and SHALL judge each answer with a relevancy evaluator against the policy wording clauses retrieved for that question. The judge SHALL be constrained to answer exactly `YES` or `NO`.

#### Scenario: Each fixed question is answered in line with the wording
- **WHEN** the suite runs with a real model and each of the four questions is asked
- **THEN** the relevancy evaluator passes the answer, and a failure message names the question and the answer

#### Scenario: An unrelated answer fails the evaluator
- **WHEN** the relevancy evaluator judges the answer "The harbour opens at 9." to the rooftop garden question against the retrieved clauses
- **THEN** the evaluation does not pass

### Requirement: Fraud findings are fact-checked against the cited bulletins
The evaluation suite SHALL run the real fraud check for the seed claims 4711, 4712 and 4713 and SHALL fact-check every evidence item of an assessed result: the claim is the bulletin id with its finding, and the supporting documents are the claim's facts (number, place, incident time, kaiju class) and the complete text of the cited bulletin file including its header. A cited bulletin id with no bulletin file SHALL fail the test.

#### Scenario: Every cited finding is supported
- **WHEN** the suite runs with a real model and the fraud check cites bulletins for a claim
- **THEN** the fact-checking evaluator passes every evidence item, and a failure message names the claim, the bulletin id and the finding

#### Scenario: A false finding fails the evaluator
- **WHEN** the fact-checking evaluator judges the finding "SB-102: places the kaiju at the harbour at 15:00" for claim 4711 against the claim's facts and the text of `SB-102`
- **THEN** the evaluation does not pass

#### Scenario: A cited bulletin does not exist
- **WHEN** an assessment cites a bulletin id for which no bulletin file exists
- **THEN** the test fails and names the id

### Requirement: Fraud verdicts match the seed scenarios
The suite SHALL assert the verdict of the real fraud check: `FLAGGED` for claim 4711 and not `FLAGGED` for claims 4712 and 4713. An unreadable result SHALL fail the test. The core claim lookup SHALL be a stub that returns the `core-mcp` seed values of the three claims, so `core-mcp` need not run.

#### Scenario: The harbour claim is flagged
- **WHEN** the real fraud check assesses claim 4711
- **THEN** the verdict is `FLAGGED`

#### Scenario: The clean claims are not flagged
- **WHEN** the real fraud check assesses claim 4712 and claim 4713
- **THEN** neither verdict is `FLAGGED`

### Requirement: The suite is skipped without a provider key
Every model-backed evaluation test class (annotated `@RealModelEvaluation`) SHALL be skipped, not failed, when `ANTHROPIC_API_KEY` is absent or empty in the environment of the run, with the reason `ANTHROPIC_API_KEY needed` even when Ollama is unreachable too (the key condition is evaluated first), and no application context SHALL be started for a skipped class.

#### Scenario: No key
- **WHEN** `:backend:evaluate` runs in a shell without `ANTHROPIC_API_KEY`
- **THEN** the build succeeds, the model-backed evaluation tests are reported as skipped with the reason that a provider key is needed (also when Ollama is unreachable), and no database container is started

#### Scenario: The key reaches the evaluation task
- **WHEN** `:backend:evaluate` runs in a shell where `ANTHROPIC_API_KEY` is set and Ollama is not reachable
- **THEN** the tests are skipped because Ollama is unreachable, not because the key is missing

### Requirement: The suite is skipped when Ollama is unreachable
Every model-backed evaluation test class SHALL be skipped, not failed, when the Ollama base URL (`SPRING_AI_OLLAMA_BASE_URL`, else `http://localhost:11434`) does not answer `GET /api/tags` with status 200 within two seconds, and the check SHALL run before the application context is created, because startup ingestion embeds the corpora through Ollama.

#### Scenario: Ollama answers
- **WHEN** the condition checks a base URL whose server answers `/api/tags` with status 200
- **THEN** the test class is enabled

#### Scenario: Nothing listens
- **WHEN** the condition checks a base URL on which nothing listens
- **THEN** the test class is disabled with a reason that names the URL, and no exception escapes

### Requirement: `check` neither runs nor skips the suite unseen
The evaluation task SHALL NOT be part of `check`. `check` SHALL compile the evaluation sources and SHALL analyse them with detekt, so the suite cannot rot unnoticed.

#### Scenario: Check does not run the evaluation
- **WHEN** the task graph of `check` is listed without executing it (`--dry-run`)
- **THEN** it contains `:backend:evaluationClasses` and `:backend:detekt` but not `:backend:evaluate`

#### Scenario: Check analyses the evaluation sources
- **WHEN** a style violation is added to a file under `backend/src/evaluation/kotlin` and `:backend:detekt` runs
- **THEN** detekt reports it

#### Scenario: Every run is a new evaluation
- **WHEN** `:backend:evaluate` runs twice in a row with unchanged inputs
- **THEN** the task executes both times (it is never up to date and never restored from a cache)
