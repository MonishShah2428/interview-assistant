package com.interviewprep.tick.service;

import org.springframework.stereotype.Service;

/**
 * Ticking must never block on a fetch. This service only flips state and enqueues; the worker
 * (Postgres-backed job table, SELECT ... FOR UPDATE SKIP LOCKED) does the fetch and extraction.
 */
@Service
public class TickService {
  // TODO: mark resource ticked + extraction_status=pending, insert a job row in the same
  // transaction
  // TODO: worker loop: claim pending jobs, fetch + extract coverage, retry with dead-letter path
  // TODO: fallback classification for unfetchable resources (books) against the topic's concept
  // list
}
