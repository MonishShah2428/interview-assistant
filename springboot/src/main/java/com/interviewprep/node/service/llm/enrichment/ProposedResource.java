package com.interviewprep.node.service.llm.enrichment;

import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;

/**
 * A resource {@link ResourceFinder} proposes, already validated and classified. Field shape mirrors
 * {@link com.interviewprep.node.entity.Resource}'s constructor minus {@code position} — ordering is
 * assigned by {@link com.interviewprep.node.service.EnrichmentService} from list order, not by the
 * finder.
 */
public record ProposedResource(
    ResourceType type,
    ResourceLane lane,
    String title,
    String url,
    String citation,
    String sourceQuery) {}
