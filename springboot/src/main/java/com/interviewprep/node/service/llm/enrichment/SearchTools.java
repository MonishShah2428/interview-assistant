package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Raw, dumb capabilities a {@link ResourceFinder} agent loop calls as tools — no filtering, no
 * ranking, no deciding. Judgment about what's good enough belongs entirely to {@link
 * ResourceFinder}; if a caller ever wants "return only good results" from here, that's a sign the
 * judgment leaked into the wrong layer.
 *
 * <p>Implemented by {@link com.interviewprep.node.service.llm.enrichment.impl.TavilySearchTools}.
 */
public interface SearchTools {

  /**
   * Runs one web search and returns raw, unvalidated hits — no URL resolution, no classification.
   */
  List<SearchResult> search(String query);

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
