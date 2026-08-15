package com.interviewprep.node.entity;

/**
 * Whether a topic's children have been generated yet. Fails independently of {@link
 * EnrichmentStatus}.
 */
enum ExpansionStatus {
  not_expanded,
  expanding,
  expanded,
  failed
}
