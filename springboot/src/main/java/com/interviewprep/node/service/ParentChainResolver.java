package com.interviewprep.node.service;

import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.TopicEdge;
import com.interviewprep.node.repository.TopicEdgeRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Walks a topic's DAG upward to build a root-to-parent chain, shared by {@link ExpansionService}
 * (sibling/duplicate context) and {@link EnrichmentService} (concept-generation scope, e.g.
 * "Sharding" under Databases vs. under System Design).
 */
@Component
class ParentChainResolver {

  private final TopicEdgeRepository topicEdgeRepository;

  ParentChainResolver(TopicEdgeRepository topicEdgeRepository) {
    this.topicEdgeRepository = topicEdgeRepository;
  }

  String resolve(Long topicId) {
    List<String> chain = new ArrayList<>();
    Set<Long> visited = new HashSet<>();
    Long currentId = topicId;
    while (true) {
      List<TopicEdge> parentEdges = topicEdgeRepository.findByIdChildTopicId(currentId);
      if (parentEdges.isEmpty()) {
        break;
      }
      // A topic can have multiple parents in the DAG; arbitrarily pick the first for prompt
      // context. Deliberate simplification, not a correctness requirement.
      Topic parent = parentEdges.get(0).getParentTopic();
      if (!visited.add(parent.getId())) {
        break; // safety valve against an accidental cycle
      }
      chain.add(0, parent.getLabel());
      currentId = parent.getId();
    }
    return chain.isEmpty() ? "(none — this is a root topic)" : String.join(" > ", chain);
  }
}
