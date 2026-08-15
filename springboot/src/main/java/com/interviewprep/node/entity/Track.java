package com.interviewprep.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * The prep goal. Owns {@code level}, which every LLM call downstream reads. Created once per goal,
 * never touched again by this pipeline. Its real job is scoping: every query downstream filters on
 * track_id, because the cache is per-user and topics from one track must never match against
 * another's.
 */
@Getter
@Entity
@Table(name = "track")
public class Track {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private Long userId;

  @Column(nullable = false, updatable = false)
  private String goal;

  @Column(nullable = false, updatable = false)
  private String level;

  @Column(name = "source_jd", updatable = false)
  private String sourceJd;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  Track() {}

  public Track(Long userId, String goal, String level, String sourceJd) {
    this.userId = userId;
    this.goal = goal;
    this.level = level;
    this.sourceJd = sourceJd;
    this.createdAt = OffsetDateTime.now();
  }
}
