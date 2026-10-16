# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Interview prep tool

Chat-first agentic study tool. A user states a prep goal, gets a tree of topics, each with a menu of searched resources they tick as consumed. A review layer quizzes them on what those resources actually taught.

Full design rationale: @docs/interview-prep-tool-spec.md — read it before changing anything in the pipelines, the concept layer, or the review flow. `docs/session-log-*.md` record past build sessions and the reasoning behind non-obvious fixes.

## Stack

- Java 21, Spring Boot 4.1 (Maven wrapper), Lombok
- DB: Postgres. Flyway owns the schema (`springboot/src/main/resources/db/migration/V*__*.sql`); Hibernate is `ddl-auto=validate` only, so every entity change needs a new migration.
- Queue: Postgres-backed job table (`enrichment_job`, transactional enqueue, claimed with `SELECT ... FOR UPDATE SKIP LOCKED`), not a separate broker
- LLM: LangChain4j `@AiService` interfaces against Groq's OpenAI-compatible endpoint (default model `openai/gpt-oss-120b`), via `langchain4j-open-ai`
- Search: Exa (`ExaSearchTools`), called as tools by the resource-finding agent

## Commands

All Maven commands run from `springboot/`. Tests are `@SpringBootTest` against a real Postgres at `localhost:5432/interview_assistant` (`DB_USERNAME`/`DB_PASSWORD`, default `postgres`/`postgres`). It must be running for anything beyond compilation.

- Test: `./mvnw test`
- Single test class / method: `./mvnw test -Dtest=NodePipelineIntegrationTest` / `./mvnw test -Dtest=TrackServiceTest#methodName`
- Format (google-java-format): `./mvnw spotless:apply`. Check with `./mvnw spotless:check`.
- Run: `./mvnw spring-boot:run`, which needs `GROQ_API_KEY` and `EXA_API_KEY` for real LLM/search calls (both default to `not-set`).
- `ExaSearchToolsLiveTest` makes real, billed Exa calls and runs only when `EXA_API_KEY` is set to a real key. It is skipped otherwise.
- Local Postgres is the Docker container `interview-assistant-pg` (`docker start interview-assistant-pg`).
- **Stop the backend before running tests.** Tests share the dev database, and a running backend's job poller claims the jobs tests insert. Surefire sets `enrichment.polling.enabled=false` for every test JVM, so tests never run pollers themselves.
- `spring-boot:run` includes DevTools, which restarts the running app whenever `target/classes` changes, including when `./mvnw test` recompiles. A restart reruns startup logic (e.g. `EnrichmentJobReconciler`) with the real API keys.
- Docker Desktop's VM clock drifts against the host by up to ~±0.6 s. Job times are written from the JVM clock but compared on Postgres's (`clock_timestamp()`), so tests that claim a job they just inserted must set its `run_after` on the database clock.

Frontend commands run from `frontend/` (React + Vite + TypeScript + Tailwind v4):

- `npm install`, then `npm run dev`. The Vite dev server proxies `/tracks` (REST and SSE) to Spring on `:8080`, so start the backend first. Browser navigations under `/tracks/...` are SPA routes and bypass the proxy.
- `npm run build` (typecheck + bundle), `npm run lint`.
- The design system (tokens, type, layout rules) is in `frontend/DESIGN.md`; tokens live in `frontend/src/index.css`. Components use semantic token classes, never raw hex.

## Architecture

Three pipelines behind a thin API layer, separated by latency tolerance. Each is a top-level package (`node`, `tick`, `review`), plus `track` for track creation/root decomposition and reading the tree:

- `node`: sync, user is waiting. Expand topics, dedupe, search, generate concepts. Budget 3-5s.
- `tick`: async. Flip the tick, enqueue, return. Worker fetches and extracts.
- `review`: sync but long. Quiz inline, or interview then grade.

Everything writes to Postgres. The search agent is the only autonomous component.

**Implementation status:** `track` and `node` are built. `TickService` and `ReviewService` are TODO stubs. The `LlmCall` entity exists, but nothing writes LLM-call logs yet.

