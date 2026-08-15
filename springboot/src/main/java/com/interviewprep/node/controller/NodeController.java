package com.interviewprep.node.controller;

import com.interviewprep.node.service.NodeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sync pipeline, user is waiting. Budget: 3-5s. */
@RestController
@RequestMapping("/tracks")
class NodeController {

  private final NodeService nodeService;

  NodeController(NodeService nodeService) {
    this.nodeService = nodeService;
  }

  @PostMapping
  ResponseEntity<Void> createTrack(@RequestBody CreateTrackRequest request) {
    // TODO: decompose goal/JD into top-level topics, 2 levels deep
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @PostMapping("/{trackId}/topics/{topicId}/expand")
  ResponseEntity<Void> expandTopic(@PathVariable Long trackId, @PathVariable Long topicId) {
    // TODO: normalize + match label against existing nodes in the track before creating children
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
