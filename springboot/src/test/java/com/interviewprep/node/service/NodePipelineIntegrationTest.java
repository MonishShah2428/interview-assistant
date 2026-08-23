package com.interviewprep.node.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.entity.EnrichmentStatus;
import com.interviewprep.node.entity.ExpansionStatus;
import com.interviewprep.node.entity.Resource;
import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;
import com.interviewprep.node.entity.Topic;
import com.interviewprep.node.entity.Track;
import com.interviewprep.node.repository.ConceptRepository;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.repository.TopicEdgeRepository;
import com.interviewprep.node.repository.TopicRepository;
import com.interviewprep.node.repository.TrackRepository;
import com.interviewprep.node.service.dto.ExpandTopicResponse;
import com.interviewprep.node.service.dto.ExpandedChild;
import com.interviewprep.node.service.llm.LabelNormalizer;
import com.interviewprep.node.service.llm.enrichment.ConceptGenerator;
import com.interviewprep.node.service.llm.enrichment.ProposedConcept;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.ResourceFinder;
import com.interviewprep.node.service.llm.expansion.ProposedChild;
import com.interviewprep.node.service.llm.expansion.TopicExpander;
import com.interviewprep.node.sse.EnrichmentEventPublisher;
import com.interviewprep.track.service.TrackService;
import com.interviewprep.track.service.dto.TopicNodeView;
import com.interviewprep.track.service.dto.TrackView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Drives the whole {@code node} pipeline end to end against a real Postgres instance and real
 * transaction boundaries: root -> expand -> enrich -> "find more resources" -> tree read. Only the
 * three outbound LLM/search boundaries ({@link TopicExpander}, {@link ConceptGenerator}, {@link
 * ResourceFinder}) are stubbed with realistic canned output.
 *
 * <p>{@link ExpansionService} and {@link EnrichmentService} are hand-constructed with those stubs
 * rather than overridden via {@code @MockitoBean}, same reasoning as {@code
 * TrackServiceTest#trackServiceWith}: {@code TopicExpander}/{@code ConceptGenerator} are
 * {@code @AiService}-backed, and LangChain4j's Spring integration builds that proxy via a {@code
 * FactoryBean} that can't tolerate {@code @MockitoBean} overriding it. Track/root creation is
 * seeded directly through the repositories rather than through {@code TrackService.createTrack} —
 * root creation with a stubbed {@code TrackDecomposer} is already covered by {@code
 * TrackServiceTest}, so this test's stub surface stays focused on the three collaborators it's
 * actually exercising. The final tree read goes through the real, unstubbed {@code TrackService}
 * bean, since {@code getTrack} never touches {@code TrackDecomposer}.
 *
 * <p>The background {@link com.interviewprep.node.queue.EnrichmentJobPoller} is disabled just for
 * this test class via {@code @TestPropertySource} (not a shared {@code
 * src/test/resources/application.properties} — that would shadow, not merge with, the main
 * classpath resource of the same name and silently strip every other test's datasource config) so
 * it never races these direct service calls for the same {@code enrichment_status} claim.
 */
@SpringBootTest
@TestPropertySource(properties = "enrichment.polling.enabled=false")
class NodePipelineIntegrationTest {

  @Autowired private TrackRepository trackRepository;
  @Autowired private TopicRepository topicRepository;
  @Autowired private TopicEdgeRepository topicEdgeRepository;
  @Autowired private ResourceRepository resourceRepository;
  @Autowired private ConceptRepository conceptRepository;
  @Autowired private EnrichmentJobRepository enrichmentJobRepository;
  @Autowired private LabelNormalizer labelNormalizer;
  @Autowired private ParentChainResolver parentChainResolver;
  @Autowired private EnrichmentEventPublisher enrichmentEventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  @Qualifier("applicationTaskExecutor")
  private AsyncTaskExecutor executor;

  @Autowired
  private TrackService trackService; // real bean — getTrack never touches TrackDecomposer

  private Long createdTrackId;

  @AfterEach
  void cleanUp() {
    if (createdTrackId == null) {
      return;
    }
    List<Topic> topics = topicRepository.findByTrackId(createdTrackId);
    for (Topic topic : topics) {
      resourceRepository.deleteAll(resourceRepository.findByTopicId(topic.getId()));
      conceptRepository.deleteAll(conceptRepository.findByTopicId(topic.getId()));
      enrichmentJobRepository.deleteAll(
          enrichmentJobRepository.findAll().stream()
              .filter(j -> j.getTopicId().equals(topic.getId()))
              .toList());
    }
    topicEdgeRepository.deleteAll(
        topicEdgeRepository.findByIdParentTopicIdIn(topics.stream().map(Topic::getId).toList()));
    topicRepository.deleteAll(topics);
    trackRepository.deleteById(createdTrackId);
  }

  @Test
  void fullNodePipelineWithStubbedLlmOutput() {
    TopicExpander expanderStub =
        (level, topicLabel, parentChainText, siblingLabelsText) ->
            List.of(
                new ProposedChild("Load Balancing", true),
                new ProposedChild("Caching Strategies", true),
                new ProposedChild("Database Sharding", true),
                new ProposedChild("Rate Limiting", false));

    Map<String, List<ProposedConcept>> conceptsByTopic =
        Map.of(
            "Load Balancing",
                List.of(
                    new ProposedConcept("Layer 4 Vs Layer 7 Load Balancing"),
                    new ProposedConcept("Round Robin Scheduling"),
                    new ProposedConcept("Least Connections Scheduling"),
                    new ProposedConcept("Consistent Hashing"),
                    new ProposedConcept("Health Checks"),
                    new ProposedConcept("Sticky Sessions")),
            "Caching Strategies",
                List.of(
                    new ProposedConcept("Cache Aside Pattern"),
                    new ProposedConcept("Write Through Caching"),
                    new ProposedConcept("Write Back Caching"),
                    new ProposedConcept("Cache Eviction Policies"),
                    new ProposedConcept("Cache Stampede")),
            "Database Sharding",
                List.of(
                    new ProposedConcept("Horizontal Vs Vertical Partitioning"),
                    new ProposedConcept("Shard Key Selection"),
                    new ProposedConcept("Rebalancing Shards"),
                    new ProposedConcept("Cross Shard Joins")),
            "Rate Limiting",
                List.of(
                    new ProposedConcept("Token Bucket Algorithm"),
                    new ProposedConcept("Sliding Window Counter")));

    Map<String, List<ProposedResource>> initialResourcesByTopic =
        Map.of(
            "Load Balancing",
                List.of(
                    new ProposedResource(
                        ResourceType.docs,
                        ResourceLane.read,
                        "AWS: What Is Load Balancing?",
                        "https://aws.amazon.com/what-is/load-balancing/",
                        "AWS Docs",
                        "load balancing interview"),
                    new ProposedResource(
                        ResourceType.video,
                        ResourceLane.watch,
                        "Load Balancing Explained",
                        "https://www.youtube.com/watch?v=K0Ta65OqQkY",
                        "Gaurav Sen",
                        "load balancing system design"),
                    new ProposedResource(
                        ResourceType.blog,
                        ResourceLane.read,
                        "NGINX Load Balancing Guide",
                        "https://www.nginx.com/resources/glossary/load-balancing/",
                        "NGINX",
                        "load balancing algorithms")),
            "Caching Strategies",
                List.of(
                    new ProposedResource(
                        ResourceType.docs,
                        ResourceLane.read,
                        "AWS Caching Overview",
                        "https://aws.amazon.com/caching/",
                        "AWS Docs",
                        "caching strategies interview"),
                    new ProposedResource(
                        ResourceType.problem,
                        ResourceLane.practice,
                        "Design a Caching System",
                        "https://leetcode.com/discuss/interview-question/system-design/",
                        "LeetCode Discuss",
                        "cache design practice")),
            "Database Sharding",
                List.of(
                    new ProposedResource(
                        ResourceType.docs,
                        ResourceLane.read,
                        "MongoDB Sharding Docs",
                        "https://www.mongodb.com/docs/manual/sharding/",
                        "MongoDB",
                        "database sharding"),
                    new ProposedResource(
                        ResourceType.video,
                        ResourceLane.watch,
                        "Sharding Explained",
                        "https://www.youtube.com/watch?v=iRTV1qvHi0k",
                        "Gaurav Sen",
                        "sharding system design")),
            "Rate Limiting",
                List.of(
                    new ProposedResource(
                        ResourceType.blog,
                        ResourceLane.read,
                        "Rate Limiting Algorithms",
                        "https://konghq.com/blog/engineering/rate-limiting",
                        "Kong",
                        "rate limiting interview")));

    // Only surfaced once the topic already has resources — mirrors the real ResourceFinder's
    // existingResourcesFor tool steering the agent away from re-proposing what's already there.
    ProposedResource loadBalancingRefreshFind =
        new ProposedResource(
            ResourceType.video,
            ResourceLane.watch,
            "Load Balancing Deep Dive (System Design Interview)",
            "https://www.youtube.com/watch?v=zpDU5JTLB48",
            "ByteByteGo",
            "load balancing find more");

    ConceptGenerator conceptGeneratorStub =
        (level, topicLabel, parentChainText) -> conceptsByTopic.getOrDefault(topicLabel, List.of());

    ResourceFinder resourceFinderStub =
        (topicId, topicLabel, level) -> {
          boolean alreadySearched = !resourceRepository.findByTopicId(topicId).isEmpty();
          if (!alreadySearched) {
            return initialResourcesByTopic.getOrDefault(topicLabel, List.of());
          }
          return topicLabel.equals("Load Balancing")
              ? List.of(loadBalancingRefreshFind)
              : List.of();
        };

    ExpansionService expansionService =
        new ExpansionService(
            topicRepository,
            topicEdgeRepository,
            resourceRepository,
            trackRepository,
            enrichmentJobRepository,
            expanderStub,
            labelNormalizer,
            parentChainResolver,
            transactionManager);

    EnrichmentService enrichmentService =
        new EnrichmentService(
            topicRepository,
            resourceRepository,
            conceptRepository,
            resourceFinderStub,
            conceptGeneratorStub,
            labelNormalizer,
            parentChainResolver,
            enrichmentEventPublisher,
            executor,
            transactionManager);

    // ---- 1. seed a track with one root ----
    Track track =
        trackRepository.saveAndFlush(
            new Track(1L, "prep for a backend SDE II interview", "mid", null));
    createdTrackId = track.getId();
    Topic systemDesignRoot =
        topicRepository.saveAndFlush(
            new Topic(track, "System Design", "system design", true, true, 0));

    // ---- 2. expand the root ----
    ExpandTopicResponse expandResponse =
        expansionService.expandTopic(track.getId(), systemDesignRoot.getId());

    assertThat(expandResponse.status()).isEqualTo(ExpansionStatus.expanded);
    assertThat(expandResponse.children())
        .extracting("label")
        .containsExactlyInAnyOrder(
            "Load Balancing", "Caching Strategies", "Database Sharding", "Rate Limiting");

    // ---- 3. enrich every child directly (no waiting on the async job queue) ----
    for (ExpandedChild child : expandResponse.children()) {
      enrichmentService.enrichTopic(child.topicId());
    }

    for (ExpandedChild child : expandResponse.children()) {
      Topic enriched = topicRepository.findById(child.topicId()).orElseThrow();
      assertThat(enriched.getEnrichmentStatus()).isEqualTo(EnrichmentStatus.ready);
      assertThat(conceptRepository.findByTopicId(child.topicId()))
          .hasSameSizeAs(conceptsByTopic.get(child.label()));
      assertThat(resourceRepository.findByTopicId(child.topicId()))
          .hasSameSizeAs(initialResourcesByTopic.get(child.label()));
    }

    // ---- 4. "find more resources" on Load Balancing ----
    Long loadBalancingId =
        expandResponse.children().stream()
            .filter(c -> c.label().equals("Load Balancing"))
            .findFirst()
            .orElseThrow()
            .topicId();
    int beforeRefresh = resourceRepository.findByTopicId(loadBalancingId).size();

    enrichmentService.refreshResources(loadBalancingId);

    List<Resource> afterRefresh = resourceRepository.findByTopicId(loadBalancingId);
    assertThat(afterRefresh).hasSize(beforeRefresh + 1);
    assertThat(afterRefresh).extracting(Resource::getUrl).contains(loadBalancingRefreshFind.url());

    // Refreshing again must not duplicate the same find (resource_unique_url backstop).
    enrichmentService.refreshResources(loadBalancingId);
    assertThat(resourceRepository.findByTopicId(loadBalancingId)).hasSize(beforeRefresh + 1);

    // ---- 5. read the full tree back through the real, unstubbed TrackService ----
    TrackView tree = trackService.getTrack(track.getId());

    assertThat(tree.roots()).hasSize(1);
    TopicNodeView root = tree.roots().get(0);
    assertThat(root.children()).hasSize(4);

    printTree(tree);
  }

  private void printTree(TrackView tree) {
    StringBuilder out = new StringBuilder();
    out.append("\n=== Track ").append(tree.id()).append(" ===\n");
    out.append("goal: ").append(tree.goal()).append("  level: ").append(tree.level()).append('\n');
    for (TopicNodeView root : tree.roots()) {
      appendNode(out, root, 0);
    }
    out.append("\nenrichment_job rows left for this track's topics: ")
        .append(
            enrichmentJobRepository.findAll().stream()
                .filter(
                    j ->
                        topicRepository.findByTrackId(createdTrackId).stream()
                            .anyMatch(t -> t.getId().equals(j.getTopicId())))
                .count())
        .append('\n');
    System.out.println(out);
  }

  private void appendNode(StringBuilder out, TopicNodeView node, int depth) {
    out.append("  ".repeat(depth))
        .append("- ")
        .append(node.label())
        .append(" [core=")
        .append(node.isCore())
        .append(", expansion=")
        .append(node.expansionStatus())
        .append(", enrichment=")
        .append(node.enrichmentStatus())
        .append(']')
        .append('\n');
    if (node.enrichmentStatus() == EnrichmentStatus.ready) {
      for (var resource : resourceRepository.findByTopicId(node.id())) {
        out.append("  ".repeat(depth + 1))
            .append("resource: [")
            .append(resource.getType())
            .append('/')
            .append(resource.getLane())
            .append("] ")
            .append(resource.getTitle())
            .append(" -> ")
            .append(resource.getUrl())
            .append('\n');
      }
      for (var concept : conceptRepository.findByTopicId(node.id())) {
        out.append("  ".repeat(depth + 1))
            .append("concept: ")
            .append(concept.getLabel())
            .append('\n');
      }
    }
    for (TopicNodeView child : node.children()) {
      appendNode(out, child, depth + 1);
    }
  }
}
