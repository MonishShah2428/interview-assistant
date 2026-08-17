package com.interviewprep.track.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.track.service.dto.TrackView;
import com.interviewprep.track.service.llm.DecomposedTopic;
import com.interviewprep.track.service.llm.TrackDecomposer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TrackServiceTest {

  @Autowired private TrackService trackService;
  @Autowired private TopicRepository topicRepository;
  @MockitoBean private TrackDecomposer trackDecomposer;

  @Test
  void duplicateNormalizedRootLabelsCollapseToOneTopic() {
    // "Data Structures" and "data structures!!" normalize identically (case + punctuation only —
    // the deterministic LabelNormalizer doesn't merge synonyms, so a real pair like the spec's
    // "Data Structures" vs. "Data Structures & Algorithms" would NOT collapse here).
    when(trackDecomposer.decompose(anyString(), anyString()))
        .thenReturn(
            List.of(
                new DecomposedTopic("Data Structures"),
                new DecomposedTopic("data structures!!"),
                new DecomposedTopic("System Design")));

    TrackView track = trackService.createTrack("prep me for an SDE II role", "mid");

    assertThat(track.roots()).hasSize(2);
    assertThat(track.roots())
        .extracting("label")
        .containsExactlyInAnyOrder("Data Structures", "System Design");

    long topicCount =
        topicRepository.findByTrackId(track.id()).stream()
            .filter(t -> t.getNormalizedLabel().equals("data structures"))
            .count();
    assertThat(topicCount).isEqualTo(1);
  }

  @Test
  void allRootsFromOneDecomposerCallArePersisted() {
    when(trackDecomposer.decompose(anyString(), anyString()))
        .thenReturn(
            List.of(
                new DecomposedTopic("Networking"),
                new DecomposedTopic("Operating Systems"),
                new DecomposedTopic("Databases")));

    TrackView track = trackService.createTrack("prep me for a networking-heavy SRE role", "mid");

    assertThat(track.id()).isNotNull();
    assertThat(track.roots()).hasSize(3);
    assertThat(topicRepository.findByTrackId(track.id())).hasSize(3);
    assertThat(track.roots()).allSatisfy(root -> assertThat(root.isCore()).isTrue());
  }
}
