package com.interviewprep.node.service.llm.enrichment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.interviewprep.node.service.llm.enrichment.SearchResult;
import com.interviewprep.node.service.llm.enrichment.SearchTools;
import com.interviewprep.node.service.llm.enrichment.SearchType;
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

  @Autowired private SearchTools searchTools;

  @Test
  void searchReturnsRealResultsForATopicQuery() {
    List<SearchResult> results =
        searchTools.search("CAP theorem distributed systems", SearchType.AUTO);

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
        searchTools.search("linearizability vs sequential consistency", SearchType.FAST);
    List<SearchResult> instant =
        searchTools.search("linearizability vs sequential consistency", SearchType.INSTANT);

    assertThat(fast).isNotEmpty();
    assertThat(instant).isNotEmpty();
  }
}
