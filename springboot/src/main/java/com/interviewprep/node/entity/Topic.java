package com.interviewprep.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * The node. The only entity with identity semantics that matter: normalized_label unique per track
 * is what makes reuse possible. Also the only entity carrying state — expansion_status and
 * enrichment_status fail independently, so they're tracked separately.
 */
@Getter
@Entity
@Table(name = "topic")
public class Topic {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "track_id", nullable = false, updatable = false)
  private Track track;

  @Column(nullable = false)
  private String label;

  /**
   * The dedupe arbiter. Two concurrent expansions proposing the same child race on {@code
   * topic_unique_in_track (track_id, normalized_label)}; the loser catches the constraint violation
   * and re-reads via {@link
   * com.interviewprep.node.repository.TopicRepository#findByTrackIdAndNormalizedLabel}.
   */
  @Column(name = "normalized_label", nullable = false)
  private String normalizedLabel;

  @Column(name = "is_core", nullable = false)
  private boolean isCore;

  @Column(name = "is_root", nullable = false)
  private boolean isRoot;

  /** Only meaningful for roots — preserves the decomposer's foundational-first output order. */
  @Column(name = "position", nullable = false)
  private int position;

  @Enumerated(EnumType.STRING)
  @Column(name = "expansion_status", nullable = false)
  private ExpansionStatus expansionStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "enrichment_status", nullable = false)
  private EnrichmentStatus enrichmentStatus;

  @Column(name = "searched_at")
  private OffsetDateTime searchedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  Topic() {}

  public Topic(Track track, String label, String normalizedLabel, boolean isCore, boolean isRoot) {
    this(track, label, normalizedLabel, isCore, isRoot, 0);
  }

  public Topic(
      Track track,
      String label,
      String normalizedLabel,
      boolean isCore,
      boolean isRoot,
      int position) {
    this.track = track;
    this.label = label;
    this.normalizedLabel = normalizedLabel;
    this.isCore = isCore;
    this.isRoot = isRoot;
    this.position = position;
    this.expansionStatus = ExpansionStatus.not_expanded;
    this.enrichmentStatus = EnrichmentStatus.pending;
    this.createdAt = OffsetDateTime.now();
  }
}
