package com.interviewprep.node.service.llm.enrichment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import com.interviewprep.node.service.llm.enrichment.SearchType;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the real Exa {@code /search} call actually returns usable resources — decoupled from
 * {@link AgenticResourceFinder}/Groq entirely, so it isn't at the mercy of the shared Groq
 * account's token-per-minute budget the way a full agentic-loop test is. Runs only when {@code
 * EXA_API_KEY} is a real key (skipped, not failed, otherwise) since it makes a genuine network call
 * to Exa and costs a real search credit each run.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EXA_API_KEY", matches = "(?!not-set).+")
class ExaSearchToolsLiveTest {

  // Mirrors AgenticResourceFinder's VIDEO_DOMAINS/PRACTICE_DOMAINS — duplicated here rather than
  // shared so this test doesn't need production visibility changes just to read a literal.
  private static final List<String> VIDEO_DOMAINS = List.of("youtube.com");
  private static final List<String> PRACTICE_DOMAINS =
      List.of(
          "leetcode.com", "neetcode.io", "interviewing.io", "hellointerview.com", "educative.io");

  @Autowired private SearchTools searchTools;

  @Test
  void searchReturnsRealResultsForATopicQuery() {
    List<SearchResult> results =
        searchTools.search("CAP theorem distributed systems", SearchType.AUTO, List.of());

    assertThat(results).isNotEmpty();
    assertThat(results)
        .allSatisfy(
            r -> {
              assertThat(r.title()).isNotBlank();
              assertThat(r.url()).startsWith("http");
              // snippet comes from Exa's "highlights" field, which can legitimately be empty for
              // a given result even when title/url are populated — not asserted non-blank here.
            });
  }

  @Test
  void searchStrategyChangesTheRequestWithoutErroring() {
    List<SearchResult> fast =
        searchTools.search("linearizability vs sequential consistency", SearchType.FAST, List.of());
    List<SearchResult> instant =
        searchTools.search(
            "linearizability vs sequential consistency", SearchType.INSTANT, List.of());

    assertThat(fast).isNotEmpty();
    assertThat(instant).isNotEmpty();
  }

  @Test
  void includeDomainsRestrictsResultsToVideoPlatforms() {
    List<SearchResult> results =
        searchTools.search(
            "a video walkthrough explaining CAP theorem for a software engineering interview",
            SearchType.AUTO,
            VIDEO_DOMAINS);

    assertThat(results).isNotEmpty();
    assertThat(results).allSatisfy(r -> assertThat(host(r.url())).isEqualTo("www.youtube.com"));
    printResults("includeDomains=VIDEO_DOMAINS", results);
  }

  @Test
  void includeDomainsRestrictsResultsToPracticeSites() {
    List<SearchResult> results =
        searchTools.search(
            "practice problems and exercises for consistent hashing interview preparation",
            SearchType.AUTO,
            PRACTICE_DOMAINS);

    assertThat(results).isNotEmpty();
    assertThat(results)
        .allSatisfy(r -> assertThat(PRACTICE_DOMAINS).anyMatch(d -> host(r.url()).endsWith(d)));
    printResults("includeDomains=PRACTICE_DOMAINS", results);
  }

  @Test
  void queryPhrasingComparisonForTheReferenceLane() {
    List<SearchResult> bareKeywords =
        searchTools.search("CAP theorem distributed systems", SearchType.AUTO, List.of());
    List<SearchResult> naturalSentence =
        searchTools.search(
            "a clear, beginner-friendly explainer on the CAP theorem in distributed systems, with"
                + " practical examples",
            SearchType.AUTO,
            List.of());

    assertThat(bareKeywords).isNotEmpty();
    assertThat(naturalSentence).isNotEmpty();
    printResults("bare-keyword query", bareKeywords);
    printResults("natural-sentence query", naturalSentence);
  }

  private static String host(String url) {
    try {
      String h = URI.create(url).getHost();
      return h == null ? "" : h;
    } catch (IllegalArgumentException malformed) {
      return "";
    }
  }

  private static void printResults(String label, List<SearchResult> results) {
    System.out.println("=== " + label + " (" + results.size() + " results) ===");
    for (SearchResult r : results) {
      System.out.println("  - " + r.title() + " -> " + r.url());
    }
  }
}
