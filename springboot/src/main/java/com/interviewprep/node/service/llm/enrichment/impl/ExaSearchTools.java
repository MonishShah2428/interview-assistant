package com.interviewprep.node.service.llm.enrichment.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.service.llm.HostRateLimiter;
import com.interviewprep.node.service.llm.enrichment.ExistingResource;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import com.interviewprep.node.service.llm.enrichment.SearchType;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Wraps Exa search for {@link #search} and does its own HEAD/ranged-GET resolution for {@link
 * #validateUrl}. One host-rate-limited {@link RestClient} handles both — absolute URIs are passed
 * per-request rather than binding a base URL, since {@link #validateUrl} targets arbitrary hosts.
 *
 * <p>Requests {@code contents.highlights} rather than {@code contents.text} — Exa's own guidance
 * for agent tool-calling is to prefer highlights (query-relevant excerpts) over full page text,
 * since the agentic resource-finder re-feeds every search result into the model's context on each
 * tool-calling round trip, and highlights run roughly 10x fewer tokens than full text for
 * comparable relevance (see
 * https://docs.exa.ai/reference/search-api-guide-for-coding-agents#content-configuration).
 *
 * <p>{@code includeDomains} is Exa's hard domain filter, sent only when non-empty — {@code
 * useAutoprompt} is deliberately never sent; Exa's own docs call it deprecated and a no-op.
 */
@Component
class ExaSearchTools implements SearchTools {

  private static final String EXA_SEARCH_URL = "https://api.exa.ai/search";
  private static final String EXA_HOST = "api.exa.ai";
  private static final int MAX_RESULTS = 8;

  private final RestClient restClient;
  private final String apiKey;
  private final ResourceRepository resourceRepository;
  private final HostRateLimiter hostRateLimiter;

  ExaSearchTools(
      RestClient.Builder restClientBuilder,
      @Value("${exa.api-key}") String apiKey,
      ResourceRepository resourceRepository,
      HostRateLimiter hostRateLimiter) {
    this.restClient = restClientBuilder.build();
    this.apiKey = apiKey;
    this.resourceRepository = resourceRepository;
    this.hostRateLimiter = hostRateLimiter;
  }

  @Override
  public List<SearchResult> search(String query, SearchType type, List<String> includeDomains) {
    hostRateLimiter.acquire(EXA_HOST);
    ExaResponse response;
    try {
      response =
          restClient
              .post()
              .uri(EXA_SEARCH_URL)
              .header("x-api-key", apiKey)
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  new ExaRequest(
                      query, type.exaValue(), MAX_RESULTS, includeDomains, new ExaContents(true)))
              .retrieve()
              .body(ExaResponse.class);
    } catch (HttpClientErrorException.TooManyRequests e) {
      throw new RetryableEnrichmentException("exa rate-limited the search request", e);
    } catch (HttpClientErrorException e) {
      throw new NonRetryableEnrichmentException("exa rejected the search request", e);
    } catch (RestClientException e) {
      throw new RetryableEnrichmentException("exa search failed", e);
    }
    if (response == null || response.results() == null) {
      return List.of();
    }
    return response.results().stream()
        .map(
            r ->
                new SearchResult(
                    r.title(),
                    r.url(),
                    r.highlights() == null ? "" : String.join(" ", r.highlights())))
        .toList();
  }

  @Override
  public boolean validateUrl(String url) {
    String host = extractHost(url);
    if (host == null) {
      return false;
    }
    hostRateLimiter.acquire(host);
    try {
      restClient.head().uri(url).retrieve().toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.MethodNotAllowed methodNotAllowed) {
      hostRateLimiter.acquire(host);
      try {
        restClient
            .get()
            .uri(url)
            .header(HttpHeaders.RANGE, "bytes=0-0")
            .retrieve()
            .toBodilessEntity();
        return true;
      } catch (RestClientException rangedGetFailed) {
        return false;
      }
    } catch (RestClientException headFailed) {
      return false; // timeout, DNS failure, non-2xx other than 405 — not resolvable, not a service
      // error
    }
  }

  @Override
  public List<ExistingResource> existingResourcesFor(Long topicId) {
    return resourceRepository.findByTopicId(topicId).stream()
        .map(r -> new ExistingResource(r.getTitle(), r.getUrl(), r.getLane()))
        .toList();
  }

  private static String extractHost(String url) {
    try {
      return URI.create(url).getHost();
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  private record ExaRequest(
      String query,
      String type,
      int numResults,
      List<String> includeDomains,
      ExaContents contents) {}

  private record ExaContents(boolean highlights) {}

  private record ExaResponse(List<ExaResult> results) {}

  private record ExaResult(String title, String url, List<String> highlights) {}
}
