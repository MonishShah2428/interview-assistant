package com.interviewprep.node.service.llm.enrichment.impl;

import com.interviewprep.node.service.llm.enrichment.ExistingResource;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import com.interviewprep.node.service.llm.enrichment.ResourceFinder;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * The only autonomous piece of enrichment: loops over {@link SearchTools} via a LangChain4j
 * tool-calling {@code AiServices} instance built fresh per call (not a Spring-managed
 * {@code @AiService}, so {@link #MAX_ROUND_TRIPS} can be enforced — see {@link ResourceFinderAi}).
 * Bounded in both iterations ({@code maxToolCallingRoundTrips}) and wall-clock time ({@link
 * #MAX_ELAPSED_SECONDS}), independent bounds because a slow individual model/tool call and a long
 * back-and-forth are different failure shapes.
 */
@Component
class AgenticResourceFinder implements ResourceFinder {

  private static final int MAX_ROUND_TRIPS = 8;
  private static final int MAX_ELAPSED_SECONDS = 45;
  private static final int MAX_RESOURCES = 6;

  private final ChatModel chatModel;
  private final SearchTools searchTools;

  AgenticResourceFinder(ChatModel chatModel, SearchTools searchTools) {
    this.chatModel = chatModel;
    this.searchTools = searchTools;
  }

  @Override
  public List<ProposedResource> findResources(Long topicId, String topicLabel, String level) {
    ResourceSearchToolAdapter adapter = new ResourceSearchToolAdapter(searchTools, topicId);
    ResourceFinderAi ai =
        AiServices.builder(ResourceFinderAi.class)
            .chatModel(chatModel)
            .tools(adapter)
            .maxToolCallingRoundTrips(MAX_ROUND_TRIPS)
            .build();

    List<ProposedResource> proposals = callWithTimeout(() -> ai.find(topicLabel, level));

    return proposals.stream()
        .filter(p -> searchTools.validateUrl(p.url())) // defensive re-check, never trust the
        // model's own claim that it validated a URL
        .limit(MAX_RESOURCES)
        .toList();
  }

  private List<ProposedResource> callWithTimeout(
      java.util.function.Supplier<List<ProposedResource>> call) {
    CompletableFuture<List<ProposedResource>> future = CompletableFuture.supplyAsync(call);
    try {
      return future.orTimeout(MAX_ELAPSED_SECONDS, TimeUnit.SECONDS).join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      if (cause instanceof TimeoutException) {
        throw new RetryableEnrichmentException(
            "resource finder exceeded " + MAX_ELAPSED_SECONDS + "s", cause);
      }
      if (cause instanceof RuntimeException re) {
        throw re;
      }
      throw new NonRetryableEnrichmentException("resource finder failed", cause);
    }
  }

  /**
   * Built fresh per {@link #findResources} call so {@code topicId} is bound correctly even though
   * {@link SearchTools} itself is a shared singleton — the underlying model/tool-calling loop has
   * no other way to learn which topic {@code existingResources} should scope to.
   */
  static class ResourceSearchToolAdapter {

    private final SearchTools searchTools;
    private final Long topicId;

    ResourceSearchToolAdapter(SearchTools searchTools, Long topicId) {
      this.searchTools = searchTools;
      this.topicId = topicId;
    }

    @Tool("Search the web for learning resources on a query.")
    public List<SearchResult> webSearch(@P("search query") String query) {
      return searchTools.search(query);
    }

    @Tool("Check whether a URL actually resolves before including it in results.")
    public boolean validateUrl(@P("URL to check") String url) {
      return searchTools.validateUrl(url);
    }

    @Tool("List resources this topic already has, so you don't re-propose them on a refresh.")
    public List<ExistingResource> existingResources() {
      return searchTools.existingResourcesFor(topicId);
    }
  }
}
