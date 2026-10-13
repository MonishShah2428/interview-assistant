package com.interviewprep.node.repository;

import com.interviewprep.node.entity.Track;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackRepository extends JpaRepository<Track, Long> {

  /** Backed by {@code track_user_idx}. */
  List<Track> findByUserIdOrderByCreatedAtDesc(Long userId);
}
