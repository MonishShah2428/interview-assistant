package com.interviewprep.node.repository;

import com.interviewprep.node.entity.Track;
import org.springframework.data.jpa.repository.JpaRepository;

// track_user_idx exists for a future "list my tracks" endpoint, not needed by this pipeline yet.
public interface TrackRepository extends JpaRepository<Track, Long> {}
