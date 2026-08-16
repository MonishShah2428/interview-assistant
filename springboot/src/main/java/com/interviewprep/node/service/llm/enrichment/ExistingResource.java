package com.interviewprep.node.service.llm.enrichment;

import com.interviewprep.node.entity.ResourceLane;

/** A resource a topic already has, surfaced to a {@link ResourceFinder} refresh so it appends. */
public record ExistingResource(String title, String url, ResourceLane lane) {}
