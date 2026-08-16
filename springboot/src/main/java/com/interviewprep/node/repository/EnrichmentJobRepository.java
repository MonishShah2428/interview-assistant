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
  }

  /**
   * Atomic claim-and-read in one round trip: {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE
   * SKIP LOCKED) RETURNING ...}. Not {@code @Modifying} — that forces Hibernate's {@code
   * executeUpdate()} path, which expects an int, but this statement returns rows via {@code
   * RETURNING}, so it needs the normal result-list path a {@code SELECT} would use.
   *
   * <p>Uses {@code clock_timestamp()}, not {@code now()} — Postgres freezes {@code now()} at
   * transaction start, so a long-lived transaction would compare {@code run_after} against a stale
   * timestamp. {@code clock_timestamp()} re-evaluates on every call, which is what a polling claim
   * actually needs.
   */
  @Query(
      value =
          """
          UPDATE enrichment_job
          SET status = 'processing', updated_at = clock_timestamp()
          WHERE id IN (
            SELECT id FROM enrichment_job
            WHERE status IN ('pending', 'failed')
              AND attempts < :maxAttempts
              AND run_after <= clock_timestamp()
            ORDER BY run_after
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
          )
          RETURNING id, topic_id
          """,
      nativeQuery = true)
  List<ClaimedJob> claimBatch(
      @Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);

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
