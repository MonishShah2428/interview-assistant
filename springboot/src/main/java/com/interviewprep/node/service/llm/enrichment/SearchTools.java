package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Raw, dumb capabilities a {@link ResourceFinder} agent loop calls as tools — no filtering, no
 * ranking, no deciding. Judgment about what's good enough belongs entirely to {@link
 * ResourceFinder}; if a caller ever wants "return only good results" from here, that's a sign the
 * judgment leaked into the wrong layer.
 *
 * <p>Implemented by {@link com.interviewprep.node.service.llm.enrichment.impl.ExaSearchTools}.
 */
public interface SearchTools {

  /**
   * Runs one web search and returns raw, unvalidated hits — no URL resolution, no classification.
   * {@code type} is Exa's own search-strategy field, passed through as-is so the caller (the
   * tool-calling LLM, via {@code ResourceSearchToolAdapter}) can choose per query; result count is
   * fixed server-side regardless of {@code type} — see {@link
   * com.interviewprep.node.service.llm.enrichment.impl.ExaSearchTools}'s {@code MAX_RESULTS} —
   * since that bounds the token cost of every downstream tool-calling round trip.
   *
   * <p>{@code includeDomains}, when non-empty, is Exa's own hard domain filter — "only return
   * results from these domains," not a ranking boost — so a caller that always wants the open web
   * must pass {@code List.of()}, never a domain list it isn't confident covers the query, or it
   * risks a short or empty result set with no fallback to the wider web.
   */
  List<SearchResult> search(String query, SearchType type, List<String> includeDomains);

  /**
   * Resolves {@code url} and reports whether it's live. HEAD by default; some hosts reject HEAD, in
   * which case a ranged GET is tried before giving up — a bare 405 is not treated as dead. Never
   * throws for an unresolvable URL: that's a normal negative result for the agent to react to, not
   * a service failure.
   */
  boolean validateUrl(String url);

  /** What {@code topicId} already has, so a refresh appends instead of re-proposing. */
  List<ExistingResource> existingResourcesFor(Long topicId);
}
