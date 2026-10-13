package com.interviewprep.node.repository;

import com.interviewprep.node.entity.EnrichmentJob;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EnrichmentJobRepository extends JpaRepository<EnrichmentJob, Long> {

  interface ClaimedJob {
    Long getId();

    Long getTopicId();

    String getKind();
  }

  /**
   * Atomic claim-and-read in one round trip: pick rows with {@code FOR UPDATE SKIP LOCKED} in a
   * {@code MATERIALIZED} CTE, then {@code UPDATE ... FROM picked RETURNING ...}. Not {@code WHERE
   * id IN (SELECT ... LIMIT n FOR UPDATE SKIP LOCKED)}: Postgres may evaluate that subquery more
   * than once, claiming more than {@code n} rows or none. Not {@code @Modifying} — that forces
   * Hibernate's {@code executeUpdate()} path, which expects an int, but this statement returns rows
   * via {@code RETURNING}, so it needs the normal result-list path a {@code SELECT} would use.
   *
   * <p>Uses {@code clock_timestamp()}, not {@code now()} — Postgres freezes {@code now()} at
   * transaction start, so a long-lived transaction would compare {@code run_after} against a stale
   * timestamp. {@code clock_timestamp()} re-evaluates on every call, which is what a polling claim
   * actually needs.
   */
  @Query(
      value =
          """
          WITH picked AS MATERIALIZED (
            SELECT id FROM enrichment_job
            WHERE status IN ('pending', 'failed')
              AND attempts < :maxAttempts
              AND run_after <= clock_timestamp()
            ORDER BY run_after
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
          )
          UPDATE enrichment_job j
          SET status = 'processing', updated_at = clock_timestamp()
          FROM picked
          WHERE j.id = picked.id
          RETURNING j.id, j.topic_id, j.kind
          """,
      nativeQuery = true)
  List<ClaimedJob> claimBatch(
      @Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);

  /**
   * Returns jobs a dead process left {@code processing} to the queue as {@code failed} and due now.
   * Only safe at startup of the single backend instance, when nothing can genuinely be running.
   */
  @Modifying
  @Query(
      value =
          "UPDATE enrichment_job SET status = 'failed', run_after = clock_timestamp(),"
              + " updated_at = clock_timestamp() WHERE status = 'processing'",
      nativeQuery = true)
  int releaseProcessing();

  /** Whether {@code topicId} has a job that is processing or still has retries left. */
  @Query(
      "SELECT COUNT(j) > 0 FROM EnrichmentJob j WHERE j.topicId = :topicId AND (j.status ="
          + " com.interviewprep.node.entity.EnrichmentJobStatus.processing OR j.attempts <"
          + " :maxAttempts)")
  boolean hasLiveJob(@Param("topicId") Long topicId, @Param("maxAttempts") int maxAttempts);

  @Modifying
  @Query(
      "UPDATE EnrichmentJob j SET j.status = com.interviewprep.node.entity.EnrichmentJobStatus.failed, "
          + "j.attempts = j.attempts + 1, j.runAfter = :runAfter, j.updatedAt = :updatedAt "
          + "WHERE j.id = :id")
  int markFailed(
      @Param("id") Long id,
      @Param("runAfter") OffsetDateTime runAfter,
      @Param("updatedAt") OffsetDateTime updatedAt);
}
