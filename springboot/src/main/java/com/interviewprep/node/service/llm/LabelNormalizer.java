package com.interviewprep.node.service.llm;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Plain deterministic cleanup, not an LLM call. {@link TopicExpander} already standardizes label
 * formatting at generation time (Title Case, no trailing punctuation, no leading articles) — this
 * just does the final lowercase/punctuation/whitespace normalization so semantically-identical
 * labels collapse to the same {@code normalized_label} for the dedup lookup.
 */
@Component
public class LabelNormalizer {

  private static final Pattern PUNCTUATION = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  public String normalize(String label) {
    String lower = label.toLowerCase();
    String stripped = PUNCTUATION.matcher(lower).replaceAll("");
    return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
  }
}
