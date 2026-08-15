package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Resource discovery: the agent loop, query reformulation, and URL validation all live behind this
 * interface. EnrichmentService only knows "give me resources for this topic at this level," never
 * how they were found — matches the project's "don't add an agent framework" rule: the only agent
 * is resource discovery, and it stays behind exactly this shape, find_resources(topic, level) -&gt;
 * Resource[].
 *
 * <p>TODO: implement as an agentic loop, likely backed by {@link SearchTools}. Until then {@link
 * com.interviewprep.node.service.llm.enrichment.placeholder.PlaceholderResourceFinder} is the only
 * bean and always throws {@link NonRetryableEnrichmentException}.
 */
public interface ResourceFinder {

  /**
   * Implementations classify their own failures: throw {@link RetryableEnrichmentException} for a
   * transient condition worth retrying (e.g. a 429), {@link NonRetryableEnrichmentException} for
   * anything that fails identically on retry (e.g. a 401, a malformed model response).
   */
  List<ProposedResource> findResources(String topicLabel, String level);
}
