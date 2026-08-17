package com.interviewprep.track.controller;

import com.interviewprep.track.service.TrackService;
import com.interviewprep.track.service.dto.TrackView;
import com.interviewprep.track.service.llm.TrackDecompositionException;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transport only: accept, validate shape, delegate, serialize, map exceptions. Never touches a
 * repository or the decomposer directly — that's {@link TrackService}'s job.
 */
@RestController
@RequestMapping("/tracks")
class TrackController {

  private final TrackService trackService;

  TrackController(TrackService trackService) {
    this.trackService = trackService;
  }

  @PostMapping
  ResponseEntity<TrackView> createTrack(@Valid @RequestBody CreateTrackRequest request) {
    TrackView track = trackService.createTrack(request.goal(), request.level());
    return ResponseEntity.status(HttpStatus.CREATED).body(track);
  }

  @GetMapping("/{trackId}")
  ResponseEntity<TrackView> getTrack(@PathVariable Long trackId) {
    return ResponseEntity.ok(trackService.getTrack(trackId));
  }

  /**
   * A decomposer failure (model timeout, unparseable output) is upstream's problem, not the user's.
   */
  @ExceptionHandler(TrackDecompositionException.class)
  ResponseEntity<Void> handleDecompositionFailure(TrackDecompositionException e) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
  }

  @ExceptionHandler(NoSuchElementException.class)
  ResponseEntity<Void> handleNotFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }
}
