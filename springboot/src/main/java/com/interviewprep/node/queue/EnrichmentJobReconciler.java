package com.interviewprep.node.queue;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.TopicRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * On startup, recovers work a previous process left unfinished. First it releases anything still
 * marked as running (jobs {@code processing}, topics {@code enriching} or {@code expanding}): this
 * is a single backend instance, so at startup none of it can genuinely still be running, and left
 * alone it would stay that way forever. Then it gives every stranded topic a job again: a non-root
 * topic left {@code pending} with no live {@code enrichment_job} would otherwise sit "queued"
 * forever, because the poller only works from job rows. Gated like {@link EnrichmentJobPoller},
 * since a job nobody polls is pointless and tests that disable polling also expect no rows to
 * appear underneath them.
 */
@Component
@ConditionalOnProperty(prefix = "enrichment.polling", name = "enabled", matchIfMissing = true)
public class EnrichmentJobReconciler {

  private static final Logger log = LoggerFactory.getLogger(EnrichmentJobReconciler.class);

  private final TopicRepository topicRepository;
  private final EnrichmentJobRepository jobRepository;
  private final TransactionTemplate requiredTemplate;

  public EnrichmentJobReconciler(
      TopicRepository topicRepository,
      EnrichmentJobRepository jobRepository,
      PlatformTransactionManager transactionManager) {
    this.topicRepository = topicRepository;
    this.jobRepository = jobRepository;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
  }

  @EventListener(ApplicationReadyEvent.class)
  void onStartup() {
    releaseInterruptedWork();
    int queued = reconcile();
    if (queued > 0) {
      log.info("queued enrichment for {} stranded pending topic(s)", queued);
    }
  }

  /** Releases work marked running by a process that no longer exists; logs what it found. */
  public void releaseInterruptedWork() {
    requiredTemplate.executeWithoutResult(
        status -> {
          int jobs = jobRepository.releaseProcessing();
          int enriching = topicRepository.releaseEnriching();
          int expanding = topicRepository.releaseExpanding();
          if (jobs + enriching + expanding > 0) {
            log.info(
                "released work interrupted by a restart: {} job(s), {} enriching topic(s),"
                    + " {} expanding topic(s)",
                jobs,
                enriching,
                expanding);
          }
        });
  }

  /** Inserts an {@code initial} job for each stranded topic; returns how many it queued. */
  public int reconcile() {
    return requiredTemplate.execute(
        status -> {
          List<Long> stranded =
              topicRepository.findPendingTopicIdsWithoutLiveJob(EnrichmentJob.MAX_ATTEMPTS);
          jobRepository.saveAll(stranded.stream().map(EnrichmentJob::new).toList());
          return stranded.size();
        });
  }
}
