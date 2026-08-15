package com.interviewprep.tick.controller;

import com.interviewprep.tick.service.TickService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Async pipeline. Flip the tick, enqueue, return — never blocks on a fetch. */
@RestController
@RequestMapping("/resources")
class TickController {

  private final TickService tickService;

  TickController(TickService tickService) {
    this.tickService = tickService;
  }

  @PostMapping("/{resourceId}/tick")
  ResponseEntity<Void> tick(@PathVariable Long resourceId) {
    // TODO: flip ticked_at, set extraction_status=pending, enqueue extraction job, return
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
