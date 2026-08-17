package com.interviewprep.node.entity;

/** What an {@link EnrichmentJob} is actually asking for. */
public enum EnrichmentJobKind {
  /** First-time enrichment: resources and concepts, via {@code EnrichmentService.enrichTopic}. */
  initial,

  /** "Find more resources" for an already-enriched topic, via {@code refreshResources}. */
  resource_refresh
}
