package com.interviewprep.node.service;

import java.util.List;

public record ExpandedChild(
    Long topicId, String label, boolean isCore, List<ResourceView> resources) {}
