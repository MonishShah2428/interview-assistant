package com.interviewprep.node.service;

import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;

public record ResourceView(
    Long id, String title, String url, ResourceType type, ResourceLane lane) {}
