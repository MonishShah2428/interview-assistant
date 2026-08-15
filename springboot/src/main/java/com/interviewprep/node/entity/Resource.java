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
 * A learning option. Owned by a topic, written once at creation. Not a checklist item, not
 * required, not counted — four resources are four alternatives; {@code lane} is what makes that
 * legible. {@code resource_unique_url (topic_id, url)} stops "find more" from re-adding what's
 * already there — it's a DB-only guard (NULLs compare distinct in Postgres), not reimplemented
 * here.
 */
@Getter
@Entity
@Table(name = "resource")
public class Resource {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "topic_id", nullable = false, updatable = false)
  private Topic topic;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ResourceType type;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private ResourceLane lane;

  @Column(nullable = false, updatable = false)
  private String title;

  @Column(updatable = false)
  private String url;

  @Column(updatable = false)
  private String citation;

  @Column(name = "source_query", updatable = false)
  private String sourceQuery;

  @Column(nullable = false)
  private int position;

  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  Resource() {}

  public Resource(
      Topic topic,
      ResourceType type,
      ResourceLane lane,
      String title,
      String url,
      String citation,
      String sourceQuery,
      int position) {
    this.topic = topic;
    this.type = type;
    this.lane = lane;
    this.title = title;
    this.url = url;
    this.citation = citation;
    this.sourceQuery = sourceQuery;
    this.position = position;
    this.createdAt = OffsetDateTime.now();
  }
}
