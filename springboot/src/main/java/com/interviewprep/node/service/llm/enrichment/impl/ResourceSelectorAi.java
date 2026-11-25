package com.interviewprep.node.service.llm.enrichment.impl;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Fallback structured-output selector used only when {@link ResourceFinderAi} already collected
 * search candidates but failed to emit a parseable final answer (e.g. {@code tool_use_failed},
 * malformed JSON, or max round trips). Runs on the schema-enforced {@code chatModel} with zero
 * tools, guaranteeing schema-valid JSON in one call without restarting searches.
 */
interface ResourceSelectorAi {

  @SystemMessage(
      """
      Pick the 4-6 best interview-prep resources from the numbered candidate list for the given
      topic and level, balancing read, watch, and practice lanes. Reject academic papers and SEO
      listicles. Only pick [id] values present in the list (return fewer or empty if none fit).
      """)
  @UserMessage(
      """
      Topic: {{topicLabel}}
      Level: {{level}}{{existingSection}}

      Candidates:
      {{candidates}}
      """)
  Result<ResourceFinderAi.SelectedResourceList> select(
      @V("topicLabel") String topicLabel,
      @V("level") String level,
      @V("existingSection") String existingSection,
      @V("candidates") String candidates);
}
