package com.interviewprep.node.queue;

import com.interviewprep.node.entity.EnrichmentJobKind;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.EnrichmentJobRepository.ClaimedJob;
import com.interviewprep.node.service.EnrichmentService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls {@code enrichment_job} and dispatches claimed rows to {@link EnrichmentService}. Claiming
 * happens in a short transaction, same shape as every other claim in this codebase; each claimed
 * job is dispatched onto {@link #executor}, not run inline, so a slow enrichment never delays the
 * next poll tick from claiming other work.
 *
 * <p>This job-level retry (bounded, fixed backoff) is coarser than {@code EnrichmentService}'s own
 * internal retry (bounded, exponential backoff, only on {@code RetryableEnrichmentException}) — it
 * catches everything the inner retry didn't recover from, on the theory that some of those (e.g. a
 * transient DB blip during the claim/status-transition itself) are still worth one more pass at
 * job-queue timescale.
 *
 * <p>Gated behind {@code enrichment.polling.enabled} (defaults on) so tests that drive {@link
 * EnrichmentService} directly can turn this off — otherwise the background poll tick races those
 * tests for the same {@code enrichment_status} claim.
 */
@Component
@ConditionalOnProperty(prefix = "enrichment.polling", name = "enabled", matchIfMissing = true)
class EnrichmentJobPoller {

  private static final Logger log = LoggerFactory.getLogger(EnrichmentJobPoller.class);

  private static final int BATCH_SIZE = 5;
  private static final int MAX_ATTEMPTS = 5;
  private static final Duration RETRY_BACKOFF = Duration.ofSeconds(60);

  private final EnrichmentJobRepository jobRepository;
  private final EnrichmentService enrichmentService;
  private final AsyncTaskExecutor executor;
  private final TransactionTemplate requiredTemplate;

  EnrichmentJobPoller(
      EnrichmentJobRepository jobRepository,
      EnrichmentService enrichmentService,
      @Qualifier("enrichmentJobExecutor") AsyncTaskExecutor executor,
      PlatformTransactionManager transactionManager) {
    this.jobRepository = jobRepository;
    this.enrichmentService = enrichmentService;
    this.executor = executor;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelay = 2000)
  void poll() {
    List<ClaimedJob> claimed =
        requiredTemplate.execute(status -> jobRepository.claimBatch(BATCH_SIZE, MAX_ATTEMPTS));
    for (ClaimedJob job : claimed) {
      CompletableFuture.runAsync(() -> process(job), executor);
    }
  }

  private void process(ClaimedJob job) {
    try {
      switch (EnrichmentJobKind.valueOf(job.getKind())) {
        case initial -> enrichmentService.enrichTopic(job.getTopicId());
        case resource_refresh -> enrichmentService.refreshResources(job.getTopicId());
      }
      requiredTemplate.executeWithoutResult(status -> jobRepository.deleteById(job.getId()));
    } catch (RuntimeException e) {
      // The only record of why a job failed; markFailed below keeps no reason.
      log.warn(
          "enrichment job {} ({}, topic {}) failed; will retry after {}",
          job.getId(),
          job.getKind(),
          job.getTopicId(),
          RETRY_BACKOFF,
          e);
      requiredTemplate.executeWithoutResult(
          status ->
              jobRepository.markFailed(
                  job.getId(), OffsetDateTime.now().plus(RETRY_BACKOFF), OffsetDateTime.now()));
    }
  }
}
