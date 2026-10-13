# Session log — interview-assistant, resource-search quality (2026-08-26)

Detailed record of this session: the diagnostic work that motivated the change, the architectural
decision made, and how it was implemented and verified. Same format as
`session-log-2026-08-15.md`.

---

## Part 1 — Diagnosing a stuck live test (Groq TPM rate limit)

- User: "do not exhaust retries, how many topics does the test enrich now? enrich not more than 3
  for testing at the moment" — scoped down an in-progress live-test run before it burned through
  retries.
- User: "run this through a live test, but enrich only 1 topic, also return the results and
  diagnostic metrics like time taken and calls made to [Groq]" — asked for real instrumentation,
  not estimates.
- **Implemented (temporary, fully reverted before commit):** a `CountingChatModel implements
  ChatModel` wrapper overriding only `chat(ChatRequest)` (every other `ChatModel` method is
  `default`), plus `AtomicInteger` counters added directly to `ResourceSearchToolAdapter`, both
  marked `// TEMP debug - remove before commit`.
- **Result:** all 5 retry attempts on the single-topic test hit `RateLimitException` — root-caused
  via the instrumentation itself, not guessed: consistently ~4 Groq calls / ~3 `webSearch` calls
  per attempt, always failing on a later round needing ~4000-5300 tokens against the shared
  account's depleted 8000 TPM budget. **Not a code defect** — an account/budget constraint.
- Cleaned up: deleted the stuck job row, reset the topic's `enrichment_status` back to `pending`,
  reverted the diagnostic code via a full rewrite of `AgenticResourceFinder.java` back to its clean
  pre-diagnostic state (confirmed via `git diff` showing zero delta).

## Part 2 — Why results were mostly research papers, not learning content

- User: "show me the prompt and the output" → showed the actual Exa request/response for a live
  "CAP theorem distributed systems" search: 8/8 results were research literature (Brewer's
  original paper, an MIT paper, an arxiv critique, PDFs of lecture slides), not learning-oriented
  content (explainers, tutorials, GeeksforGeeks-style writeups).
- User: "why is this mostly return papers? can we ask it to focus more on specific websites, say
  like mdn webdocs, geeksforgeeks etc"
- User: "when we do include domain in the params, will it only respond with those, or those will
  definitely be included, how does it work?" — verified directly against Exa's docs rather than
  assumed: `includeDomains` is a **hard filter** ("only return results from these domains"), not a
  ranking boost. Applying it broadly risks a short or empty result set on any topic the fixed
  domain list doesn't cover.
- User: "can we try and make it specifically return those better ones first, but in the prompt we
  provide it without providing an include or exclude list?" — checked whether query text alone
  (`site:` operators, naming a domain) could steer results. Confirmed against Exa's docs: **it
  cannot** — Exa's own guidance explicitly warns against relying on this ("do not duplicate the
  filter with a site: operator in query"). Domain targeting only happens through
  `includeDomains`/`excludeDomains`.
- User: "describe the exact scenario we are in, and what we are looking for that exa ai cant do" →
  explained the gap in plain terms: Exa can restrict *which sites* appear (hard filter) or search
  the open web, but has no middle setting that says "prefer learning-oriented sites over research
  ones without excluding everything else" — that steering has to come from how the query itself is
  phrased, or from a curated domain list, not from a search-provider setting.
- User: "can you rewrite this but better define trusted sites as those that were meant for learning
  and less for research" — refined the framing: "trusted" domains for this project mean sites
  built for teaching a concept (docs, tutorials, structured practice), not sites whose primary
  purpose is publishing original research.

## Part 3 — The architectural decision: fully agentic, 3 tools, LLM decides

User pasted an external proposal with 5 suggestions for improving resource-search quality, asking:
"lets try and understand, clear your doubts, also implement only suggestions 1 and 2 right now,
explain how this will probably affect token usage, and run a test only on exa ai and show the
output."

