package com.interviewprep.node.service.llm.enrichment.placeholder;

import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.ResourceFinder;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Placeholder until the resource-discovery agent loop is built. Always fails non-retryably — a
 * silently-empty result would look like a real (if empty) success and leave a topic falsely {@code
 * ready}; failing loudly sends the topic to {@code failed}, the honest state.
 */
@Component
class PlaceholderResourceFinder implements ResourceFinder {

  @Override
  public List<ProposedResource> findResources(String topicLabel, String level) {
    throw new NonRetryableEnrichmentException("ResourceFinder is not implemented yet");
  }
}
