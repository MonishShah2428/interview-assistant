package com.interviewprep.node.service;

import org.springframework.stereotype.Service;

/**
 * Track creation and tree expansion. Every call here takes the track's level as input — omitting it
 * is what causes the model to default to exhaustive and makes gap reports permanently wrong.
 */
@Service
public class NodeService {
  // TODO: decompose track (goal/JD, level) -> top-level topics, 2 levels deep
  // TODO: expand node (topic, parent chain, sibling labels, level) -> child topics, core/optional
  // TODO: search resources (node label, level) -> laned resource rows, persisted once
  // TODO: generate concepts (node label, level) -> concept list, origin=upfront
}
