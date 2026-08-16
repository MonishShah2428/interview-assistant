package com.interviewprep.node.service;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.Resource;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.TopicEdge;
import com.interviewprep.node.entity.Track;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.repository.TopicEdgeRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.repository.TrackRepository;
import com.interviewprep.node.service.dto.ExpandTopicResponse;
import com.interviewprep.node.service.dto.ExpandedChild;
import com.interviewprep.node.service.dto.ResourceView;
import com.interviewprep.node.service.llm.LabelNormalizer;
import com.interviewprep.node.service.llm.expansion.ProposedChild;
import com.interviewprep.node.service.llm.expansion.TopicExpander;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Create-or-reuse for a topic's children. No method here is {@code @Transactional} — every boundary
 * is explicit via {@link #requiredTemplate}/{@link #requiresNewTemplate} so an LLM call is never
 * made while holding a DB connection open, and {@code @Transactional}'s self-invocation limitation
 * (calling another {@code @Transactional} method on {@code this} bypasses the proxy) never comes
 * into play.
 */
@Service
public class ExpansionService {

  private static final List<ExpansionStatus> CLAIMABLE_STATUSES =
      List.of(ExpansionStatus.not_expanded, ExpansionStatus.failed);

  private final TopicRepository topicRepository;
  private final TopicEdgeRepository topicEdgeRepository;
  private final ResourceRepository resourceRepository;
  private final TrackRepository trackRepository;
  private final EnrichmentJobRepository enrichmentJobRepository;
  private final TopicExpander topicExpander;
  private final LabelNormalizer labelNormalizer;
  private final ParentChainResolver parentChainResolver;
  private final TransactionTemplate requiredTemplate;
  private final TransactionTemplate requiresNewTemplate;

  ExpansionService(
      TopicRepository topicRepository,
      TopicEdgeRepository topicEdgeRepository,
      ResourceRepository resourceRepository,
      TrackRepository trackRepository,
      EnrichmentJobRepository enrichmentJobRepository,
      TopicExpander topicExpander,
      LabelNormalizer labelNormalizer,
      ParentChainResolver parentChainResolver,
      PlatformTransactionManager transactionManager) {
    this.topicRepository = topicRepository;
    this.topicEdgeRepository = topicEdgeRepository;
    this.resourceRepository = resourceRepository;
    this.trackRepository = trackRepository;
    this.enrichmentJobRepository = enrichmentJobRepository;
    this.topicExpander = topicExpander;
    this.labelNormalizer = labelNormalizer;
    this.parentChainResolver = parentChainResolver;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTemplate = new TransactionTemplate(transactionManager);
    this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public ExpandTopicResponse expandTopic(Long topicId) {
    int claimed =
        requiredTemplate.execute(
            status ->
                topicRepository.claimForExpansion(
                    topicId, ExpansionStatus.expanding, CLAIMABLE_STATUSES));

    if (claimed == 0) {
      // Doesn't exist, already expanded, or another request already owns it — return current state.
      return requiredTemplate.execute(status -> buildResponse(topicId));
    }

    try {
      PromptContext ctx = requiredTemplate.execute(status -> gatherContext(topicId));

      List<ProposedChild> proposals = // LLM call, no transaction open
          topicExpander.expand(
              ctx.level(), ctx.topicLabel(), ctx.parentChainText(), ctx.siblingLabelsText());

      List<ExpandedChild> children = resolveAndPersist(ctx.trackId(), topicId, proposals);

      requiredTemplate.executeWithoutResult(
          status -> topicRepository.updateExpansionStatus(topicId, ExpansionStatus.expanded));

      return new ExpandTopicResponse(ExpansionStatus.expanded, children);
    } catch (RuntimeException e) {
      requiredTemplate.executeWithoutResult(
          status -> topicRepository.updateExpansionStatus(topicId, ExpansionStatus.failed));
      throw e;
    }
  }

  // ---- step 2: prompt context (one read transaction) ----

  private PromptContext gatherContext(Long topicId) {
    Topic topic =
        topicRepository
            .findById(topicId)
            .orElseThrow(() -> new NoSuchElementException("topic not found: " + topicId));
    Long trackId = topic.getTrack().getId();
    String level = topic.getTrack().getLevel();

    List<TopicEdge> childEdges = topicEdgeRepository.findByIdParentTopicId(topicId);
    String siblingLabelsText =
        childEdges.isEmpty()
            ? "(none yet)"
            : childEdges.stream()
                .map(e -> e.getChildTopic().getLabel())
                .collect(Collectors.joining(", "));

    return new PromptContext(
        trackId, level, topic.getLabel(), parentChainResolver.resolve(topicId), siblingLabelsText);
  }

  // ---- step 4: resolve + persist each proposal ----

  private List<ExpandedChild> resolveAndPersist(
      Long trackId, Long parentTopicId, List<ProposedChild> proposals) {
    List<ExpandedChild> results = new ArrayList<>(proposals.size());
    for (ProposedChild proposal : proposals) {
      results.add(resolveProposal(trackId, parentTopicId, proposal));
    }
    return results;
  }

  private ExpandedChild resolveProposal(Long trackId, Long parentTopicId, ProposedChild proposal) {
    String normalizedLabel =
        labelNormalizer.normalize(proposal.label()); // deterministic, no LLM call

    ResolvedChild resolved = resolveTopicWithResources(trackId, proposal, normalizedLabel);

    try {
      requiresNewTemplate.executeWithoutResult(status -> insertEdge(parentTopicId, resolved.id()));
    } catch (DataIntegrityViolationException raceOnEdge) {
      // Edge already exists — a concurrent request or a retried partial expansion already wrote
      // it. Nothing to re-read; its existence is exactly the state we wanted.
    }

    return new ExpandedChild(
        resolved.id(), resolved.label(), resolved.isCore(), resolved.resources());
  }

  private ResolvedChild resolveTopicWithResources(
      Long trackId, ProposedChild proposal, String normalizedLabel) {
    try {
      return requiresNewTemplate.execute(
          status -> {
            Topic topic =
                topicRepository
                    .findByTrackIdAndNormalizedLabel(trackId, normalizedLabel)
                    .orElseGet(
                        () -> {
                          Track trackRef = trackRepository.getReferenceById(trackId);
                          Topic created =
                              new Topic(
                                  trackRef,
                                  proposal.label(),
                                  normalizedLabel,
                                  proposal.isCore(),
                                  false);
                          topicRepository.saveAndFlush(created); // force the constraint check now
                          // Atomic with the topic row: same transaction, so a topic can never
                          // exist without a queued enrichment job. Enrichment has no latency
                          // budget, so the actual work happens off this sync 3-5s pipeline —
                          // EnrichmentJobPoller picks the job up and calls
                          // EnrichmentService.enrichTopic.
                          enrichmentJobRepository.save(new EnrichmentJob(created.getId()));
                          return created;
                        });
            return new ResolvedChild(
                topic.getId(), topic.getLabel(), topic.isCore(), toResourceViews(topic.getId()));
          });
    } catch (DataIntegrityViolationException race) {
      // Lost the create race: the transaction above aborted at the DB level on the unique
      // constraint. Re-read in a brand-new transaction — never in the one that just aborted.
      return requiredTemplate.execute(
          status -> {
            Topic topic =
                topicRepository
                    .findByTrackIdAndNormalizedLabel(trackId, normalizedLabel)
                    .orElseThrow(() -> race);
            return new ResolvedChild(
                topic.getId(), topic.getLabel(), topic.isCore(), toResourceViews(topic.getId()));
          });
    }
  }

  private void insertEdge(Long parentTopicId, Long childId) {
    Topic parentRef = topicRepository.getReferenceById(parentTopicId);
    Topic childRef = topicRepository.getReferenceById(childId);
    topicEdgeRepository.saveAndFlush(new TopicEdge(parentRef, childRef));
  }

  // ---- guard early-return / response assembly ----

  private ExpandTopicResponse buildResponse(Long topicId) {
    Topic topic =
        topicRepository
            .findById(topicId)
            .orElseThrow(() -> new NoSuchElementException("topic not found: " + topicId));
    List<ExpandedChild> children =
        topicEdgeRepository.findByIdParentTopicId(topicId).stream()
            .map(edge -> toExpandedChild(edge.getChildTopic()))
            .toList();
    return new ExpandTopicResponse(topic.getExpansionStatus(), children);
  }

  private ExpandedChild toExpandedChild(Topic child) {
    return new ExpandedChild(
        child.getId(), child.getLabel(), child.isCore(), toResourceViews(child.getId()));
  }

  private List<ResourceView> toResourceViews(Long topicId) {
    return resourceRepository.findByTopicId(topicId).stream().map(this::toResourceView).toList();
  }

  private ResourceView toResourceView(Resource r) {
    return new ResourceView(r.getId(), r.getTitle(), r.getUrl(), r.getType(), r.getLane());
  }

  private record PromptContext(
      Long trackId,
      String level,
      String topicLabel,
      String parentChainText,
      String siblingLabelsText) {}

  private record ResolvedChild(
      Long id, String label, boolean isCore, List<ResourceView> resources) {}
}