### Node pipeline flow

1. `ExpansionService.expandTopic`: the LLM proposes children (`TopicExpander`), and each label is normalized (`LabelNormalizer`, deterministic, not an LLM) and matched against the track before insert (DAG edges via `topic_edge`). New topics get an `enrichment_job` row in the same transaction.
2. `EnrichmentJobPoller` (`@Scheduled`, every 2s) claims job batches and dispatches them to `EnrichmentService` on the dedicated `enrichmentJobExecutor`. Job kinds: `initial` and `resource_refresh` ("find more", append-only).
3. `EnrichmentService` runs two producers concurrently on `applicationTaskExecutor`: `ResourceFinder` (`AgenticResourceFinder`, a bounded tool-calling loop over Exa) and `ConceptGenerator`. Each persists in its own transaction. A topic reaches `ready` only when both finish. On retry, a producer whose rows already exist is skipped.
4. Completion of both job kinds is pushed to clients over SSE (`GET /tracks/{trackId}/events`, event name `enrichment`, payload `EnrichmentEvent{topicId, kind, status, newResourceCount}`). For `resource_refresh`, `status` is the refresh outcome, not the topic's `enrichment_status`.

Read endpoints the UI depends on: `GET /tracks` (list), `GET /tracks/{id}` (tree: labels and statuses only), and `GET /tracks/{trackId}/topics/{topicId}` (`TopicQueryService`: resources, concepts, `searchedAt`). The tree deliberately has no resources; clients fetch a topic's detail when they show it.

### Patterns that aren't obvious from one file

- **No LLM call while holding a DB connection.** The services avoid `@Transactional` and use explicit `TransactionTemplate` boundaries (`REQUIRED` / `REQUIRES_NEW`) around DB work only. Per-row inserts use `REQUIRES_NEW` so a unique-constraint hit (e.g. `resource_unique_url`, which is the dedupe guard) doesn't abort sibling inserts.
- **Two retry layers:** `EnrichmentService` retries only on `RetryableEnrichmentException` (exponential backoff). The poller retries the whole job (fixed backoff, max attempts) for anything else. Throw `NonRetryableEnrichmentException` for failures that shouldn't be retried inside the service.
- **Two `ChatModel` beans** (`llm/ChatModelConfig`): the `@Primary` `chatModel` uses strict JSON schema, which every `@AiService` returning a POJO/`List<POJO>` depends on. `toolCallingChatModel` omits it, because Groq rejects JSON-schema + tools in one request, and it is used only by the resource-finder agent. These beans are built by hand because the langchain4j OpenAI Spring starter is incompatible with Spring Boot 4.
- **Groq rate limits are waited out, per request.** Both beans are wrapped in `RateLimitRetryingChatModel`: on a tokens-per-minute 429 it waits the time Groq names plus 4s and resends (max 4 retries; waits over 60s, i.e. daily limits, fail immediately). A "Request too large" error is not retried. The search agent's whole conversation is resent every round, so on the 8k-TPM tier its request size is the binding constraint: Exa returns 5 results per search, snippets are capped at 500 chars (`ExaSearchTools.MAX_SNIPPET_CHARS`), and URLs are validated after the loop rather than through a model tool. Adding tools, results, or snippet length can push single requests back over 8k.
- **"Find more" on a `failed` topic queues an `initial` job, not a refresh.** `enrichTopic` resumes (skips producers whose rows exist) and is the only path to `ready`; a refresh never changes `enrichment_status`.
- **Startup recovery assumes a single backend instance.** On `ApplicationReadyEvent`, `EnrichmentJobReconciler` first releases everything still marked running (`processing` jobs, `enriching`/`expanding` topics), since a previous process can't still own it. Then it re-queues stranded topics: a non-root `pending` topic with no live job (none processing or with retries left) would show "queued" forever, since the poller only works from job rows. "Find more" on such a topic queues the same `initial` job, and is a no-op when a search or retry is already queued. Running two backends against one database would break this.
- **One enrichment job at a time** (`enrichment.max-concurrent-jobs=1`). On the 8k-TPM tier, concurrent searches just take turns on one budget, so each topic times out and burns retries. Raise it only with a higher Groq tier.
- **gpt-oss sometimes ends the agent loop by calling a nonexistent tool named `JSON`**, and Groq rejects the request with `tool_use_failed`. `AgenticResourceFinder` treats that as retryable rather than failing the job.
- **Groq also has a daily limit** (200k tokens/day on this tier). `RateLimitRetryingChatModel` deliberately won't wait out a daily-limit 429 (the wait is minutes to hours), so jobs fail and retry on the queue's 60 s backoff until it frees up.
- **Background job failures are only visible in the log.** `EnrichmentJobPoller` logs each failure with its exception; the `enrichment_job` row keeps no reason.
- **Executors are explicit:** `EnrichmentQueueConfig` must define `applicationTaskExecutor` itself. Any custom `Executor` bean makes Boot stop auto-creating it. The two pools stay separate to avoid self-starvation (`enrichTopic` joins on sub-tasks).
- **Testing `@AiService` collaborators:** they can't be replaced with `@MockitoBean` (the LangChain4j proxy `FactoryBean` breaks). Tests hand-construct the service under test with stub implementations instead (see `TrackServiceTest`, `NodePipelineIntegrationTest`). Tests that call `EnrichmentService` directly must set `@TestPropertySource(properties = "enrichment.polling.enabled=false")` so the poller doesn't race them. Don't add a `src/test/resources/application.properties`, because it would shadow the main one.

