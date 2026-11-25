package com.interviewprep.node.service.llm.enrichment.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;
import com.interviewprep.node.service.llm.enrichment.ExistingResource;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import com.interviewprep.node.service.llm.enrichment.SearchType;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AgenticResourceFinderTest {

  @Test
  void parallelHappyPathCompletesInTwoRoundsWithCompactContextAndHydratedMetadata() {
    StubSearchTools searchTools =
        new StubSearchTools(
            List.of(),
            Map.of(
                "open",
                List.of(
                    new SearchResult(
                        "Consistent Hashing Guide",
                        "https://highscalability.com/consistent-hashing",
                        "![img](https://cdn.example.com/a.png) Explains [hash rings](https://example.com) and virtual nodes."),
                    new SearchResult(
                        "Dynamo Paper Overview",
                        "https://example.com/dynamo-blog",
                        "Practical virtual node balancing tutorial."),
                    new SearchResult(
                        "Third Web Hit", "https://example.com/web-3", "Third snippet."),
                    new SearchResult(
                        "Fourth Web Hit", "https://example.com/web-4", "Fourth snippet."),
                    new SearchResult(
                        "Fifth Overflow Hit", "https://example.com/web-5", "Overflow snippet.")),
                "video",
                List.of(
                    new SearchResult(
                        "Consistent Hashing Walkthrough",
                        "https://www.youtube.com/watch?v=abc123",
                        "Visual walkthrough for system design interviews.")),
                "practice",
                List.of(
                    new SearchResult(
                        "Design Consistent Hashing",
                        "https://leetcode.com/problems/design-consistent-hashing/",
                        "Implement consistent hashing ring."),
                    new SearchResult(
                        "Dead Link Problem",
                        "https://neetcode.io/problems/dead-link",
                        "Should be dropped by validateUrl."))));
    searchTools.invalidUrls.add("https://neetcode.io/problems/dead-link");

    AtomicInteger round = new AtomicInteger();
    ScriptedChatModel toolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              if (round.getAndIncrement() == 0) {
                return response(
                    AiMessage.from(
                        List.of(
                            toolReq("c1", "webSearch", "consistent hashing tutorial virtual nodes"),
                            toolReq("c2", "searchVideos", "consistent hashing system design video"),
                            toolReq(
                                "c3",
                                "searchPracticeProblems",
                                "consistent hashing coding interview exercise"))),
                    120,
                    60);
              }
              int webId = findIdForTitle(req, "Consistent Hashing Guide");
              int videoId = findIdForTitle(req, "Consistent Hashing Walkthrough");
              int practiceValidId = findIdForTitle(req, "Design Consistent Hashing");
              int practiceDeadId = findIdForTitle(req, "Dead Link Problem");
              return response(
                  AiMessage.from(
                      """
                      {"resources":[
                        {"id":%d,"type":"blog","lane":"read"},
                        {"id":%d,"type":"blog","lane":"read"},
                        {"id":%d,"type":"video","lane":"watch"},
                        {"id":%d,"type":"problem","lane":"practice"},
                        {"id":%d,"type":"problem","lane":"practice"},
                        {"id":999,"type":"docs","lane":"read"}
                      ]}
                      """
                          .formatted(webId, webId, videoId, practiceValidId, practiceDeadId)),
                  900,
                  40);
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(
            Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
            req -> {
              throw new AssertionError("structured fallback should not be invoked");
            });

    AgenticResourceFinder finder =
        new AgenticResourceFinder(structuredModel, toolModel, searchTools);

    List<ProposedResource> resources = finder.findResources(10L, "Consistent Hashing", "senior");

    // Only 2 LLM rounds and 3 searches; existingResources is never an advertised tool.
    assertThat(toolModel.requests).hasSize(2);
    assertThat(structuredModel.requests).isEmpty();
    assertThat(searchTools.searchCalls.get()).isEqualTo(3);

    ChatRequest round1 = toolModel.requests.get(0);
    assertThat(round1.toolSpecifications())
        .extracting(ToolSpecification::name)
        .containsExactlyInAnyOrder("webSearch", "searchVideos", "searchPracticeProblems");

    // Round 2 tool outputs are compact numbered plain text capped at MAX_RENDERED_RESULTS (4),
    // with markdown link/image noise stripped.
    ChatRequest round2 = toolModel.requests.get(1);
    List<ToolExecutionResultMessage> toolMessages =
        round2.messages().stream()
            .filter(ToolExecutionResultMessage.class::isInstance)
            .map(ToolExecutionResultMessage.class::cast)
            .toList();
    assertThat(toolMessages).hasSize(3);
    ToolExecutionResultMessage webMsg =
        toolMessages.stream()
            .filter(m -> m.toolName().equals("webSearch"))
            .findFirst()
            .orElseThrow();
    assertThat(webMsg.text()).contains("] Consistent Hashing Guide");
    assertThat(webMsg.text()).contains("Explains hash rings and virtual nodes.");
    assertThat(webMsg.text())
        .doesNotContain("cdn.example.com")
        .doesNotContain("Fifth Overflow Hit");
    assertThat(webMsg.text()).doesNotContain("\"snippet\"");

    // Selected IDs hydrate verbatim title, url, sourceQuery, null citation; invalid/dup/missing
    // entries are dropped.
    assertThat(resources)
        .containsExactly(
            new ProposedResource(
                ResourceType.blog,
                ResourceLane.read,
                "Consistent Hashing Guide",
                "https://highscalability.com/consistent-hashing",
                null,
                "consistent hashing tutorial virtual nodes"),
            new ProposedResource(
                ResourceType.video,
                ResourceLane.watch,
                "Consistent Hashing Walkthrough",
                "https://www.youtube.com/watch?v=abc123",
                null,
                "consistent hashing system design video"),
            new ProposedResource(
                ResourceType.problem,
                ResourceLane.practice,
                "Design Consistent Hashing",
                "https://leetcode.com/problems/design-consistent-hashing/",
                null,
                "consistent hashing coding interview exercise"));

    // Total input text sent across the whole 2-round loop stays well under budget (~1-1.5k tokens).
    int totalInputChars =
        toolModel.requests.stream().mapToInt(AgenticResourceFinderTest::estimateRequestChars).sum();
    assertThat(totalInputChars).isLessThan(7_500);
  }

  @Test
  void refreshPreInjectsExistingTitlesAndFiltersDuplicateUrlsFromSearchOutput() {
    StubSearchTools searchTools =
        new StubSearchTools(
            List.of(
                new ExistingResource(
                    "Already Saved Guide", "https://example.com/already", ResourceLane.read)),
            Map.of(
                "open",
                List.of(
                    new SearchResult(
                        "Already Saved Guide", "https://example.com/already", "Duplicate hit."),
                    new SearchResult(
                        "Fresh Caching Article", "https://example.com/fresh", "Fresh hit.")),
                "video",
                List.of(),
                "practice",
                List.of()));

    AtomicInteger round = new AtomicInteger();
    ScriptedChatModel toolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              if (round.getAndIncrement() == 0) {
                return response(
                    AiMessage.from(toolReq("c1", "webSearch", "caching deep dive")), 100, 30);
              }
              return response(
                  AiMessage.from(
                      "{\"resources\":[{\"id\":1,\"type\":\"blog\",\"lane\":\"read\"}]}"),
                  300,
                  20);
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA), req -> null);

    AgenticResourceFinder finder =
        new AgenticResourceFinder(structuredModel, toolModel, searchTools);

    List<ProposedResource> resources = finder.findResources(42L, "Caching", "mid");

    UserMessage round1User =
        (UserMessage)
            toolModel.requests.get(0).messages().stream()
                .filter(UserMessage.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(round1User.singleText())
        .contains("Already saved (do not repeat):")
        .contains("- [read] Already Saved Guide");

    ToolExecutionResultMessage webMsg =
        (ToolExecutionResultMessage)
            toolModel.requests.get(1).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(webMsg.text()).doesNotContain("https://example.com/already");
    assertThat(webMsg.text()).contains("[1] Fresh Caching Article");

    assertThat(resources)
        .extracting(ProposedResource::url)
        .containsExactly("https://example.com/fresh");
  }

  @Test
  void recoversInPlaceViaStructuredSelectorWhenToolUseFailedAfterCollectingCandidates() {
    StubSearchTools searchTools =
        new StubSearchTools(
            List.of(),
            Map.of(
                "open",
                List.of(
                    new SearchResult(
                        "Load Balancing Basics", "https://example.com/lb", "Good explainer.")),
                "video",
                List.of(),
                "practice",
                List.of()));

    AtomicInteger round = new AtomicInteger();
    ScriptedChatModel toolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              if (round.getAndIncrement() == 0) {
                return response(
                    AiMessage.from(toolReq("c1", "webSearch", "load balancing explainer")),
                    100,
                    30);
              }
              throw new InvalidRequestException("tool_use_failed: invalid tool 'JSON'");
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(
            Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
            req ->
                response(
                    AiMessage.from(
                        "{\"resources\":[{\"id\":1,\"type\":\"blog\",\"lane\":\"read\"}]}"),
                    250,
                    25));

    AgenticResourceFinder finder =
        new AgenticResourceFinder(structuredModel, toolModel, searchTools);

    List<ProposedResource> resources = finder.findResources(7L, "Load Balancing", "mid");

    assertThat(toolModel.requests).hasSize(2);
    assertThat(structuredModel.requests).hasSize(1);
    assertThat(searchTools.searchCalls.get()).isEqualTo(1);
    assertThat(resources)
        .extracting(ProposedResource::url)
        .containsExactly("https://example.com/lb");
  }

  @Test
  void recoversInPlaceViaStructuredSelectorOnMalformedJsonOrMaxRoundTrips() {
    StubSearchTools searchTools =
        new StubSearchTools(
            List.of(),
            Map.of(
                "open",
                List.of(
                    new SearchResult("Sharding Guide", "https://example.com/sharding", "Guide.")),
                "video",
                List.of(),
                "practice",
                List.of()));

    // Case 1: malformed JSON output triggers OutputParsingException
    AtomicInteger parseRound = new AtomicInteger();
    ScriptedChatModel badJsonToolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              if (parseRound.getAndIncrement() == 0) {
                return response(
                    AiMessage.from(toolReq("c1", "webSearch", "database sharding guide")), 100, 30);
              }
              return response(AiMessage.from("not valid json at all"), 200, 20);
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(
            Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
            req ->
                response(
                    AiMessage.from(
                        "{\"resources\":[{\"id\":1,\"type\":\"docs\",\"lane\":\"read\"}]}"),
                    200,
                    20));

    List<ProposedResource> parsedFallback =
        new AgenticResourceFinder(structuredModel, badJsonToolModel, searchTools)
            .findResources(8L, "Sharding", "mid");
    assertThat(structuredModel.requests).hasSize(1);
    assertThat(searchTools.searchCalls.get()).isEqualTo(1);
    assertThat(parsedFallback)
        .extracting(ProposedResource::url)
        .containsExactly("https://example.com/sharding");

    // Case 2: model keeps requesting tools past MAX_ROUND_TRIPS
    StubSearchTools secondSearchTools =
        new StubSearchTools(
            List.of(),
            Map.of(
                "open",
                List.of(
                    new SearchResult("Sharding Guide", "https://example.com/sharding", "Guide.")),
                "video",
                List.of(),
                "practice",
                List.of()));
    AtomicInteger loopSeq = new AtomicInteger();
    ScriptedChatModel loopingToolModel =
        new ScriptedChatModel(
            Set.of(),
            req ->
                response(
                    AiMessage.from(
                        toolReq(
                            "c" + loopSeq.incrementAndGet(),
                            "webSearch",
                            "query " + loopSeq.get())),
                    100,
                    20));
    ScriptedChatModel secondStructuredModel =
        new ScriptedChatModel(
            Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
            req ->
                response(
                    AiMessage.from(
                        "{\"resources\":[{\"id\":1,\"type\":\"docs\",\"lane\":\"read\"}]}"),
                    200,
                    20));

    List<ProposedResource> roundTripFallback =
        new AgenticResourceFinder(secondStructuredModel, loopingToolModel, secondSearchTools)
            .findResources(9L, "Sharding", "mid");
    assertThat(secondStructuredModel.requests).hasSize(1);
    assertThat(roundTripFallback)
        .extracting(ProposedResource::url)
        .containsExactly("https://example.com/sharding");
  }

  @Test
  void toolUseFailedBeforeAnySearchRunsRemainsRetryableWithoutFallback() {
    StubSearchTools searchTools = new StubSearchTools(List.of(), Map.of());
    ScriptedChatModel toolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              throw new InvalidRequestException("tool_use_failed on turn 1");
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(
            Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA),
            req -> {
              throw new AssertionError("fallback must not run without candidates");
            });

    AgenticResourceFinder finder =
        new AgenticResourceFinder(structuredModel, toolModel, searchTools);

    assertThatThrownBy(() -> finder.findResources(11L, "Indexing", "mid"))
        .isInstanceOf(RetryableEnrichmentException.class);
    assertThat(structuredModel.requests).isEmpty();
  }

  @Test
  void enforcesHardCapOnSearchesPerRun() {
    StubSearchTools searchTools =
        new StubSearchTools(
            List.of(),
            Map.of(
                "open",
                List.of(new SearchResult("Hit", "https://example.com/1", "Snippet.")),
                "video",
                List.of(new SearchResult("Vid", "https://youtube.com/watch?v=1", "Video.")),
                "practice",
                List.of(new SearchResult("Prob", "https://leetcode.com/problems/a", "Problem."))));

    AtomicInteger round = new AtomicInteger();
    ScriptedChatModel toolModel =
        new ScriptedChatModel(
            Set.of(),
            req -> {
              if (round.getAndIncrement() == 0) {
                return response(
                    AiMessage.from(
                        List.of(
                            toolReq("c1", "webSearch", "q1"),
                            toolReq("c2", "searchVideos", "q2"),
                            toolReq("c3", "searchPracticeProblems", "q3"),
                            toolReq("c4", "webSearch", "q4"),
                            toolReq("c5", "searchVideos", "q5"),
                            toolReq("c6", "searchPracticeProblems", "q6"))),
                    100,
                    60);
              }
              return response(
                  AiMessage.from(
                      "{\"resources\":[{\"id\":1,\"type\":\"blog\",\"lane\":\"read\"}]}"),
                  400,
                  20);
            });
    ScriptedChatModel structuredModel =
        new ScriptedChatModel(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA), req -> null);

    new AgenticResourceFinder(structuredModel, toolModel, searchTools)
        .findResources(12L, "Graphs", "mid");

    assertThat(searchTools.searchCalls.get()).isEqualTo(AgenticResourceFinder.MAX_SEARCHES_PER_RUN);
    List<ToolExecutionResultMessage> toolResults =
        toolModel.requests.get(1).messages().stream()
            .filter(ToolExecutionResultMessage.class::isInstance)
            .map(ToolExecutionResultMessage.class::cast)
            .toList();
    assertThat(toolResults)
        .anySatisfy(msg -> assertThat(msg.text()).contains("Search budget reached"));
  }

  private static int findIdForTitle(ChatRequest request, String title) {
    Pattern pattern = Pattern.compile("\\[(\\d+)\\] " + Pattern.quote(title));
    for (ChatMessage msg : request.messages()) {
      if (msg instanceof ToolExecutionResultMessage toolMsg) {
        Matcher m = pattern.matcher(toolMsg.text());
        if (m.find()) {
          return Integer.parseInt(m.group(1));
        }
      }
    }
    throw new AssertionError("No candidate ID found for title: " + title);
  }

  private static ToolExecutionRequest toolReq(String id, String name, String query) {
    return ToolExecutionRequest.builder()
        .id(id)
        .name(name)
        .arguments("{\"query\":\"" + query + "\",\"type\":\"AUTO\"}")
        .build();
  }

  private static ChatResponse response(AiMessage message, int inputTokens, int outputTokens) {
    return ChatResponse.builder()
        .aiMessage(message)
        .tokenUsage(new TokenUsage(inputTokens, outputTokens))
        .build();
  }

  private static int estimateRequestChars(ChatRequest request) {
    int total = 0;
    for (ChatMessage msg : request.messages()) {
      switch (msg) {
        case SystemMessage sys -> total += sys.text().length();
        case UserMessage user -> total += user.singleText().length();
        case ToolExecutionResultMessage tool -> total += tool.text().length();
        case AiMessage ai -> total += ai.text() != null ? ai.text().length() : 0;
        default -> {}
      }
    }
    if (request.toolSpecifications() != null) {
      for (ToolSpecification spec : request.toolSpecifications()) {
        total += spec.name().length();
        if (spec.description() != null) {
          total += spec.description().length();
        }
        if (spec.parameters() != null) {
          total += spec.parameters().toString().length();
        }
      }
    }
    return total;
  }

  private static class ScriptedChatModel implements ChatModel {

    private final Set<Capability> capabilities;
    private final Function<ChatRequest, ChatResponse> responder;
    final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    ScriptedChatModel(Set<Capability> capabilities, Function<ChatRequest, ChatResponse> responder) {
      this.capabilities = capabilities;
      this.responder = responder;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
      requests.add(request);
      return responder.apply(request);
    }

    @Override
    public Set<Capability> supportedCapabilities() {
      return capabilities;
    }
  }

  private static class StubSearchTools implements SearchTools {

    private final List<ExistingResource> existing;
    private final Map<String, List<SearchResult>> resultsByBucket;
    final Set<String> invalidUrls = new java.util.HashSet<>();
    final AtomicInteger searchCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> bucketCalls = new ConcurrentHashMap<>();

    StubSearchTools(
        List<ExistingResource> existing, Map<String, List<SearchResult>> resultsByBucket) {
      this.existing = existing;
      this.resultsByBucket = resultsByBucket;
    }

    @Override
    public List<SearchResult> search(String query, SearchType type, List<String> includeDomains) {
      searchCalls.incrementAndGet();
      String bucket =
          includeDomains == null || includeDomains.isEmpty()
              ? "open"
              : includeDomains.contains("youtube.com") ? "video" : "practice";
      int callNum = bucketCalls.computeIfAbsent(bucket, k -> new AtomicInteger()).getAndIncrement();
      List<SearchResult> list = resultsByBucket.getOrDefault(bucket, List.of());
      if (callNum == 0) {
        return list;
      }
      // Return distinct URLs on subsequent calls in loops so each call yields fresh hits.
      List<SearchResult> next = new ArrayList<>(list.size());
      for (SearchResult r : list) {
        next.add(new SearchResult(r.title(), r.url() + "?attempt=" + callNum, r.snippet()));
      }
      return next;
    }

    @Override
    public boolean validateUrl(String url) {
      return !invalidUrls.contains(url);
    }

    @Override
    public List<ExistingResource> existingResourcesFor(Long topicId) {
      return existing;
    }
  }
}
