package com.interviewprep.node.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Composite key for {@link TopicEdge}: one DAG edge per (parent, child) pair. */
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class TopicEdgeId implements Serializable {

  @Column(name = "parent_topic_id")
  private Long parentTopicId;

  @Column(name = "child_topic_id")
  private Long childTopicId;
}
