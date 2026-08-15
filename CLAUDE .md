# Interview prep tool

Chat-first agentic study tool. A user states a prep goal, gets a tree of topics, each with a menu of searched resources they tick as consumed. A review layer quizzes them on what those resources actually taught.

Full design rationale: @docs/spec.md — read it before changing anything in the pipelines, the concept layer, or the review flow.

## Stack

- Language / framework: Java, Spring Boot (Maven)
- DB: Postgres
- Queue: Postgres-backed job table (transactional enqueue, poll with `SELECT ... FOR UPDATE SKIP LOCKED`), not a separate broker
- LLM layer: Spring AI (structured output converters, tool calling for resource search)
- Test command: `mvn test`
- Lint / format command: `mvn spotless:apply`
- Run command: `mvn spring-boot:run`

## Architecture

Three pipelines behind a thin API layer, separated by latency tolerance:

- `node` — sync, user is waiting. Expand topics, dedupe, search, generate concepts. Budget 3-5s.
- `tick` — async. Flip the tick, enqueue, return. Worker fetches and extracts.
- `review` — sync but long. Quiz inline, or interview then grade.

Everything writes to Postgres. The search agent is the only autonomous component.

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
