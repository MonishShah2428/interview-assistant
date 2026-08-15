package com.interviewprep.node.service.llm.enrichment.placeholder;

import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import java.util.List;
import org.springframework.stereotype.Component;

/** Placeholder until a real search provider is wired in. See {@link PlaceholderResourceFinder}. */
@Component
class PlaceholderSearchTools implements SearchTools {

  @Override
  public List<SearchResult> search(String query) {
    throw new NonRetryableEnrichmentException("SearchTools is not implemented yet");
  }
}
