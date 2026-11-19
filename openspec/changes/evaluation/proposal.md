# Proposal

## Why

The demo's tests all run against a scripted fake model, so nothing says whether the real model answers coverage questions sensibly or whether a real fraud check cites bulletins that say what it claims. Spring AI ships evaluators (`RelevancyEvaluator`, `FactCheckingEvaluator`) for exactly this: another model judges an answer. This is the evaluation step of wave 5 in `ROADMAP.md`, after `policy-rag` and `agentic-rag-fraud`, whose outputs it judges.

## What Changes

- A small evaluation suite in its own Gradle source set `evaluation` (backend), run only by the new task `:backend:evaluate`. It is compiled and analysed by `check` but never run by it.
- Coverage: four fixed coverage questions are answered by the real `CoverageQuestions`; `RelevancyEvaluator` judges each answer against the policy clauses retrieved for the question.
- Fraud: the real `FraudCheck` assesses the three seed claims 4711, 4712 and 4713; `FactCheckingEvaluator` checks every cited finding against the cited bulletin; 4711 must be `FLAGGED`, the other two must not be.
- One negative control per evaluator (a wrong answer, a false finding) must fail, so the judge is shown not to say yes to everything.
- The suite has its own Spring context (real Anthropic model, real Ollama embeddings, Testcontainers pgvector, stub core tools) and is skipped as a whole (every model-backed class) without `ANTHROPIC_API_KEY` or without a reachable Ollama.
- The Gradle credential scrub (which removes the provider key from every `Test` task) skips exactly the `evaluate` task. It stays deny-by-default for all others.
- ADR-0004 gets an addendum (the anticipated "separate task" that may call a real provider); README documents how to run the suite.

## Capabilities

### New Capabilities
- `evaluation-suite`: the model-judged checks of coverage answers and fraud findings, their skip conditions and their place outside `check`.

### Modified Capabilities
- `backend-test-harness`: "Tests never reach the real model provider" and "Backend integration tests share one application context" are scoped to the regular `test` task and name the evaluation task as the single exception.

## Impact

- `backend/build.gradle.kts` (source set, task, scrub, `check` wiring, detekt source). No new dependency: the evaluators and JUnit conditions are already on the classpath.
- New `backend/src/evaluation/kotlin/...` sources. No production code changes.
- `config/detekt/detekt.yml` (evaluation sources exempt from three test-style rules, like tests).
- `docs/adr/0004-test-strategy.md` (addendum), `docs/adr/0006-vector-store-and-embeddings.md` (status-line note: `evaluate` uses real Ollama embeddings), `README.md`.

## Out of scope / follow-ups

- Running the suite in CI without a key (it needs a paid key and a local Ollama).
- A judge other than the configured Haiku model, and model routing. Haiku judging Haiku is accepted for a demo.
- Retrieval tuning surfaced by the suite (nomic `search_document:` / `search_query:` prefixes, a similarity threshold, stale-chunk cleanup, see `decisions.md`): the suite measures current behaviour.
- Putting the clause texts into `CoverageAnswer` (it would leak `Document` into the domain answer).
- Exercising the real MCP path to `core-mcp` in the suite (the core claim lookup is a stub).
