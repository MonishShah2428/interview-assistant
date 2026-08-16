package com.interviewprep.node.service.llm.enrichment.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.interviewprep.node.repository.ResourceRepository;
import com.interviewprep.node.service.llm.HostRateLimiter;
import com.interviewprep.node.service.llm.enrichment.ExistingResource;
import com.interviewprep.node.service.llm.enrichment.NonRetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.RetryableEnrichmentException;
import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
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
 * Wraps Tavily for {@link #search} and does its own HEAD/ranged-GET resolution for {@link
 * #validateUrl}. One host-rate-limited {@link RestClient} handles both — absolute URIs are passed
 * per-request rather than binding a base URL, since {@link #validateUrl} targets arbitrary hosts.
 */
@Component
class TavilySearchTools implements SearchTools {

  private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";
  private static final String TAVILY_HOST = "api.tavily.com";
  private static final int MAX_RESULTS = 8;

  private final RestClient restClient;
  private final String apiKey;
  private final ResourceRepository resourceRepository;
  private final HostRateLimiter hostRateLimiter;

  TavilySearchTools(
      RestClient.Builder restClientBuilder,
      @Value("${tavily.api-key}") String apiKey,
      ResourceRepository resourceRepository,
      HostRateLimiter hostRateLimiter) {
    this.restClient = restClientBuilder.build();
    this.apiKey = apiKey;
    this.resourceRepository = resourceRepository;
    this.hostRateLimiter = hostRateLimiter;
  }

  @Override
  public List<SearchResult> search(String query) {
    hostRateLimiter.acquire(TAVILY_HOST);
    TavilyResponse response;
    try {
      response =
          restClient
              .post()
              .uri(TAVILY_SEARCH_URL)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new TavilyRequest(apiKey, query, MAX_RESULTS))
              .retrieve()
              .body(TavilyResponse.class);
    } catch (HttpClientErrorException.TooManyRequests e) {
      throw new RetryableEnrichmentException("tavily rate-limited the search request", e);
    } catch (HttpClientErrorException e) {
      throw new NonRetryableEnrichmentException("tavily rejected the search request", e);
    } catch (RestClientException e) {
      throw new RetryableEnrichmentException("tavily search failed", e);
    }
    if (response == null || response.results() == null) {
      return List.of();
    }
    return response.results().stream()
        .map(r -> new SearchResult(r.title(), r.url(), r.content()))
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

  private record TavilyRequest(
      @JsonProperty("api_key") String apiKey,
      @JsonProperty("query") String query,
      @JsonProperty("max_results") int maxResults) {}

  private record TavilyResponse(List<TavilyResult> results) {}

  private record TavilyResult(String title, String url, String content) {}
}
