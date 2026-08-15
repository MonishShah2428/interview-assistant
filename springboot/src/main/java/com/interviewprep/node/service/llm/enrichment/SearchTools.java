package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Raw web search — the lower-level capability a future agentic {@link ResourceFinder}
 * implementation calls from inside its loop. EnrichmentService never calls this directly; it only
 * talks to {@link ResourceFinder}. The real implementation's {@link #search} method is the natural
 * place for a LangChain4j {@code @Tool} annotation once the agent loop exists.
 *
 * <p>TODO: implement against a real search provider. Until then {@link
 * com.interviewprep.node.service.llm.enrichment.placeholder.PlaceholderSearchTools} is the only
 * bean and always throws {@link NonRetryableEnrichmentException}.
 */
public interface SearchTools {

  /**
   * Runs one web search and returns raw, unvalidated hits — no URL resolution, no classification.
   */
  List<SearchResult> search(String query);
}
