package com.interviewprep.node.controller;

import com.interviewprep.node.service.ExpansionService;
import com.interviewprep.node.service.NodeService;
import com.interviewprep.node.service.dto.ExpandTopicResponse;
import com.interviewprep.node.sse.EnrichmentEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sync pipeline, user is waiting. Budget: 3-5s (except the SSE subscribe endpoint). */
@RestController
@RequestMapping("/tracks")
class NodeController {

  private final NodeService nodeService;
  private final ExpansionService expansionService;
  private final EnrichmentEventPublisher enrichmentEventPublisher;

  NodeController(
      NodeService nodeService,
      ExpansionService expansionService,
      EnrichmentEventPublisher enrichmentEventPublisher) {
    this.nodeService = nodeService;
    this.expansionService = expansionService;
    this.enrichmentEventPublisher = enrichmentEventPublisher;
  }

  @PostMapping
  ResponseEntity<Void> createTrack(@RequestBody CreateTrackRequest request) {
    // TODO: decompose goal/JD into top-level topics, 2 levels deep
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }

  @PostMapping("/{trackId}/topics/{topicId}/expand")
  ResponseEntity<ExpandTopicResponse> expandTopic(
      @PathVariable Long trackId, @PathVariable Long topicId) {
    return ResponseEntity.ok(expansionService.expandTopic(topicId));
  }

  @GetMapping(path = "/{trackId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter subscribeToEvents(@PathVariable Long trackId) {
    return enrichmentEventPublisher.subscribe(trackId);
  }
}
