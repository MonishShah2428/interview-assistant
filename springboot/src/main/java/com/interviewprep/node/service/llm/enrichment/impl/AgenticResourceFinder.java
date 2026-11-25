package com.interviewprep.node.service.llm.enrichment.impl;

import com.interviewprep.node.service.llm.enrichment.ExistingResource;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.ResourceFinder;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import com.interviewprep.node.service.llm.enrichment.SearchType;
import com.interviewprep.node.service.llm.enrichment.impl.ResourceFinderAi.SelectedResource;
import com.interviewprep.node.service.llm.enrichment.impl.ResourceFinderAi.SelectedResourceList;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.OutputParsingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * The only autonomous piece of enrichment: loops over {@link SearchTools} via a LangChain4j
 * tool-calling {@code AiServices} instance built fresh per call (not a Spring-managed
 * {@code @AiService}, so {@link #MAX_ROUND_TRIPS} can be enforced — see {@link ResourceFinderAi}).
 * Bounded in iterations ({@link #MAX_ROUND_TRIPS}), searches ({@link #MAX_SEARCHES_PER_RUN}), and
 * wall-clock time ({@link #MAX_ELAPSED_SECONDS}).
 *
 * <p>To fit Groq's free tier (8,000 TPM / 200,000 TPD), existing resources are fetched once before
 * the loop (rather than exposing an {@code existingResources} tool), tool calls run in parallel and
 * return compact ID-indexed plain text, and final selection returns only {@code {id, type, lane}}
 * hydrated verbatim from recorded search hits. If the agent fails to finalize after already
 * collecting candidates (e.g. {@code tool_use_failed}, malformed JSON, or max round trips), {@link
 * ResourceSelectorAi} completes the selection in one schema-enforced call instead of restarting
 * searches from round 1.
 */
@Component
class AgenticResourceFinder implements ResourceFinder {

  private static final Logger log = LoggerFactory.getLogger(AgenticResourceFinder.class);

  private static final int MAX_ROUND_TRIPS = 5;
  // Leaves room for RateLimitRetryingChatModel to wait out a few Groq rate limits (each the time
  // Groq asks for plus 4s) mid-loop; timing out during a wait would restart the search from round
  // 1.
  private static final int MAX_ELAPSED_SECONDS = 120;
  private static final int MAX_RESOURCES = 6;
  static final int MAX_SEARCHES_PER_RUN = 5;
  static final int MAX_RENDERED_RESULTS = 4;
  static final int WEB_SNIPPET_CHARS = 250;
  static final int MEDIA_SNIPPET_CHARS = 160;

  // Tunable defaults, not exhaustive — the video/practice tools hard-filter to these via Exa's
  // includeDomains, so a topic whose only good resource lives on a site outside this list simply
  // won't get one from that tool; the LLM still has webSearch (open web) as a fallback.
  private static final List<String> VIDEO_DOMAINS = List.of("youtube.com");
  private static final List<String> PRACTICE_DOMAINS =
      List.of(
          "leetcode.com", "neetcode.io", "interviewing.io", "hellointerview.com", "educative.io");

  private final ChatModel structuredChatModel;
  private final ChatModel toolCallingChatModel;
  private final SearchTools searchTools;

  AgenticResourceFinder(
      ChatModel structuredChatModel,
      @Qualifier("toolCallingChatModel") ChatModel toolCallingChatModel,
      SearchTools searchTools) {
    this.structuredChatModel = structuredChatModel;
    this.toolCallingChatModel = toolCallingChatModel;
    this.searchTools = searchTools;
  }

  @Override
  public List<ProposedResource> findResources(Long topicId, String topicLabel, String level) {
    List<ExistingResource> rawExisting = searchTools.existingResourcesFor(topicId);
    List<ExistingResource> existing = rawExisting == null ? List.of() : rawExisting;
    String existingSection = formatExistingSection(existing);

    ResourceSearchToolAdapter adapter = new ResourceSearchToolAdapter(searchTools, existing);
    ExecutorService toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
    Result<SelectedResourceList> result;
    boolean usedFallback = false;
    try {
      ResourceFinderAi ai =
          AiServices.builder(ResourceFinderAi.class)
              .chatModel(toolCallingChatModel)
              .tools(adapter)
              .executeToolsConcurrently(toolExecutor)
              .maxToolCallingRoundTrips(MAX_ROUND_TRIPS)
              .build();

      try {
        result = callWithTimeout(() -> ai.find(topicLabel, level, existingSection));
      } catch (RuntimeException failure) {
        if (isRecoverableFinalizationFailure(failure) && adapter.hasCandidates()) {
          log.warn(
              "Resource finder hit {} for topic '{}' after collecting {} candidates; falling back"
                  + " to structured selector",
              failure.getClass().getSimpleName(),
              topicLabel,
              adapter.candidateCount(),
              failure);
          usedFallback = true;
          ResourceSelectorAi selector =
              AiServices.builder(ResourceSelectorAi.class).chatModel(structuredChatModel).build();
          result =
              callWithTimeout(
                  () ->
                      selector.select(
                          topicLabel, level, existingSection, adapter.formatAllCandidates()));
        } else if (isMalformedToolCall(failure)) {
          throw new RetryableEnrichmentException("model made a malformed tool call", failure);
        } else {
          throw failure;
        }
      }
    } finally {
      toolExecutor.shutdownNow();
    }

    SelectedResourceList list = result != null ? result.content() : null;
    List<SelectedResource> picks =
        list != null && list.resources() != null ? list.resources() : List.of();
    List<ProposedResource> proposals = hydrateAndValidate(picks, adapter);

    logSummary(topicLabel, result, adapter, proposals.size(), usedFallback);
    return proposals;
  }

  private List<ProposedResource> hydrateAndValidate(
      List<SelectedResource> selected, ResourceSearchToolAdapter adapter) {
    if (selected == null || selected.isEmpty()) {
      return List.of();
    }
    Set<Integer> usedIds = new HashSet<>();
    Set<String> usedUrls = new HashSet<>();
    List<ProposedResource> validated = new ArrayList<>();
    for (SelectedResource pick : selected) {
      if (pick == null || pick.type() == null || pick.lane() == null) {
        continue;
      }
      if (!usedIds.add(pick.id())) {
        continue;
      }
      Candidate candidate = adapter.candidate(pick.id());
      if (candidate == null || !usedUrls.add(candidate.url())) {
        continue;
      }
      // URLs are checked here, after the loop, not as a model tool: each tool call is another
      // round trip that resends the whole conversation.
      if (!searchTools.validateUrl(candidate.url())) {
        continue;
      }
      validated.add(
          new ProposedResource(
              pick.type(),
              pick.lane(),
              candidate.title(),
              candidate.url(),
              null,
              candidate.sourceQuery()));
      if (validated.size() >= MAX_RESOURCES) {
        break;
      }
    }
    return List.copyOf(validated);
  }

  private <T> T callWithTimeout(Supplier<T> call) {
    CompletableFuture<T> future = CompletableFuture.supplyAsync(call);
    try {
      return future.orTimeout(MAX_ELAPSED_SECONDS, TimeUnit.SECONDS).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof TimeoutException) {
        future.cancel(true);
        throw new RetryableEnrichmentException(
            "resource finder exceeded " + MAX_ELAPSED_SECONDS + "s", cause);
      }
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new NonRetryableEnrichmentException("resource finder failed", cause);
    }
  }

  private static String formatExistingSection(List<ExistingResource> existing) {
    if (existing.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (ExistingResource r : existing) {
      if (r != null && r.title() != null && !r.title().isBlank()) {
        if (sb.isEmpty()) {
          sb.append("\nAlready saved (do not repeat):");
        }
        sb.append("\n- [").append(r.lane()).append("] ").append(r.title().strip());
      }
    }
    return sb.toString();
  }

  private static void logSummary(
      String topicLabel,
      Result<SelectedResourceList> result,
      ResourceSearchToolAdapter adapter,
      int returned,
      boolean usedFallback) {
    int rounds =
        result != null && result.intermediateResponses() != null
            ? result.intermediateResponses().size() + 1
            : 1;
    TokenUsage usage = result != null ? result.tokenUsage() : null;
    Integer in = usage != null ? usage.inputTokenCount() : null;
    Integer out = usage != null ? usage.outputTokenCount() : null;
    Integer total = usage != null ? usage.totalTokenCount() : null;
    Integer cached = null;
    Integer reasoning = null;
    if (usage instanceof OpenAiTokenUsage openAiUsage) {
      if (openAiUsage.inputTokensDetails() != null) {
        cached = openAiUsage.inputTokensDetails().cachedTokens();
      }
      if (openAiUsage.outputTokensDetails() != null) {
        reasoning = openAiUsage.outputTokensDetails().reasoningTokens();
      }
    }
    log.info(
        "ResourceFinder summary topic='{}' rounds={} searches={} candidates={} returned={}"
            + " fallback={} tokens(in={}, cached={}, out={}, reasoning={}, total={})",
        topicLabel,
        rounds,
        adapter.searchesExecuted(),
        adapter.candidateCount(),
        returned,
        usedFallback,
        in,
        cached,
        out,
        reasoning,
        total);
  }

  private static boolean isRecoverableFinalizationFailure(Throwable t) {
    return isMalformedToolCall(t) || isOutputParsingFailure(t) || isMaxRoundTripsExceeded(t);
  }

  /**
   * gpt-oss sometimes sends its final answer as a call to a tool named "JSON" that doesn't exist,
   * and Groq rejects the whole request ({@code tool_use_failed}).
   */
  private static boolean isMalformedToolCall(Throwable t) {
    return hasCauseMatching(
        t,
        cause ->
            cause instanceof InvalidRequestException
                && cause.getMessage() != null
                && cause.getMessage().contains("tool_use_failed"));
  }

  private static boolean isOutputParsingFailure(Throwable t) {
    return hasCauseMatching(t, cause -> cause instanceof OutputParsingException);
  }

  private static boolean isMaxRoundTripsExceeded(Throwable t) {
    return hasCauseMatching(
        t,
        cause ->
            cause.getMessage() != null && cause.getMessage().contains("maxToolCallingRoundTrips"));
  }

  private static boolean hasCauseMatching(Throwable t, Predicate<Throwable> predicate) {
    Throwable current = t;
    for (int depth = 0; current != null && depth < 16; depth++) {
      if (predicate.test(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  record Candidate(int id, String title, String url, String sourceQuery, String snippet) {

    String formatEntry() {
      StringBuilder sb = new StringBuilder();
      sb.append('[').append(id).append("] ").append(title).append('\n').append(url);
      if (!snippet.isEmpty()) {
        sb.append('\n').append(snippet);
      }
      return sb.toString();
    }
  }

  /**
   * Built fresh per {@link #findResources} call to track ID-indexed candidates, deduplicate URLs
   * against existing resources and prior searches, and enforce {@link #MAX_SEARCHES_PER_RUN}.
   */
  static class ResourceSearchToolAdapter {

    private final SearchTools searchTools;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicInteger searchCount = new AtomicInteger(0);
    private final ConcurrentSkipListMap<Integer, Candidate> candidatesById =
        new ConcurrentSkipListMap<>();
    private final Set<String> seenUrls = ConcurrentHashMap.newKeySet();

    ResourceSearchToolAdapter(SearchTools searchTools, List<ExistingResource> existing) {
      this.searchTools = searchTools;
      if (existing != null) {
        for (ExistingResource r : existing) {
          if (r != null && r.url() != null && !r.url().isBlank()) {
            seenUrls.add(r.url());
          }
        }
      }
    }

    @Tool("Search open web for tutorials, docs, or blog articles (read lane).")
    public String webSearch(
        @P("natural-language query") String query, @P("AUTO, FAST, or INSTANT") SearchType type) {
      return runSearch(query, type, List.of(), WEB_SNIPPET_CHARS);
    }

    @Tool("Search trusted video platforms for video walkthroughs (watch lane).")
    public String searchVideos(
        @P("natural-language query") String query, @P("AUTO, FAST, or INSTANT") SearchType type) {
      return runSearch(query, type, VIDEO_DOMAINS, MEDIA_SNIPPET_CHARS);
    }

    @Tool("Search coding/interview sites for practice problems (practice lane).")
    public String searchPracticeProblems(
        @P("natural-language query") String query, @P("AUTO, FAST, or INSTANT") SearchType type) {
      return runSearch(query, type, PRACTICE_DOMAINS, MEDIA_SNIPPET_CHARS);
    }

    private String runSearch(
        String query, SearchType type, List<String> includeDomains, int maxSnippetChars) {
      if (searchCount.incrementAndGet() > MAX_SEARCHES_PER_RUN) {
        return "Search budget reached. Pick the best resources from results already returned.";
      }
      String safeQuery = query == null ? "" : query.strip();
      SearchType effectiveType = type != null ? type : SearchType.AUTO;
      List<SearchResult> results = searchTools.search(safeQuery, effectiveType, includeDomains);
      if (results == null || results.isEmpty()) {
        return "No new results.";
      }

      StringBuilder sb = new StringBuilder();
      int rendered = 0;
      for (SearchResult r : results) {
        if (rendered >= MAX_RENDERED_RESULTS) {
          break;
        }
        if (r == null || r.url() == null || r.url().isBlank()) {
          continue;
        }
        if (!seenUrls.add(r.url())) {
          continue;
        }
        int id = nextId.getAndIncrement();
        String title = r.title() != null && !r.title().isBlank() ? r.title().strip() : r.url();
        String snippet = formatSnippet(r.snippet(), maxSnippetChars);
        Candidate candidate = new Candidate(id, title, r.url(), safeQuery, snippet);
        candidatesById.put(id, candidate);

        if (rendered > 0) {
          sb.append("\n\n");
        }
        sb.append(candidate.formatEntry());
        rendered++;
      }
      return rendered == 0 ? "No new results." : sb.toString();
    }

    private static String formatSnippet(String rawSnippet, int maxChars) {
      String cleaned = ExaSearchTools.cleanSnippet(rawSnippet);
      return cleaned.isEmpty() ? "" : ExaSearchTools.truncate(cleaned, maxChars);
    }

    boolean hasCandidates() {
      return !candidatesById.isEmpty();
    }

    int candidateCount() {
      return candidatesById.size();
    }

    int searchesExecuted() {
      return Math.min(searchCount.get(), MAX_SEARCHES_PER_RUN);
    }

    Candidate candidate(int id) {
      return candidatesById.get(id);
    }

    String formatAllCandidates() {
      StringBuilder sb = new StringBuilder();
      for (Candidate c : candidatesById.values()) {
        if (!sb.isEmpty()) {
          sb.append("\n\n");
        }
        sb.append(c.formatEntry());
      }
      return sb.toString();
    }
  }
}
