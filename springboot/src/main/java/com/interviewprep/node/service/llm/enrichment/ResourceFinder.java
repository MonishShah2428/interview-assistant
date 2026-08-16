package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Resource discovery: the agent loop, query reformulation, and URL validation all live behind this
 * interface. EnrichmentService only knows "give me resources for this topic at this level," never
 * how they were found — matches the project's "don't add an agent framework" rule: the only agent
 * is resource discovery, and it stays behind exactly this shape, find_resources(topic, level) -&gt;
 * Resource[].
 *
 * <p>Implemented by {@link
 * com.interviewprep.node.service.llm.enrichment.impl.AgenticResourceFinder}.
 */
public interface ResourceFinder {

  /**
   * Implementations classify their own failures: throw {@link RetryableEnrichmentException} for a
   * transient condition worth retrying (e.g. a 429), {@link NonRetryableEnrichmentException} for
   * anything that fails identically on retry (e.g. a 401, a malformed model response).
   *
   * @param topicId scopes the {@code existingResourcesFor} check on a refresh — this call never
   *     reads or writes {@code Resource} rows itself, it only needs the id to hand to {@link
   *     SearchTools}.
   */
  List<ProposedResource> findResources(Long topicId, String topicLabel, String level);
}
