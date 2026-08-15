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
 * An idea the topic covers, scoped to the track's level. Owned by a topic, written once at
 * creation. Nothing in the node pipeline reads this yet — it exists so the tick pipeline's future
 * extractor has a fixed vocabulary to classify against, avoiding a backfill of every existing
 * topic. {@code concept_unique_in_topic (topic_id, normalized_label)} is a DB-only guard, not
 * reimplemented here.
 */
@Getter
@Entity
@Table(name = "concept")
public class Concept {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "topic_id", nullable = false, updatable = false)
  private Topic topic;

  @Column(nullable = false, updatable = false)
  private String label;

  @Column(name = "normalized_label", nullable = false, updatable = false)
  private String normalizedLabel;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ConceptOrigin origin;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  Concept() {}

  public Concept(Topic topic, String label, String normalizedLabel, ConceptOrigin origin) {
    this.topic = topic;
    this.label = label;
    this.normalizedLabel = normalizedLabel;
    this.origin = origin;
    this.createdAt = OffsetDateTime.now();
  }
}
