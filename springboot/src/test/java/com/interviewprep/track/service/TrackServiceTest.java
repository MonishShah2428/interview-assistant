package com.interviewprep.track.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code createTrack} tests build their own {@code TrackService} via {@link
 * #trackServiceWith(TrackDecomposer)} rather than autowiring the real bean: {@code TrackDecomposer}
 * is {@code @AiService}-annotated, and LangChain4j's Spring integration builds its proxy bean via a
 * {@code FactoryBean} that can't tolerate {@code @MockitoBean} overriding it (confirmed via a real
 * failure: {@code IllegalConfigurationException: The type implemented by the AI Service must be an
 * interface, found '...$MockitoMock$...'}). {@code getTrack} tests don't have this problem — they
 * never touch {@code TrackDecomposer} — so they use the real autowired {@link #trackService} bean.
 */
@SpringBootTest
@Transactional
class TrackServiceTest {

  @Autowired private TrackRepository trackRepository;
  @Autowired private TopicRepository topicRepository;
  @Autowired private TopicEdgeRepository topicEdgeRepository;
  @Autowired private LabelNormalizer labelNormalizer;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  private TrackService trackService; // real bean — getTrack never touches TrackDecomposer

  private TrackService trackServiceWith(TrackDecomposer stub) {
    return new TrackService(
        trackRepository,
        topicRepository,
        topicEdgeRepository,
        stub,
        labelNormalizer,
        transactionManager);
  }

  @Test
  void duplicateNormalizedRootLabelsCollapseToOneTopic() {
    // "Data Structures" and "data structures!!" normalize identically (case + punctuation only —
    // the deterministic LabelNormalizer doesn't merge synonyms, so a real pair like the spec's
    // "Data Structures" vs. "Data Structures & Algorithms" would NOT collapse here).
    TrackDecomposer stub =
        (goal, level) ->
            List.of(
                new DecomposedTopic("Data Structures"),
                new DecomposedTopic("data structures!!"),
                new DecomposedTopic("System Design"));

    TrackView track = trackServiceWith(stub).createTrack("prep me for an SDE II role", "mid");

    assertThat(track.roots()).hasSize(2);
    assertThat(track.roots())
        .extracting("label")
        .containsExactlyInAnyOrder("Data Structures", "System Design");

    long topicCount =
        topicRepository.findByTrackId(track.id()).stream()
            .filter(t -> t.getNormalizedLabel().equals("data structures"))
            .count();
    assertThat(topicCount).isEqualTo(1);

    // Position is assigned post-dedup against the deduped list (0, 1), not the raw 3-item
    // proposal list (which would put "System Design" at position 2) — proves the collapsed
    // duplicate doesn't leave a gap in the persisted ordering.
    assertThat(topicRepository.findByTrackIdAndIsRootTrueOrderByPosition(track.id()))
        .extracting("label", "position")
        .containsExactly(tuple("Data Structures", 0), tuple("System Design", 1));
  }

  @Test
  void allRootsFromOneDecomposerCallArePersisted() {
    TrackDecomposer stub =
        (goal, level) ->
            List.of(
                new DecomposedTopic("Networking"),
                new DecomposedTopic("Operating Systems"),
                new DecomposedTopic("Databases"));

    TrackView track =
        trackServiceWith(stub).createTrack("prep me for a networking-heavy SRE role", "mid");

    assertThat(track.id()).isNotNull();
    assertThat(track.roots()).hasSize(3);
    assertThat(topicRepository.findByTrackId(track.id())).hasSize(3);
    assertThat(track.roots()).allSatisfy(root -> assertThat(root.isCore()).isTrue());

    // Positions 0, 1, 2 in the decomposer's original order — not id order, not alphabetical.
    assertThat(topicRepository.findByTrackIdAndIsRootTrueOrderByPosition(track.id()))
        .extracting("label", "position")
        .containsExactly(
            tuple("Networking", 0), tuple("Operating Systems", 1), tuple("Databases", 2));
  }

  @Test
  void getTrackReturnsChildrenNestedUnderTheirRoot() {
    TrackDecomposer stub = (goal, level) -> List.of(new DecomposedTopic("Databases"));
    TrackView created = trackServiceWith(stub).createTrack("prep for a backend role", "mid");
    Long rootId = created.roots().get(0).id();

    Track trackRef = trackRepository.getReferenceById(created.id());
    Topic rootRef = topicRepository.getReferenceById(rootId);
    Topic child =
        topicRepository.saveAndFlush(new Topic(trackRef, "Indexing", "indexing", true, false));
    topicEdgeRepository.saveAndFlush(new TopicEdge(rootRef, child));

    TrackView tree = trackService.getTrack(created.id());

    assertThat(tree.roots()).hasSize(1);
    TopicNodeView root = tree.roots().get(0);
    assertThat(root.id()).isEqualTo(rootId);
    assertThat(root.children()).extracting("label").containsExactly("Indexing");
  }

  @Test
  void getTrackStopsOnACycleInsteadOfRecursingForever() {
    // The DB only blocks a literal self-edge (parent_topic_id <> child_topic_id), not a longer
    // cycle — and this isn't hypothetical: an LLM proposing a "child" that normalizes to an
    // existing ancestor's label gets reused by resolveTopicWithResources rather than rejected,
    // which is exactly how a real back-edge like this could arise.
    Track track = trackRepository.saveAndFlush(new Track(1L, "cycle test goal", "mid", null));
    Topic a = topicRepository.saveAndFlush(new Topic(track, "A", "a", true, true));
    Topic b = topicRepository.saveAndFlush(new Topic(track, "B", "b", true, false));
    topicEdgeRepository.saveAndFlush(new TopicEdge(a, b));
    topicEdgeRepository.saveAndFlush(new TopicEdge(b, a));

    TrackView tree = trackService.getTrack(track.getId());

    assertThat(tree.roots()).hasSize(1);
    TopicNodeView rootView = tree.roots().get(0);
    assertThat(rootView.label()).isEqualTo("A");
    assertThat(rootView.children()).extracting("label").containsExactly("B");
    TopicNodeView bView = rootView.children().get(0);
    // B's own child is A again (the real cycle), but recursion stops there — A's second
    // appearance carries no children of its own, rather than looping back to B forever.
    assertThat(bView.children()).extracting("label").containsExactly("A");
    assertThat(bView.children().get(0).children()).isEmpty();
  }
}
