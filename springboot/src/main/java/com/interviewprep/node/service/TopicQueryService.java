package com.interviewprep.node.service;

import com.interviewprep.node.entity.Concept;
import com.interviewprep.node.entity.Resource;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.repository.ConceptRepository;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.service.dto.ConceptView;
import com.interviewprep.node.service.dto.ResourceDetailView;
import com.interviewprep.node.service.dto.TopicDetailView;
import java.util.Comparator;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Read-only views of a single topic. Kept apart from {@link ExpansionService} and {@link
 * EnrichmentService}: those decide and perform work, this only reports what already exists.
 */
@Service
public class TopicQueryService {

  private static final Comparator<Resource> RESOURCE_ORDER =
      Comparator.comparingInt(Resource::getPosition).thenComparing(Resource::getId);

  private final TopicRepository topicRepository;
  private final ResourceRepository resourceRepository;
  private final ConceptRepository conceptRepository;
  private final TransactionTemplate requiredTemplate;

  TopicQueryService(
      TopicRepository topicRepository,
      ResourceRepository resourceRepository,
      ConceptRepository conceptRepository,
      PlatformTransactionManager transactionManager) {
    this.topicRepository = topicRepository;
    this.resourceRepository = resourceRepository;
    this.conceptRepository = conceptRepository;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
    this.requiredTemplate.setReadOnly(true);
  }

  public TopicDetailView getTopic(Long trackId, Long topicId) {
    return requiredTemplate.execute(status -> buildDetail(trackId, topicId));
  }

  private TopicDetailView buildDetail(Long trackId, Long topicId) {
    Topic topic =
        topicRepository
            .findById(topicId)
            .filter(t -> t.getTrack().getId().equals(trackId))
            .orElseThrow(() -> new NoSuchElementException("topic not found in track: " + topicId));
    return new TopicDetailView(
        topic.getId(),
        topic.getLabel(),
        topic.isCore(),
        topic.isRoot(),
        topic.getExpansionStatus(),
        topic.getEnrichmentStatus(),
        topic.getSearchedAt(),
        resourceRepository.findByTopicId(topicId).stream()
            .sorted(RESOURCE_ORDER)
            .map(TopicQueryService::toResourceDetail)
            .toList(),
        conceptRepository.findByTopicId(topicId).stream()
            .map(TopicQueryService::toConceptView)
            .toList());
  }

  private static ResourceDetailView toResourceDetail(Resource r) {
    return new ResourceDetailView(
        r.getId(),
        r.getTitle(),
        r.getUrl(),
        r.getCitation(),
        r.getType(),
        r.getLane(),
        r.getSourceQuery(),
        r.getPosition());
  }

  private static ConceptView toConceptView(Concept c) {
    return new ConceptView(c.getId(), c.getLabel(), c.getOrigin());
  }
}
