# Agentic interview prep tool — design spec

## What it is

A chat-first tool that turns a vague prep goal ("get me ready for an SDE II role", or a pasted JD) into a navigable tree of topics. Each topic carries a menu of real, searched resources — books, blogs, videos, LeetCode problems — that you tick off as you consume them. At the end of a session you ask it to quiz you, and it tests you on **what the resources you consumed actually taught**, not on the topic names.

The three things that distinguish it from a static roadmap:

1. It remembers what you actually did, not just what you were told to do.
2. Resources are alternatives, not a checklist — pick a lane, don't grind all four.
3. The review layer is grounded in consumed content, so it can't quiz you on material you were never given.

---

## Core flows

### Entry

Two ways in, converging on the same structure:

- **Broad** — "prep me for an SDE role" or a pasted job description. The model decomposes it into a track with top-level topics.
- **Narrow** — "I want to prepare for system design." Creates a track seeded at that topic.

A **track** carries the goal, target level, and source JD if any. Everything downstream is scoped to it. The level is not decoration — it is passed into every subsequent LLM call and is the main thing preventing scope explosion.

### Tree expansion — hybrid

Two levels are generated upfront so you have something to browse immediately. Anything deeper is expanded on demand, one node at a time.

**Depth control.** Recursion has no natural floor. Left alone the model will happily produce `Hash Maps → Collision Resolution → Open Addressing → Robin Hood Hashing`, which is three levels past anything an interviewer will ask. Two mitigations:

- Every node is tagged `core` or `optional` relative to the track's level.
- The expansion prompt receives the level and is instructed to mark when further depth stops being interview-relevant, so the UI can warn rather than silently keep going.

**Node identity.** The same concept will surface under multiple parents — `Sharding` under both Databases and System Design, `Sliding Window` under both Arrays and Strings. Before creating a node, normalize the label (lowercase, strip punctuation, trim qualifiers) and match against existing nodes in the same track. On match, reuse the existing node ID; the tree is a DAG, not a strict tree. On miss, create.

This matters because a duplicated node means duplicated searches, duplicated ticks, and being quizzed twice on one idea.

The expansion prompt must also receive the current sibling labels and parent chain, so the model can avoid emitting near-duplicates in the first place rather than relying on post-hoc dedupe to catch them.

### Resources

**Searched live, once per node.** Search fires at node creation. The results are persisted as rows with their own IDs and never silently re-run.

This is deliberate. If resources were re-searched at render time, the top five would drift between sessions and your ticks would be orphaned — you'd tick a video on Monday and find it gone on Tuesday. Refresh is an explicit user action ("find more resources for this") that **appends** new rows rather than replacing the set. It also saves a search round-trip on every tree expansion.

**Resources are options, not requirements.** Four resources on a node are four ways to learn the same thing. Watch the video *or* read the chapter. This is why raw tick counts are not a progress metric.

**Lanes.** Bucket resources into `read` / `watch` / `practice`. This makes the menu legible — you can see at a glance you've only ever watched videos and never solved a problem — and it gives the UI something meaningful to display in place of a fake completion ratio.

**Staleness.** Stamp `searched_at`. A node cached in March has dead YouTube links by September. Re-validate on read past some age threshold, or at minimum let "find more" refresh rather than only append.

### Ticks

A tick means **"I consumed this"** — not "I know this." The two diverge constantly; you will watch a video, tick it, and still bomb the question.

Keeping them separate is what keeps the review layer calibrated:

- Ticks determine **what** gets asked.
- Review self-ratings determine **how well you did**.

A topic is never marked "covered." There is no completion state on a topic at all. This falls out of resources-as-options: with alternatives rather than requirements, no tick count means anything, so the concept of a finished topic doesn't apply.

### Concepts and coverage

Two sources feed the concept layer.

**Topic-level concept list, generated upfront.** When a node is created — same LLM call as the search — the model also emits the set of concepts that topic covers, scoped to the track's level. `Caching` for an SDE II interview is a different list than `Caching` for a distributed systems PhD, and if the level isn't passed through, the model defaults to the exhaustive version and your gap report will show you permanently 30% covered.

This list does two jobs. It fixes the vocabulary so extraction can't produce "LRU eviction" from one resource and "least-recently-used policy" from another as separate rows. And it makes gap analysis possible: concepts with no coverage from any ticked resource are exactly what your resources never taught you.

**Resource-level extraction, at tick time.** When you tick a resource, fetch its content and extract which concepts it covered.

Extraction runs at tick time rather than search time — you surface ten resources and consume two, so there's no reason to pay for the eight you skipped.

Extractability varies sharply by type:

| Type | Method | Reliability |
|---|---|---|
| YouTube | Transcript API / yt-dlp | High |
| Blog, docs | Fetch + text extract | High |
| LeetCode | Problem statement + patterns | High |
| Book chapter | No fetch path — see fallback | Shaky |
| Paywalled, PDF | Usually nothing | Fails |

**Fallback for unfetchable resources.** Books are the awkward case: you can't fetch DDIA chapter 3, but it's probably the best thing you read that week and shouldn't become invisible to the review layer. Instead of letting the model freely infer content from the title — which produces confident fabrication — hand it the topic's existing concept list and ask which entries this resource likely covers. Classification against a fixed set. The worst case is picking a wrong concept off a real list, rather than inventing one that doesn't exist.

