package com.interviewprep.track.service.dto;

import java.time.OffsetDateTime;

/** A track without its tree, for listing. */
public record TrackSummaryView(Long id, String goal, String level, OffsetDateTime createdAt) {}
