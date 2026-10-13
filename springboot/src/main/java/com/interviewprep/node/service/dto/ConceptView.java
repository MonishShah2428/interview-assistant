package com.interviewprep.node.service.dto;

import com.interviewprep.node.entity.ConceptOrigin;

public record ConceptView(Long id, String label, ConceptOrigin origin) {}
