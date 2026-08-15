package com.interviewprep.node.service.llm.enrichment;

/**
 * A {@link ResourceFinder} or {@link ConceptGenerator} failure worth retrying — a transient
 * condition (e.g. a rate limit) that may succeed on a later attempt. {@link
 * com.interviewprep.node.service.EnrichmentService} retries a bounded number of times on this type
 * and fails immediately on anything else.
 */
public class RetryableEnrichmentException extends RuntimeException {

  public RetryableEnrichmentException(String message) {
    super(message);
  }

  public RetryableEnrichmentException(String message, Throwable cause) {
    super(message, cause);
  }
}
