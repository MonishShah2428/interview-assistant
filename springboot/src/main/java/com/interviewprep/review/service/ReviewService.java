package com.interviewprep.review.service;

import org.springframework.stereotype.Service;

/**
 * Questions attach to concepts, not topics or resources. Interview and grading are two separate LLM
 * passes that do not share context — grading must never receive the rubric during pass 1.
 */
@Service
public class ReviewService {
  // TODO: quiz mode -> direct Q&A over covered concepts
  // TODO: mock interview mode, pass 1 -> conversational, no rubric in context
  // TODO: grading, pass 2 -> fresh call over the finished transcript, produces self-rating prompt
}
