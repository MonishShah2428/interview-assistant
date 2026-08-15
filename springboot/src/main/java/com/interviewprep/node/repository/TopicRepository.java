package com.interviewprep.node.repository;

import com.interviewprep.node.entity.Topic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {

  // The dedupe-arbiter re-read: backed by topic_unique_in_track.
  Optional<Topic> findByTrackIdAndNormalizedLabel(Long trackId, String normalizedLabel);

  // Backed by the partial index topic_roots_idx.
  List<Topic> findByTrackIdAndIsRootTrue(Long trackId);

  // Backed by topic_track_idx.
  List<Topic> findByTrackId(Long trackId);
}
