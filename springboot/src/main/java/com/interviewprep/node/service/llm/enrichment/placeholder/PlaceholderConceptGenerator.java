package com.interviewprep.node.service.llm.enrichment.placeholder;

import com.interviewprep.node.service.llm.enrichment.ConceptGenerator;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.ProposedConcept;
import java.util.List;
import org.springframework.stereotype.Component;

/** Placeholder until concept generation is built. See {@link PlaceholderResourceFinder}. */
@Component
class PlaceholderConceptGenerator implements ConceptGenerator {

  @Override
  public List<ProposedConcept> generateConcepts(String topicLabel, String level) {
    throw new NonRetryableEnrichmentException("ConceptGenerator is not implemented yet");
  }
}
