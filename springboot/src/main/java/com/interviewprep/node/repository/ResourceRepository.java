package com.interviewprep.node.repository;

import com.interviewprep.node.entity.Resource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceRepository extends JpaRepository<Resource, Long> {

  // Backed by resource_topic_idx. Stands in for topic.getResources().
  List<Resource> findByTopicId(Long topicId);

  // The "find more" dedupe check, backed by resource_unique_url.
  Optional<Resource> findByTopicIdAndUrl(Long topicId, String url);
}
