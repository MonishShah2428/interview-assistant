package com.interviewprep.node.service.dto;

import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;

public record ResourceDetailView(
    Long id,
    String title,
    String url,
    String citation,
    ResourceType type,
    ResourceLane lane,
    String sourceQuery,
    int position) {}
