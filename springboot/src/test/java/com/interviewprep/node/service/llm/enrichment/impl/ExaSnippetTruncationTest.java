package com.interviewprep.node.service.llm.enrichment.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExaSnippetTruncationTest {

  @Test
  void shortSnippetsAreLeftAlone() {
    assertThat(ExaSearchTools.truncate("  Write-through caching writes to both.  ", 500))
        .isEqualTo("Write-through caching writes to both.");
  }

  @Test
  void longSnippetsAreCutAtAWordBoundaryWithAnEllipsis() {
    String text = "word ".repeat(200); // 1000 chars

    String cut = ExaSearchTools.truncate(text, ExaSearchTools.MAX_SNIPPET_CHARS);

    assertThat(cut).hasSizeLessThanOrEqualTo(ExaSearchTools.MAX_SNIPPET_CHARS);
    assertThat(cut).endsWith("word…");
  }

  @Test
  void aSingleHugeWordIsCutMidWordRatherThanDropped() {
    String cut = ExaSearchTools.truncate("x".repeat(800), 500);

    assertThat(cut).hasSize(500).endsWith("…");
  }
}
