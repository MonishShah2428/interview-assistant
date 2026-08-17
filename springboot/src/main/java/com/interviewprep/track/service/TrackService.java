package com.interviewprep.track.service;

import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.TopicEdge;
import com.interviewprep.node.entity.Track;
import com.interviewprep.node.repository.TopicEdgeRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.repository.TrackRepository;
import com.interviewprep.node.service.llm.LabelNormalizer;
import com.interviewprep.track.service.dto.TopicNodeView;
import com.interviewprep.track.service.dto.TrackView;
import com.interviewprep.track.service.llm.DecomposedTopic;
import com.interviewprep.track.service.llm.TrackDecomposer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
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
  private final TopicEdgeRepository topicEdgeRepository;
  private final TrackDecomposer trackDecomposer;
  private final LabelNormalizer labelNormalizer;
  private final TransactionTemplate requiredTemplate;

  TrackService(
      TrackRepository trackRepository,
      TopicRepository topicRepository,
      TopicEdgeRepository topicEdgeRepository,
      TrackDecomposer trackDecomposer,
      LabelNormalizer labelNormalizer,
      PlatformTransactionManager transactionManager) {
    this.trackRepository = trackRepository;
    this.topicRepository = topicRepository;
    this.topicEdgeRepository = topicEdgeRepository;
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
   * Reads a track's full topic tree back, recursively — the DAG's own point is that a topic can
   * legitimately hang under several parents, so this materializes every path rather than a strict
   * single-parent tree.
   */
  public TrackView getTrack(Long trackId) {
    return requiredTemplate.execute(status -> buildTrackView(trackId));
  }

  private TrackView buildTrackView(Long trackId) {
    Track track =
        trackRepository
            .findById(trackId)
            .orElseThrow(() -> new NoSuchElementException("track not found: " + trackId));

    List<Topic> allTopics = topicRepository.findByTrackId(trackId);
    Map<Long, List<Topic>> childrenByParentId = groupChildrenByParent(allTopics);
    List<Topic> roots = topicRepository.findByTrackIdAndIsRootTrueOrderByPosition(trackId);

    List<TopicNodeView> rootViews =
        roots.stream()
            .map(root -> toTopicNodeView(root, childrenByParentId, new HashSet<>()))
            .toList();

    return new TrackView(track.getId(), track.getGoal(), track.getLevel(), rootViews);
  }

  private Map<Long, List<Topic>> groupChildrenByParent(List<Topic> allTopics) {
    Map<Long, Topic> topicsById = new HashMap<>();
    List<Long> topicIds = new ArrayList<>(allTopics.size());
    for (Topic topic : allTopics) {
      topicsById.put(topic.getId(), topic);
      topicIds.add(topic.getId());
    }

    Map<Long, List<Topic>> childrenByParentId = new HashMap<>();
    for (TopicEdge edge : topicEdgeRepository.findByIdParentTopicIdIn(topicIds)) {
      Topic child = topicsById.get(edge.getId().getChildTopicId());
      childrenByParentId
          .computeIfAbsent(edge.getId().getParentTopicId(), key -> new ArrayList<>())
          .add(child);
    }
    return childrenByParentId;
  }

  // pathVisited tracks only the current path down from a root, not a global visited set — the same
  // topic reappearing under a different parent is the DAG working as intended, but a topic
  // reappearing on its own path (a genuine cycle: possible if an LLM proposes a "child" that
  // normalizes to an existing ancestor's label, which resolveTopicWithResources would reuse rather
  // than reject) must stop recursing instead of overflowing the stack.
  private TopicNodeView toTopicNodeView(
      Topic topic, Map<Long, List<Topic>> childrenByParentId, Set<Long> pathVisited) {
    if (!pathVisited.add(topic.getId())) {
      return toTopicNodeView(topic, List.of());
    }
    List<Topic> children = childrenByParentId.getOrDefault(topic.getId(), List.of());
    List<TopicNodeView> childViews =
        children.stream()
            .map(child -> toTopicNodeView(child, childrenByParentId, new HashSet<>(pathVisited)))
            .toList();
    return toTopicNodeView(topic, childViews);
  }

  /**
   * One plain transaction, atomic across every root — a partial failure must never leave a track
   * with three of six roots. Deduping happens here, in memory, before any insert: the decomposer
   * can return "Data Structures" and "Data Structures & Algorithms" (or, after normalization, two
   * labels that collapse to the same string) in the same response, and a {@code LinkedHashMap}
   * keyed by normalized label collapses them while preserving the decomposer's order — a dedupe
   * collapsing two proposed roots into one is expected and fine, not an error to assert against.
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
    List<Topic> roots = new ArrayList<>(deduped.size());
    int position = 0;
    for (Map.Entry<String, DecomposedTopic> entry : deduped.entrySet()) {
      roots.add(createRoot(track, entry.getValue(), entry.getKey(), position++));
    }
    return roots;
  }

  /**
   * Persists one deduped root: is_root = true, no edge — roots have no parent, which is the whole
   * reason this is a separate method from createOrLink rather than that method called with a null
   * parent. Position is preserved so the decomposer's foundational-first ordering survives into
   * storage instead of decaying to id/alphabetical order. Runs inside createRoots' single
   * transaction, not its own — the dedup above already ran before any insert, so the unique
   * constraint below is a backstop that should never actually fire, not an active catch loop; see
   * this class's Javadoc for why REQUIRES_NEW/catch-and-continue was rejected here.
   */
  private Topic createRoot(
      Track track, DecomposedTopic proposal, String normalizedLabel, int position) {
    Topic created = new Topic(track, proposal.label(), normalizedLabel, true, true, position);
    topicRepository.saveAndFlush(created); // force the constraint check now
    return created;
  }

  private TrackView toTrackView(Track track, List<Topic> roots) {
    return new TrackView(
        track.getId(),
        track.getGoal(),
        track.getLevel(),
        roots.stream().map(this::toTopicNodeView).toList());
  }

  private TopicNodeView toTopicNodeView(Topic topic) {
    return toTopicNodeView(topic, List.of());
  }

  private TopicNodeView toTopicNodeView(Topic topic, List<TopicNodeView> children) {
    return new TopicNodeView(
        topic.getId(),
        topic.getLabel(),
        topic.isCore(),
        topic.getExpansionStatus(),
        topic.getEnrichmentStatus(),
        children);
  }
}
