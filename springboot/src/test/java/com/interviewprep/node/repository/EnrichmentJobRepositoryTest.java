package com.interviewprep.node.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.entity.EnrichmentJobStatus;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.Track;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class EnrichmentJobRepositoryTest {

  @Autowired private EnrichmentJobRepository enrichmentJobRepository;
  @Autowired private TrackRepository trackRepository;
  @Autowired private TopicRepository topicRepository;
  @Autowired private EntityManager entityManager;

  @Test
  void claimBatchClaimsOnlyRequestedCountAndLeavesTheRestPending() {
    Track track = trackRepository.saveAndFlush(new Track(1L, "goal", "mid", null));
    Topic topicA = topicRepository.saveAndFlush(new Topic(track, "Topic A", "topic a", true, true));
    Topic topicB = topicRepository.saveAndFlush(new Topic(track, "Topic B", "topic b", true, true));

    EnrichmentJob jobA = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicA.getId()));
    EnrichmentJob jobB = enrichmentJobRepository.saveAndFlush(new EnrichmentJob(topicB.getId()));

    List<EnrichmentJobRepository.ClaimedJob> claimed = enrichmentJobRepository.claimBatch(1, 5);
    // claimBatch is a native query, so it bypasses the persistence context entirely — jobA/jobB
    // are still cached from saveAndFlush above and would otherwise mask the update with stale
    // in-memory state.
    entityManager.clear();

    assertThat(claimed).hasSize(1);
    Long claimedId = claimed.get(0).getId();
    assertThat(Set.of(jobA.getId(), jobB.getId())).contains(claimedId);

    EnrichmentJob claimedRow = enrichmentJobRepository.findById(claimedId).orElseThrow();
    assertThat(claimedRow.getStatus()).isEqualTo(EnrichmentJobStatus.processing);

    Long untouchedId = claimedId.equals(jobA.getId()) ? jobB.getId() : jobA.getId();
    EnrichmentJob untouchedRow = enrichmentJobRepository.findById(untouchedId).orElseThrow();
    assertThat(untouchedRow.getStatus()).isEqualTo(EnrichmentJobStatus.pending);
  }
}
