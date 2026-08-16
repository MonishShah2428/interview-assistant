package com.interviewprep.node.sse;

import com.interviewprep.node.entity.EnrichmentStatus;

/** Pushed to a track's subscribers once one of its topics finishes enriching. */
public record EnrichmentEvent(Long topicId, EnrichmentStatus status) {}
