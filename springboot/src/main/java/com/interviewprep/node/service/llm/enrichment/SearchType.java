package com.interviewprep.node.service.llm.enrichment;

/**
 * Search strategy passed through to {@link SearchTools#search}, matching the subset of Exa's own
 * {@code type} values that fit a bounded-latency tool call — Exa also offers {@code deep-lite},
 * {@code deep}, and {@code deep-reasoning} (4-40s each per Exa's own latency guidance), any one of
 * which could consume most of {@code AgenticResourceFinder}'s whole wall-clock budget by itself, so
 * those are deliberately left out. {@code neural} is also left out: Exa's docs call it "legacy
 * terminology rather than the recommended setting for new code" (see
 * https://docs.exa.ai/reference/search-api-guide-for-coding-agents#search-type-reference) — {@code
 * auto} already picks neural or keyword search per query automatically. Exposed to the LLM as a
 * tool-calling parameter (see {@code ResourceSearchToolAdapter#webSearch}) so it can pick a
 * strategy per query instead of every search running identically.
 */
public enum SearchType {
  /** Exa's default: balanced relevance and speed, ~1s. Right choice when unsure. */
  AUTO("auto"),
  /** Lower-latency, still good relevance, ~450ms — for exact terms, names, or error strings. */
  FAST("fast"),
  /** Lowest latency, basic depth, ~250ms — for quick lookups where speed matters most. */
  INSTANT("instant");

  private final String exaValue;

  SearchType(String exaValue) {
    this.exaValue = exaValue;
  }

  public String exaValue() {
    return exaValue;
  }
}