Extraction may append genuinely new concepts not on the topic list, but this should be a deliberate, flagged addition rather than silent accretion.

**Ticking must not block on the fetch.** A transcript pull takes seconds; a blog fetch can hang; Cloudflare will sometimes just refuse. The checkbox flips instantly and enqueues a job. Every resource carries `extraction_status`: `pending` / `done` / `failed` / `unfetchable`. The quiz layer must handle a resource still in flight — skip it, or say so.

Where extraction confidence is low, the quiz should say "I'm not certain what this chapter covered, so this question is general" rather than bluffing.

### Review

Triggered as a chat command, not a button — "quiz me", "quiz me on caching", "quiz me on everything from today", "quiz me on things I ticked but was never tested on". A text command gets scoping for free; a button would force you to build a scope picker.

**Questions attach to concepts, not topics or resources.** If two resources both taught cache invalidation, you get asked once.

Two modes for now:

- **Quiz** — direct Q&A over covered concepts.
- **Mock interview** — conversational, follow-ups, pushback.

Both read the same inputs (ticked resources → coverage rows → concepts) and differ mainly in prompt and turn structure, so they're cheap to build together.

**Flashcards / spaced repetition are deferred.** That one is a real subsystem, not a prompt — it needs a scheduler (SM-2 or FSRS), intervals, ease factors, and cards that resurface weeks later. Log every review answer with a self-rating from day one so the data is there when you want it, and add scheduling on top once you know you actually keep using the tool.

**Open question:** whether a quiz takes over the main thread or opens a separate session. Taking over is simpler to build, but then prep history and interview transcripts interleave in one log, which gets messy when you want to see how you answered something last week. A separate session record still rendered as chat is probably the middle ground.

---

## Interface

Chat-first, with a persistent tree rail as a spatial anchor. Without the rail you lose your place in a sixty-node tree; with it, the chat stays the only interaction model.

**The rail is navigation and status, not content.** Clicking a node doesn't open a topic page — it drops a message into the chat ("show me caching") and the assistant responds with the resource card. One interaction model, not two.

**Resource cards must be re-renderable.** A card isn't static message text. You tick a box in a card from twenty minutes ago and it has to persist. Cards render from stored rows keyed by `topic_id`; the message is a container that says "render topic 47's resources here."

**The rail needs collapse.** Five top-level topics with children makes that column taller than the chat. Collapsed by default except the active branch.

---

## Data model

Node cache is **per user**. Each user's tree is their own. This costs more — every user re-searches "Arrays" from scratch — but it keeps personalization intact and avoids stale shared state. If it bites, a global read-through cache can go underneath the per-user layer later without changing anything above it.

The immediate benefit: ticks fold directly into the resource row. No user-state join table.

```
Track     → id, user_id, goal, level, source_jd

Topic     → id, track_id, parent_id, label, normalized_label,
            depth, is_core, searched_at

Resource  → id, topic_id, type, lane, title, url,
            source_query, ticked_at, extraction_status

Concept   → id, topic_id, label, origin(upfront|extracted)

Coverage  → resource_id, concept_id, confidence

Session   → id, user_id, date, topic_ids[]

Review    → id, concept_id, resource_id, mode,
            asked_at, self_rating
```

`Topic.parent_id` allows multiple parents (DAG) — model it as a join table if your ORM fights you on this.

Gap analysis is a query, not a feature: concepts on a topic with no `Coverage` row from any ticked resource.

---

## LLM call contracts

| Call | Input | Output |
|---|---|---|
| Decompose track | Goal or JD text, level | Top-level topics, 2 levels deep |
| Expand node | Node, parent chain, sibling labels, level | Child topics, each `core`/`optional` |
| Search resources | Node label, level | Web search → resource rows, laned |
| Generate concepts | Node label, level | Concept list, scoped to level |
| Extract coverage | Fetched text, topic concept list | Concept IDs covered + confidence |
| Classify (fallback) | Title/URL, topic concept list | Concept IDs likely covered, low confidence |
| Quiz | Concepts, source resources, mode | Questions, then grading + self-rating prompt |

The two extraction calls are deliberately separate. The primary one sees real text; the fallback never does and must be constrained to picking from a list.

---

## Build order

1. Track creation, tree expansion, the rail. No resources yet — prove the tree feels right and the depth control works.
2. Search + persisted resource rows + lanes + ticking. Now it's useful even with no review layer.
3. Concept generation at node creation.
4. Extraction pipeline, fetchable types only, async with status.
5. Quiz + mock interview over concepts.
6. Unfetchable fallback classification.
7. Gap report ("what your resources never covered").
8. Spaced repetition, if you're still using it by then.

---

## Known risks

**Extraction quality is the real unknown.** Everything downstream of it — quiz fairness, gap accuracy — depends on whether concept extraction produces something that matches what you actually learned. You'll only find out by ticking twenty real resources and seeing whether the questions feel fair. Build steps 4 and 5 close together and evaluate them as one unit.

**Hallucinated resources.** LLMs confidently produce dead YouTube URLs and LeetCode numbers that point at a different problem. Live search mitigates most of this, but validate URLs resolve before persisting rows.

**Concept list drift.** If extraction is allowed to freely append new concepts, the vocabulary degrades back into synonyms over time. Keep appended concepts flagged by origin and review them.

**Self-report gap.** A tick asserts consumption. Someone ticking things they skimmed will get a review layer that overestimates them. Unavoidable given the design; the self-rating log is the corrective.
