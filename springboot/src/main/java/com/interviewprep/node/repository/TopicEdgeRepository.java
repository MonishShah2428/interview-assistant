package com.interviewprep.node.repository;

import com.interviewprep.node.entity.TopicEdge;
import com.interviewprep.node.entity.TopicEdgeId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicEdgeRepository extends JpaRepository<TopicEdge, TopicEdgeId> {

  // Children of a node.
  List<TopicEdge> findByIdParentTopicId(Long parentTopicId);

  // Every edge for a whole set of parents in one query — a full tree read needs all of a track's
  // edges at once, not a query per node.
  List<TopicEdge> findByIdParentTopicIdIn(Collection<Long> parentTopicIds);

  // Parents of a node — a topic can hang under several. Backed by topic_edge_child_idx.
  List<TopicEdge> findByIdChildTopicId(Long childTopicId);
}
