package com.interviewprep.node.service.dto;

import com.interviewprep.node.entity.ExpansionStatus;
import java.util.List;

public record ExpandTopicResponse(ExpansionStatus status, List<ExpandedChild> children) {}
