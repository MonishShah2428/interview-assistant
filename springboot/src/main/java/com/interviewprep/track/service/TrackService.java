package com.interviewprep.track.service;

import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.Track;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.repository.TrackRepository;
import com.interviewprep.node.service.llm.LabelNormalizer;
import com.interviewprep.track.service.dto.TopicNodeView;
import com.interviewprep.track.service.dto.TrackView;
import com.interviewprep.track.service.llm.DecomposedTopic;
import com.interviewprep.track.service.llm.TrackDecomposer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Track creation. No {@code @Transactional} — same reasoning as {@code ExpansionService}: the
 * decomposer is an LLM call and must never run while holding a DB connection open, so every
 * boundary is explicit via {@link #requiredTemplate}.
 *
 * <p>Roots are containers you expand through, not topics you study from — this service deliberately
 * does no expansion and no enrichment enqueue for them. Easy to accidentally reintroduce since
 * {@link #createRoots} looks so close to {@code ExpansionService#resolveTopicWithResources}; don't.
 */
@Service
public class TrackService {

  // No auth yet — single-user until real auth exists.
  private static final Long PLACEHOLDER_USER_ID = 1L;

  private final TrackRepository trackRepository;
  private final TopicRepository topicRepository;
  private final TrackDecomposer trackDecomposer;
  private final LabelNormalizer labelNormalizer;
  private final TransactionTemplate requiredTemplate;

  TrackService(
      TrackRepository trackRepository,
      TopicRepository topicRepository,
      TrackDecomposer trackDecomposer,
      LabelNormalizer labelNormalizer,
      PlatformTransactionManager transactionManager) {
    this.trackRepository = trackRepository;
    this.topicRepository = topicRepository;
    this.trackDecomposer = trackDecomposer;
    this.labelNormalizer = labelNormalizer;
    this.requiredTemplate = new TransactionTemplate(transactionManager);
  }

  public TrackView createTrack(String goal, String level) {
    Track track =
        requiredTemplate.execute(
            status ->
                trackRepository.saveAndFlush(new Track(PLACEHOLDER_USER_ID, goal, level, null)));

    List<DecomposedTopic> proposals = trackDecomposer.decompose(goal, level); // no transaction open

    List<Topic> roots = requiredTemplate.execute(status -> createRoots(track, proposals));

    return toTrackView(track, roots);
  }

  /**
   * One plain transaction, atomic across every root — a partial failure must never leave a track
   * with three of six roots. Deduping happens here, in memory, before any insert: the decomposer
   * can return "Data Structures" and "Data Structures & Algorithms" (or, after normalization, two
   * labels that collapse to the same string) in the same response, and a {@code LinkedHashMap}
   * keyed by normalized label collapses them while preserving the decomposer's order.
   *
   * <p>Unlike {@code ExpansionService}'s createOrLink, this never needs insert-first/catch-the-
   * violation handling: roots only ever get created once, by this one call, for a track that didn't
   * exist a moment ago, so there's no other request that could be racing to create the same root —
   * the only way two roots could collide on {@code (track_id, normalized_label)} is within this
   * exact batch, which the dedup above already prevents.
   */
  private List<Topic> createRoots(Track track, List<DecomposedTopic> proposals) {
    Map<String, DecomposedTopic> deduped = new LinkedHashMap<>();
    for (DecomposedTopic proposal : proposals) {
      deduped.putIfAbsent(labelNormalizer.normalize(proposal.label()), proposal);
    }
    return deduped.entrySet().stream()
        .map(
            entry -> {
              Topic created =
                  new Topic(track, entry.getValue().label(), entry.getKey(), true, true);
              topicRepository.saveAndFlush(created); // force the constraint check now
              return created;
            })
        .toList();
  }

  private TrackView toTrackView(Track track, List<Topic> roots) {
    return new TrackView(
        track.getId(),
        track.getGoal(),
        track.getLevel(),
        roots.stream().map(this::toTopicNodeView).toList());
  }

  private TopicNodeView toTopicNodeView(Topic topic) {
    return new TopicNodeView(
        topic.getId(),
        topic.getLabel(),
        topic.isCore(),
        topic.getExpansionStatus(),
        topic.getEnrichmentStatus());
  }
}
