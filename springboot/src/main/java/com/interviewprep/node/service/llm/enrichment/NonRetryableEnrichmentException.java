package com.interviewprep.node.service.llm.enrichment;

/**
 * A {@link ResourceFinder} or {@link ConceptGenerator} failure that will fail identically on retry
 * — bad credentials, a malformed model response, "not implemented yet." {@link
 * com.interviewprep.node.service.EnrichmentService} never retries this type.
 */
public class NonRetryableEnrichmentException extends RuntimeException {

  public NonRetryableEnrichmentException(String message) {
    super(message);
  }

  public NonRetryableEnrichmentException(String message, Throwable cause) {
    super(message, cause);
  }
}
