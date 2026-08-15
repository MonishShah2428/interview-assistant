package com.interviewprep.node.repository;

import com.interviewprep.node.entity.Concept;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConceptRepository extends JpaRepository<Concept, Long> {

  // Backed by concept_topic_idx. The tick pipeline's future extractor reads this vocabulary.
  List<Concept> findByTopicId(Long topicId);

  // Backed by concept_unique_in_topic, mirrors Topic's dedupe-arbiter pattern.
  Optional<Concept> findByTopicIdAndNormalizedLabel(Long topicId, String normalizedLabel);
}
