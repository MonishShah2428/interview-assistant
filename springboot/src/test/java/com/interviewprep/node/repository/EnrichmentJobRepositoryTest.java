package com.interviewprep.node.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.entity.EnrichmentJobStatus;
import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.Track;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class EnrichmentJobRepositoryTest {

  @Autowired private EnrichmentJobRepository enrichmentJobRepository;
  @Autowired private TrackRepository trackRepository;
  @Autowired private TopicRepository topicRepository;
  @Autowired private EntityManager entityManager;

  /**
   * Work a killed backend left marked as running must become claimable again. Runs inside this
   * class's rolled-back transaction, so the shared database's real rows are restored afterwards.
   */
  @Test
  void releasingInterruptedWorkMakesItClaimableAgain() {
    Track track = trackRepository.saveAndFlush(new Track(1L, "goal", "mid", null));
    Topic topic = topicRepository.saveAndFlush(new Topic(track, "Topic C", "topic c", true, false));
    EnrichmentJob job = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topic.getId()));
    entityManager
        .createNativeQuery("UPDATE enrichment_job SET status = 'processing' WHERE id = :id")
        .setParameter("id", job.getId())
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE topic SET enrichment_status = 'enriching', expansion_status = 'expanding'"
                + " WHERE id = :id")
        .setParameter("id", topic.getId())
        .executeUpdate();

    assertThat(enrichmentJobRepository.releaseProcessing()).isPositive();
    assertThat(topicRepository.releaseEnriching()).isPositive();
    assertThat(topicRepository.releaseExpanding()).isPositive();
    entityManager.clear();

    assertThat(enrichmentJobRepository.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.failed);
    Topic released = topicRepository.findById(topic.getId()).orElseThrow();
    assertThat(released.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.failed);
    assertThat(released.getExpansionStatus()).isEqualTo(ExpansionStatus.failed);
  }

  @Test
  void claimBatchClaimsOnlyRequestedCountAndLeavesTheRestPending() {
    // claimBatch looks across the whole table, and this database is shared with real data. Push
    // every existing job out of reach first so only this test's two compete; the test transaction
    // rolls this back.
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET run_after = clock_timestamp() + interval '1 day'")
        .executeUpdate();

    Track track = trackRepository.saveAndFlush(new Track(1L, "goal", "mid", null));
    Topic topicA = topicRepository.saveAndFlush(new Topic(track, "Topic A", "topic a", true, true));
    Topic topicB = topicRepository.saveAndFlush(new Topic(track, "Topic B", "topic b", true, true));

    EnrichmentJob jobA = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicA.getId()));
    EnrichmentJob jobB = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicB.getId()));

    // run_after comes from the JVM clock but claimBatch compares it with Postgres's. Docker
    // Desktop's VM clock drifts against the host (measured -589..+460 ms), so a job inserted a
    // moment ago can still look scheduled for the future. Restate both on the database clock.
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET created_at = clock_timestamp() - interval '10 seconds',"
                + " run_after = clock_timestamp() - interval '1 second' WHERE id = :a")
        .setParameter("a", jobA.getId())
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET created_at = clock_timestamp() - interval '5 seconds',"
                + " run_after = clock_timestamp() - interval '1 second' WHERE id = :b")
        .setParameter("b", jobB.getId())
        .executeUpdate();

    List<EnrichmentJobRepository.ClaimedJob> claimed = enrichmentJobRepository.claimBatch(1, 5);
    // claimBatch is a native query, so it bypasses the persistence context entirely — jobA/jobB
    // are still cached from saveAndFlush above and would otherwise mask the update with stale
    // in-memory state.
    entityManager.clear();

    assertThat(claimed).hasSize(1);
    assertThat(claimed.get(0).getId()).isEqualTo(jobB.getId());

    assertThat(enrichmentJobRepository.findById(jobB.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.processing);
    assertThat(enrichmentJobRepository.findById(jobA.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.pending);
  }

  @Test
  void claimBatchClaimsMostRecentlyRequestedFirstAndLeavesProcessingJobUntouched() {
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET run_after = clock_timestamp() + interval '1 day'")
        .executeUpdate();

    Track track = trackRepository.saveAndFlush(new Track(1L, "goal", "mid", null));
    Topic topicA =
        topicRepository.saveAndFlush(new Topic(track, "Topic A", "topic a", true, false));
    Topic topicB =
        topicRepository.saveAndFlush(new Topic(track, "Topic B", "topic b", true, false));
    Topic topicC =
        topicRepository.saveAndFlush(new Topic(track, "Topic C", "topic c", true, false));

    EnrichmentJob jobA = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicA.getId()));
    EnrichmentJob jobB = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicB.getId()));
    EnrichmentJob jobC = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicC.getId()));

    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET status = 'processing',"
                + " created_at = clock_timestamp() - interval '30 seconds',"
                + " run_after = clock_timestamp() - interval '30 seconds' WHERE id = :a")
        .setParameter("a", jobA.getId())
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET created_at = clock_timestamp() - interval '20 seconds',"
                + " run_after = clock_timestamp() - interval '1 second' WHERE id = :b")
        .setParameter("b", jobB.getId())
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET created_at = clock_timestamp() - interval '10 seconds',"
                + " run_after = clock_timestamp() - interval '1 second' WHERE id = :c")
        .setParameter("c", jobC.getId())
        .executeUpdate();

    List<EnrichmentJobRepository.ClaimedJob> first = enrichmentJobRepository.claimBatch(1, 5);
    entityManager.clear();

    assertThat(first)
        .extracting(EnrichmentJobRepository.ClaimedJob::getId)
        .containsExactly(jobC.getId());
    assertThat(enrichmentJobRepository.findById(jobA.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.processing);
    assertThat(enrichmentJobRepository.findById(jobC.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.processing);
    assertThat(enrichmentJobRepository.findById(jobB.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.pending);

    List<EnrichmentJobRepository.ClaimedJob> second = enrichmentJobRepository.claimBatch(1, 5);
    entityManager.clear();

    assertThat(second)
        .extracting(EnrichmentJobRepository.ClaimedJob::getId)
        .containsExactly(jobB.getId());
    assertThat(enrichmentJobRepository.findById(jobB.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.processing);
  }

  @Test
  void newerRequestIsClaimedAheadOfOlderDueRetry() {
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET run_after = clock_timestamp() + interval '1 day'")
        .executeUpdate();

    Track track = trackRepository.saveAndFlush(new Track(1L, "goal", "mid", null));
    Topic oldTopic =
        topicRepository.saveAndFlush(new Topic(track, "Old Topic", "old topic", true, false));
    Topic freshTopic =
        topicRepository.saveAndFlush(new Topic(track, "Fresh Topic", "fresh topic", true, false));

    EnrichmentJob oldRetry =
        enrichmentJobRepository.saveAndFlush(new EnrichmentJob(oldTopic.getId()));
    EnrichmentJob freshJob =
        enrichmentJobRepository.saveAndFlush(new EnrichmentJob(freshTopic.getId()));

    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET status = 'failed', attempts = 1,"
                + " created_at = clock_timestamp() - interval '2 minutes',"
                + " run_after = clock_timestamp() - interval '1 second' WHERE id = :oldId")
        .setParameter("oldId", oldRetry.getId())
        .executeUpdate();
    entityManager
        .createNativeQuery(
            "UPDATE enrichment_job SET status = 'pending', attempts = 0,"
                + " created_at = clock_timestamp() - interval '30 seconds',"
                + " run_after = clock_timestamp() - interval '30 seconds' WHERE id = :freshId")
        .setParameter("freshId", freshJob.getId())
        .executeUpdate();

    List<EnrichmentJobRepository.ClaimedJob> claimed = enrichmentJobRepository.claimBatch(1, 5);
    entityManager.clear();

    assertThat(claimed)
        .extracting(EnrichmentJobRepository.ClaimedJob::getId)
        .containsExactly(freshJob.getId());
    assertThat(enrichmentJobRepository.findById(freshJob.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.processing);
    assertThat(enrichmentJobRepository.findById(oldRetry.getId()).orElseThrow().getStatus())
        .isEqualTo(EnrichmentJobStatus.failed);
  }
}
