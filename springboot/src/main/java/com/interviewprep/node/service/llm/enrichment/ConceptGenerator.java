package com.interviewprep.node.service.llm.enrichment;

import java.util.List;

/**
 * Concept-list generation for a topic, scoped to the track's level — the "upfront" half of the
 * concept layer (the other half, extraction at tick time, belongs to the future tick pipeline).
 *
 * <p>TODO: implement as an {@code @AiService}, same style as {@link
 * com.interviewprep.node.service.llm.expansion.TopicExpander}. Until then {@link
 * com.interviewprep.node.service.llm.enrichment.placeholder.PlaceholderConceptGenerator} is the
 * only bean and always throws {@link NonRetryableEnrichmentException}.
 */
public interface ConceptGenerator {

  /**
   * Proposes the concepts {@code topicLabel} covers at {@code level}. See {@link ResourceFinder}
   * for the retryable/non-retryable failure contract.
   */
  List<ProposedConcept> generateConcepts(String topicLabel, String level);
}
