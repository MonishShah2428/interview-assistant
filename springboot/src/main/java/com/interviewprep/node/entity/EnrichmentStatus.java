package com.interviewprep.node.entity;

/**
 * Whether a topic's resources and concepts have been generated yet. Fails independently of {@link
 * ExpansionStatus}.
 */
enum EnrichmentStatus {
  pending,
  enriching,
  ready,
  failed
}
