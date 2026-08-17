package com.interviewprep.track.service.dto;

import java.util.List;

/** A track plus its topic nodes — same shape a future tree-read endpoint will produce. */
public record TrackView(Long id, String goal, String level, List<TopicNodeView> roots) {}
