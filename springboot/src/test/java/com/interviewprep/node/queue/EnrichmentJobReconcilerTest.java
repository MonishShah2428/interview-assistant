package com.interviewprep.node.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.entity.EnrichmentJobKind;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.Track;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.repository.TrackRepository;
import com.interviewprep.node.service.ExpansionService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A topic left {@code pending} with no live job is stranded: the poller only works from job rows.
 * Polling is disabled so nothing claims the rows these tests insert; the reconciler bean is gated
 * on the same property, so it is constructed by hand.
 */
@SpringBootTest
@TestPropertySource(properties = "enrichment.polling.enabled=false")
class EnrichmentJobReconcilerTest {

  @Autowired private TrackRepository trackRepository;
  @Autowired private TopicRepository topicRepository;
  @Autowired private EnrichmentJobRepository jobRepository;
  @Autowired private ExpansionService expansionService;
  @Autowired private PlatformTransactionManager transactionManager;

  private EnrichmentJobReconciler reconciler;
  private Track track;
  private Topic stranded;
  private Topic exhausted;
  private Topic queued;
  private Topic root;

  @BeforeEach
  void seed() {
    reconciler = new EnrichmentJobReconciler(topicRepository, jobRepository, transactionManager);
    // The database is shared with real data that may already hold stranded topics; remember them
    // so reconcile() can be checked against this test's topics only, and their jobs undone after.
    strandedBeforeTest =
        topicRepository.findPendingTopicIdsWithoutLiveJob(EnrichmentJob.MAX_ATTEMPTS);

    track = trackRepository.saveAndFlush(new Track(1L, "reconciler test", "mid", null));
    root = topicRepository.saveAndFlush(new Topic(track, "Root", "root", true, true));
    stranded = topicRepository.saveAndFlush(new Topic(track, "Stranded", "stranded", true, false));
    exhausted =
        topicRepository.saveAndFlush(new Topic(track, "Exhausted", "exhausted", true, false));
    queued = topicRepository.saveAndFlush(new Topic(track, "Queued", "queued", true, false));

    EnrichmentJob deadJob = jobRepository.saveAndFlush(new EnrichmentJob(exhausted.getId()));
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            s -> {
              for (int i = 0; i < EnrichmentJob.MAX_ATTEMPTS; i++) {
                jobRepository.markFailed(
                    deadJob.getId(), OffsetDateTime.now(), OffsetDateTime.now());
              }
            });
    jobRepository.saveAndFlush(new EnrichmentJob(queued.getId()));
  }

  private List<Long> strandedBeforeTest = List.of();

  @AfterEach
  void cleanUp() {
    List<Long> ours = List.of(root.getId(), stranded.getId(), exhausted.getId(), queued.getId());
    jobRepository.deleteAll(jobsFor(ours));
    // Undo jobs reconcile() queued for pre-existing stranded topics; the app's own startup check
    // will queue them for real.
    jobRepository.deleteAll(
        jobsFor(strandedBeforeTest).stream()
            .filter(j -> j.getAttempts() == 0 && j.getKind() == EnrichmentJobKind.initial)
            .toList());
    topicRepository.deleteAllById(ours);
    trackRepository.deleteById(track.getId());
  }

  @Test
  void startupQueuesOnlyTopicsThatNothingWillPickUp() {
    int queuedCount = reconciler.reconcile();

    // stranded + exhausted, plus whatever was already stranded; never `queued` or the root
    assertThat(queuedCount).isEqualTo(strandedBeforeTest.size() + 2);
    assertThat(liveKinds(stranded)).containsExactly(EnrichmentJobKind.initial);
    assertThat(liveKinds(exhausted)).containsExactly(EnrichmentJobKind.initial);
    assertThat(jobsFor(List.of(queued.getId()))).hasSize(1);
    assertThat(jobsFor(List.of(root.getId()))).isEmpty();

    assertThat(reconciler.reconcile()).isZero(); // idempotent
  }

  @Test
  void findMoreOnAPendingTopicQueuesItsFirstSearchOnlyWhenMissing() {
    expansionService.requestResourceRefresh(track.getId(), stranded.getId());
    expansionService.requestResourceRefresh(track.getId(), queued.getId());

    assertThat(liveKinds(stranded)).containsExactly(EnrichmentJobKind.initial);
    assertThat(jobsFor(List.of(queued.getId()))).hasSize(1); // no duplicate
  }

  private List<EnrichmentJob> jobsFor(List<Long> topicIds) {
    return jobRepository.findAll().stream().filter(j -> topicIds.contains(j.getTopicId())).toList();
  }

  private List<EnrichmentJobKind> liveKinds(Topic topic) {
    return jobsFor(List.of(topic.getId())).stream()
        .filter(j -> j.getAttempts() < EnrichmentJob.MAX_ATTEMPTS)
        .map(EnrichmentJob::getKind)
        .toList();
  }
}
