package com.interviewprep.node.service.dto;

import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * One topic with everything enrichment produced for it. The tree read ({@code TopicNodeView}) stays
 * labels-and-statuses only so it stays cheap for a sixty-node track; this is the per-topic read a
 * client makes once it knows which topic it's showing.
 */
public record TopicDetailView(
    Long id,
    String label,
    boolean isCore,
    boolean isRoot,
    ExpansionStatus expansionStatus,
    EnrichmentStatus enrichmentStatus,
    OffsetDateTime searchedAt,
    /** A search for this topic is queued or running, e.g. the queue's retry after a failure. */
    boolean searchQueued,
    List<ResourceDetailView> resources,
    List<ConceptView> concepts) {}
