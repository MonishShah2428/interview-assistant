package com.interviewprep.node.service.llm.enrichment.impl;

import com.interviewprep.node.service.llm.enrichment.ProposedResource;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;

/**
 * The tool-calling contract {@link AgenticResourceFinder} builds by hand via {@code
 * AiServices.builder(...)}, never scanned by Spring: unlike {@link
 * com.interviewprep.node.service.llm.expansion.TopicExpander}, this loop needs {@code
 * maxToolCallingRoundTrips} bounded explicitly, which the declarative {@code @AiService} annotation
 * has no attribute for.
 *
 * <p>{@link #find} returns {@link ProposedResourceList}, not {@code List<ProposedResource>}
 * directly: the model backing this call uses tool calling, which OpenAI-compatible APIs (Groq
 * included) refuse to combine with {@code response_format: json_schema} ("json mode cannot be
 * combined with tool/function calling"), so this call can't get native structured output the way
 * {@link com.interviewprep.node.service.llm.enrichment.ConceptGenerator} and friends do. Without
 * that, langchain4j falls back to text-prompted format instructions — which it only supports for a
 * single POJO, not a bare {@code List<POJO>} (see
 * https://github.com/langchain4j/langchain4j/issues/3264). Wrapping the list in one object routes
 * through the parser that actually works.
 */
interface ResourceFinderAi {

  @SystemMessage(
      """
      Find 4 to 6 learning resources for one topic, covering the read/watch/practice lanes — a
      topic with four videos and nothing to read is a bad result even if all four are excellent.

      Use the webSearch tool. If the first results look like SEO listicles, judge them as junk
      and reformulate the query rather than accepting them. Call validateUrl on every candidate
      before including it in your final answer — never include a URL you have not validated.

      Call existingResources first and only propose resources not already listed there, appending
      rather than replacing.

      If search genuinely turns up nothing usable, return fewer resources or an empty list. Never
      invent a title, URL, or citation — a plausible-looking fabricated link is worse than
      returning nothing.
      """)
  @UserMessage("Topic: {{topicLabel}}\nLevel: {{level}}\nFind resources for \"{{topicLabel}}\".")
  ProposedResourceList find(@V("topicLabel") String topicLabel, @V("level") String level);

  record ProposedResourceList(List<ProposedResource> resources) {}
}
