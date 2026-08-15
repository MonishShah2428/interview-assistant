package com.interviewprep.review.controller;

import com.interviewprep.review.service.ReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sync but long. Triggered as a chat command ("quiz me on caching"), not a button. */
@RestController
@RequestMapping("/review")
class ReviewController {

  private final ReviewService reviewService;

  ReviewController(ReviewService reviewService) {
    this.reviewService = reviewService;
  }

  @PostMapping
  ResponseEntity<Void> review(@RequestBody ReviewRequest request) {
    // TODO: parse scope from command text, run quiz or mock-interview mode over covered concepts
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
  }
}
