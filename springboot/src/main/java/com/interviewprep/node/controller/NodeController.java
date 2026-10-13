package com.interviewprep.node.controller;

import com.interviewprep.node.service.ExpansionService;
import com.interviewprep.node.service.TopicQueryService;
import com.interviewprep.node.service.dto.ExpandTopicResponse;
import com.interviewprep.node.service.dto.TopicDetailView;
import com.interviewprep.node.sse.EnrichmentEventPublisher;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Sync pipeline, user is waiting. Budget: 3-5s (except the SSE subscribe endpoint). */
@RestController
@RequestMapping("/tracks")
class NodeController {

  private final ExpansionService expansionService;
  private final TopicQueryService topicQueryService;
  private final EnrichmentEventPublisher enrichmentEventPublisher;

  NodeController(
      ExpansionService expansionService,
      TopicQueryService topicQueryService,
      EnrichmentEventPublisher enrichmentEventPublisher) {
    this.expansionService = expansionService;
    this.topicQueryService = topicQueryService;
    this.enrichmentEventPublisher = enrichmentEventPublisher;
  }

  @GetMapping("/{trackId}/topics/{topicId}")
  ResponseEntity<TopicDetailView> getTopic(@PathVariable Long trackId, @PathVariable Long topicId) {
    return ResponseEntity.ok(topicQueryService.getTopic(trackId, topicId));
  }

  @PostMapping("/{trackId}/topics/{topicId}/expand")
  ResponseEntity<ExpandTopicResponse> expandTopic(
      @PathVariable Long trackId, @PathVariable Long topicId) {
    return ResponseEntity.ok(expansionService.expandTopic(trackId, topicId));
  }

  @PostMapping("/{trackId}/topics/{topicId}/resources/refresh")
  ResponseEntity<Void> refreshResources(@PathVariable Long trackId, @PathVariable Long topicId) {
    expansionService.requestResourceRefresh(trackId, topicId);
    return ResponseEntity.accepted().build();
  }

  @GetMapping(path = "/{trackId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter subscribeToEvents(@PathVariable Long trackId) {
    return enrichmentEventPublisher.subscribe(trackId);
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Void> handleNotFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }
}
