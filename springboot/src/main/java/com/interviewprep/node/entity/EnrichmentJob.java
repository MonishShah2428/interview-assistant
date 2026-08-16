package com.interviewprep.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * A queued call to {@code EnrichmentService.enrichTopic}. Deliberately holds a plain {@code
 * topicId} rather than a {@code @ManyToOne Topic} like {@link Resource}/{@link Concept} do —
 * nothing ever navigates from a job to the topic's other fields, the poller only needs the raw id.
 * The FK is enforced at the DB level only.
 */
@Getter
@Entity
@Table(name = "enrichment_job")
public class EnrichmentJob {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "topic_id", nullable = false, updatable = false)
  private Long topicId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private EnrichmentJobStatus status;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "run_after", nullable = false)
  private OffsetDateTime runAfter;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  EnrichmentJob() {}

  public EnrichmentJob(Long topicId) {
    this.topicId = topicId;
    this.status = EnrichmentJobStatus.pending;
    this.attempts = 0;
    OffsetDateTime now = OffsetDateTime.now();
    this.runAfter = now;
    this.createdAt = now;
    this.updatedAt = now;
  }
}
