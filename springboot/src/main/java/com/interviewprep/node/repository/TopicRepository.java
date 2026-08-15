package com.interviewprep.node.repository;

import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.Topic;
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

  // Backed by the partial index topic_roots_idx.
  List<Topic> findByTrackIdAndIsRootTrue(Long trackId);

  // Backed by topic_track_idx.
  List<Topic> findByTrackId(Long trackId);

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
}
