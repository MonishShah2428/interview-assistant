package com.interviewprep.node.repository;

import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.Topic;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TopicRepository extends JpaRepository<Topic, Long> {

  // The dedupe-arbiter re-read: backed by topic_unique_in_track.
  Optional<Topic> findByTrackIdAndNormalizedLabel(Long trackId, String normalizedLabel);

  // Backed by the partial index topic_roots_idx; position preserves the decomposer's order.
  List<Topic> findByTrackIdAndIsRootTrueOrderByPosition(Long trackId);

  // Backed by topic_track_idx.
  List<Topic> findByTrackId(Long trackId);

  // trackId-scoping guard: does this topic actually belong to the track the caller claims it does.
  boolean existsByIdAndTrackId(Long id, Long trackId);

  /**
   * Marks topics a dead process left mid-enrichment as {@code failed}, which {@code enrichTopic}
   * can claim again (it resumes, keeping whatever was already saved). Startup only, like {@link
   * EnrichmentJobRepository#releaseProcessing}.
   */
  @Modifying
  @Query(
      value = "UPDATE topic SET enrichment_status = 'failed' WHERE enrichment_status = 'enriching'",
      nativeQuery = true)
  int releaseEnriching();

  /** Marks topics a dead process left mid-expansion as {@code failed}, so "Try again" works. */
  @Modifying
  @Query(
      value = "UPDATE topic SET expansion_status = 'failed' WHERE expansion_status = 'expanding'",
      nativeQuery = true)
  int releaseExpanding();

  /**
   * Non-root topics still waiting for enrichment that nothing will ever pick up: no job, or only
   * jobs that ran out of retries. Normal creation never leaves one (the job is inserted in the same
   * transaction as the topic); manual cleanup can.
   */
  @Query(
      value =
          """
          SELECT t.id FROM topic t
          WHERE t.is_root = false
            AND t.enrichment_status = 'pending'
            AND NOT EXISTS (
              SELECT 1 FROM enrichment_job j
              WHERE j.topic_id = t.id
                AND (j.status = 'processing' OR j.attempts < :maxAttempts))
          ORDER BY t.id
          """,
      nativeQuery = true)
  List<Long> findPendingTopicIdsWithoutLiveJob(@Param("maxAttempts") int maxAttempts);

  /**
   * Atomic claim: flips the topic to {@code expanding} only if currently in a claimable state.
   * Returns 1 if this call won the claim, 0 if the topic doesn't exist or was already
   * expanded/expanding. Entities here are {@code @Getter}-only with no setters, so status
   * transitions go through bulk updates like this rather than load-then-save.
   */
  @Modifying
  @Query(
      "UPDATE Topic t SET t.expansionStatus = :expanding "
          + "WHERE t.id = :topicId AND t.expansionStatus IN :claimableStatuses")
  int claimForExpansion(
      @Param("topicId") Long topicId,
      @Param("expanding") ExpansionStatus expanding,
      @Param("claimableStatuses") Collection<ExpansionStatus> claimableStatuses);

  /** Unconditional expansion-status transition, used once the caller already owns the topic. */
  @Modifying
  @Query("UPDATE Topic t SET t.expansionStatus = :status WHERE t.id = :topicId")
  int updateExpansionStatus(
      @Param("topicId") Long topicId, @Param("status") ExpansionStatus status);

  /**
   * Atomic claim: flips the topic to {@code enriching} only if currently in a claimable state. Same
   * shape as {@link #claimForExpansion} — enrichment_status fails independently of
   * expansion_status, so it gets its own claim.
   */
  @Modifying
  @Query(
      "UPDATE Topic t SET t.enrichmentStatus = :enriching "
          + "WHERE t.id = :topicId AND t.enrichmentStatus IN :claimableStatuses")
  int claimForEnrichment(
      @Param("topicId") Long topicId,
      @Param("enriching") EnrichmentStatus enriching,
      @Param("claimableStatuses") Collection<EnrichmentStatus> claimableStatuses);

  /** Unconditional enrichment-status transition, used once the caller already owns the topic. */
  @Modifying
  @Query("UPDATE Topic t SET t.enrichmentStatus = :status WHERE t.id = :topicId")
  int updateEnrichmentStatus(
      @Param("topicId") Long topicId, @Param("status") EnrichmentStatus status);

  /**
   * Stamped once, on the resource producer's first successful run — skipped on a resumed retry that
   * finds resources already present.
   */
  @Modifying
  @Query("UPDATE Topic t SET t.searchedAt = :searchedAt WHERE t.id = :topicId")
  int updateSearchedAt(
      @Param("topicId") Long topicId, @Param("searchedAt") OffsetDateTime searchedAt);
}