## Invariants

These are load-bearing. Breaking one produces a bug that looks like a model quality problem but isn't.

**Resources are searched once per node, then persisted.** Never re-search at render time. Ticks are foreign-keyed to resource rows; if the row set changes underneath them, progress is orphaned. "Find more" appends, it does not replace.

**Never create a topic without normalizing and matching first.** Same concept reaches the tree via multiple parents. Normalize the label, match against existing nodes in the track, reuse the ID on hit. The tree is a DAG. Duplicate nodes cause duplicate searches and duplicate quiz questions.

**Track level goes into every LLM call.** Topic expansion, resource search, concept generation. Without it the model defaults to exhaustive and the gap report is permanently wrong.

**Ticking never blocks on a fetch.** Flip `ticked_at`, set `extraction_status = pending`, return immediately. All fetching and extraction happens in the worker.

**The fallback extractor never sees free text and never generates freely.** When a resource can't be fetched, it classifies against the topic's existing concept list. Constrained choice, not open generation. This is what stops the model inventing content a book never contained.

**Quiz questions attach to concepts, not topics or resources.** Deduplication of what gets asked happens at the concept layer.

**Interview and grading do not share context.** Pass 1 has no rubric in its prompt at all. Pass 2 is a fresh call over the finished transcript. If you find yourself passing the concept list into the interviewer "just for scoping," stop — that's the leak this split exists to prevent.

**A tick means consumed, not understood.** Ticks decide what gets asked. Self-ratings decide how it went. Don't let ticks feed mastery estimates.

## Conventions

- Every LLM call is logged with prompt, response, latency, and cost, keyed to the entity it produced.
- LLM calls return structured output; parse and validate before persisting. Never persist raw model text into a typed column.
- Validate resource URLs resolve before writing the row.
- Concepts carry `origin` — `upfront` or `extracted`. Never let extraction silently append without the flag.
- Worker jobs need retries and a dead-letter path. YouTube rate-limits, blogs 403.

## Don't

- Don't add an agent framework or orchestrator. The spine is a deterministic pipeline with LLM steps. The only agent is resource discovery, and it stays behind `find_resources(topic, level) -> Resource[]`.
- Don't add a completion state to topics. Resources are alternatives, so tick counts don't mean anything.
- Don't build spaced repetition yet. Log self-ratings so the data exists; the scheduler comes later.
- Don't put orchestration logic in request handlers.