- Suggestion 1: split the single undifferentiated `webSearch` tool into domain-filtered tools per
  content lane (video, practice) plus the existing open-web reference search.
  Suggestion 2: phrase search queries as natural, descriptive sentences instead of bare keyword
  lists, since research papers and beginner tutorials read very differently even on the same
  topic.
- Two doubts resolved before implementing (both verified live against Exa's docs, not assumed):
  `useAutoprompt` is **deprecated and does nothing** on the current API (Exa's own "Common
  Mistakes" section) — dropped from consideration entirely, despite being suggested externally.
  `contents.highlights` vs `contents.text` — highlights (query-relevant excerpts) are Exa's
  documented recommendation for agent tool-calling workflows, ~10x fewer tokens for comparable
  relevance; already in use from earlier in the project, unchanged here.
- **Decision point, via `AskUserQuestion`:** two designs were on the table for suggestion 1 —
  (a) code deterministically fans out and issues the video/practice searches itself, guaranteeing
  slot coverage and cutting Groq token usage, but taking search judgment away from the LLM; or
  (b) keep it fully agentic — give the LLM three tools (`webSearch`, `searchVideos`,
  `searchPracticeProblems`) and let it decide itself when to call each.
  **User chose (b): "Keep it fully agentic — LLM gets 3 tools, decides when to call each."**
  This means slot coverage isn't guaranteed (if the LLM never calls `searchVideos`, a topic can end
  up with no video resource) and Groq token usage is **not** reduced — all three search "slots"
  still go through the same tool-calling loop, round-trip count could go up slightly in the worst
  case. The upside, documented honestly rather than oversold: domain-filtered searches return a
  more homogeneous, on-target candidate pool per call, which should mean less wasted
  reformulation/retry inside the loop than today's undifferentiated search hitting the wrong
  content type — but the net token effect is genuinely uncertain without measuring.
  Query-shaping (suggestion 2) has ~no token effect either way: Exa embeds the query server-side,
  which isn't Groq-token-metered; only a few dozen extra system-prompt tokens are added once per
  call, independent of topic.
- Also confirmed via the same `AskUserQuestion`: **"Yes, drop it"** regarding `useAutoprompt`.
- Plan written and approved via `ExitPlanMode` (full plan preserved at
  `C:\Users\tomon\.claude\plans\quizzical-weaving-koala.md`).

## Part 4 — Implementation

- `SearchTools.search` — signature changed from `(String query, SearchType type)` to
  `(String query, SearchType type, List<String> includeDomains)`; javadoc updated to state plainly
  that `includeDomains` is a hard filter, so a caller that always wants the open web must pass
  `List.of()`, never a domain list it isn't confident covers the query.
- `ExaSearchTools` — `search()` forwards `includeDomains`; `ExaRequest` record gained an
  `includeDomains` field annotated `@JsonInclude(JsonInclude.Include.NON_EMPTY)` so Jackson omits
  the field entirely when empty/null, since omitting the field (not sending an empty array) is
  Exa's documented "no filter" state. Class javadoc records that `useAutoprompt` is deliberately
  never sent.
- `AgenticResourceFinder` — added `VIDEO_DOMAINS = List.of("youtube.com")` and
  `PRACTICE_DOMAINS = List.of("leetcode.com", "neetcode.io", "interviewing.io",
  "hellointerview.com", "educative.io")` (tunable defaults, not exhaustive — noted in a comment
  that a topic whose only good resource lives outside these lists simply won't get one from that
  tool, and the LLM still has `webSearch` as a fallback). `ResourceSearchToolAdapter` gained
  `searchVideos`/`searchPracticeProblems` alongside `webSearch`; the domain list is baked into
  which method the LLM calls, never an LLM-set parameter, so the LLM can't hard-filter itself into
  zero results by choosing a bad domain list itself.
- `ResourceFinderAi` — system prompt rewritten to document all three tools and instruct the model
  to call all three across a topic rather than defaulting to `webSearch` alone (accepting that a
  lane can come up short if search genuinely finds nothing there), plus query-shaping guidance with
  one example phrasing per tool.

## Part 5 — Live verification, Exa-only (no Groq), and what it actually showed

Per the user's explicit ask ("run a test only on exa ai"), extended
`ExaSearchToolsLiveTest.java` (already gated on `EXA_API_KEY`, skips cleanly without one) with:
`includeDomainsRestrictsResultsToVideoPlatforms`, `includeDomainsRestrictsResultsToPracticeSites`,
`queryPhrasingComparisonForTheReferenceLane`. All 5 tests in the class passed live against the real
Exa API (22.57s total).

- User: "show the output" → shown.
- User: "give me the returned links" / "give me the links" → raw URLs from the CAP theorem test
  pulled out and listed directly.
- User: "i am not well aware of all of these websites, do you know if these are well reputed sites
  specifically used for learning" → gave an honest, site-by-site reputation assessment rather than
  assuming familiarity.
- User: "can we take a sure example where gfg should appear, and check if it does really appear?"
  — requested a targeted test on a topic GeeksforGeeks is known to cover well (Dijkstra's
  algorithm), specifically to check whether the query-shaping approach can guarantee one named
  site's appearance.
- **Finding, confirmed on two separate topics (CAP theorem, Dijkstra's algorithm):** natural-
  sentence phrasing reliably shifts results away from academic papers toward tutorial/blog content
  — but it does **not** guarantee any one specific named site (e.g. GeeksforGeeks) appears. It
  shifts the *category* of site found, not targets an individual domain. This is the honest, tested
  limit of suggestion 2 alone, without also adding that specific site to a hard `includeDomains`
  list.
- User: "give me the links" (for the Dijkstra's algorithm test) → shown.

## Part 6 — Query-composition follow-up (identified, not yet implemented)

- User: "rather than a generic natural sentence for all the topics can we have an ai agent create
  that natural keyword search" — asked whether query composition should be made more dynamic per
  topic rather than templated example phrasing baked into the system prompt.
- **Decision point, via `AskUserQuestion`:** two options were on the table — introduce a separate
  query-composition step/call, vs. strengthen the existing prompt in place (same LLM, same
  tool-calling call, no new architecture, no extra Groq calls) by explicitly instructing the model
  to compose a fresh, topic-specific query each time rather than copying the example phrasing
  verbatim, and giving 2-3 varied examples per tool instead of just one.
  **User chose: "Strengthen the existing prompt (Recommended)."**
- Not yet implemented at the point this log was written — the current `ResourceFinderAi` system
  prompt still has exactly one example query template per tool (`webSearch`, `searchVideos`,
  `searchPracticeProblems`); the approved next step is to add the anti-verbatim-copying instruction
  and expand each to 2-3 varied examples.

## Part 7 — Commit and push

- User: "commit and push" → nothing new to stage (the prompt-strengthening edit from Part 6 had not
  been started yet — only read). Pushed the 2 already-existing local commits to `origin/main`:
  `de4a857..b281698` (`24d7be4` "Expose Exa's native search type to the LLM and fix stale search
  config" and `b281698` "Add domain-filtered video/practice search tools and shape reference
  queries"). First push attempt timed out at 30s (general system/network slowness observed
  throughout the session); retried at 120s, succeeded.

## Current state / pending

- `AgenticResourceFinder` now exposes three tools (`webSearch`, `searchVideos`,
  `searchPracticeProblems`), fully agentic — the LLM decides which to call and when; slot coverage
  is not guaranteed by design.
- `ResourceFinderAi`'s system prompt needs the Part 6 strengthening (anti-copying instruction +
  2-3 examples per tool) — approved but not yet implemented.
- Honest, documented tradeoff standing from Part 3: this whole change does not reduce Groq token
  usage versus the old single-`webSearch` design: the fully-agentic choice trades that reduction
  for keeping search judgment inside the LLM's control.
