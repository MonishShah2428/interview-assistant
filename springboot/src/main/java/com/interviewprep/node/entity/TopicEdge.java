package com.interviewprep.node.entity;

import com.interviewprep.node.composite.TopicEdgeId;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;

/**
 * The DAG edge table — one topic can hang under several parents, so this is not a plain join table.
 * The self-edge check (parent_topic_id &lt;&gt; child_topic_id) is enforced by Postgres, not
 * revalidated in Java.
 */
@Getter
@Entity
@Table(name = "topic_edge")
public class TopicEdge {

  @EmbeddedId private TopicEdgeId id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("parentTopicId")
  @JoinColumn(name = "parent_topic_id")
  private Topic parentTopic;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("childTopicId")
  @JoinColumn(name = "child_topic_id")
  private Topic childTopic;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  TopicEdge() {}

  public TopicEdge(Topic parent, Topic child) {
    this.id = new TopicEdgeId(parent.getId(), child.getId());
    this.parentTopic = parent;
    this.childTopic = child;
    this.createdAt = OffsetDateTime.now();
  }
}
