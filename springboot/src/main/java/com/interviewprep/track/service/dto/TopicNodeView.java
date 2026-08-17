package com.interviewprep.track.service.dto;

import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import java.util.List;

/** One topic node, shaped to be reused by a future tree-read endpoint as well as track creation. */
public record TopicNodeView(
    Long id,
    String label,
    boolean isCore,
    ExpansionStatus expansionStatus,
    EnrichmentStatus enrichmentStatus,
    List<TopicNodeView> children) {}
