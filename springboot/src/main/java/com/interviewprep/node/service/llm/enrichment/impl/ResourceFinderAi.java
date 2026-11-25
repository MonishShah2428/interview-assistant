package com.interviewprep.node.service.llm.enrichment.impl;

import com.interviewprep.node.entity.ResourceLane;
import com.interviewprep.node.entity.ResourceType;
import dev.langchain4j.service.Result;
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
 * <p>{@link #find} returns {@code Result<SelectedResourceList>}, not {@code List<SelectedResource>}
 * directly: the model backing this call uses tool calling, which OpenAI-compatible APIs (Groq
 * included) refuse to combine with {@code response_format: json_schema} ("json mode cannot be
 * combined with tool/function calling"), so this call can't get native structured output the way
 * {@link com.interviewprep.node.service.llm.enrichment.ConceptGenerator} and friends do. Without
 * that, langchain4j falls back to text-prompted format instructions — which it only supports for a
 * single POJO, not a bare {@code List<POJO>} (see
 * https://github.com/langchain4j/langchain4j/issues/3264). Returning compact {@code (id, type,
 * lane)} selections instead of echoing titles, URLs, and queries cuts output tokens and keeps
 * {@code AgenticResourceFinder} in control of verbatim URL/query hydration.
 */
interface ResourceFinderAi {

  @SystemMessage(
      """
      Find 4-6 interview-prep resources for one topic at the given level, balancing read, watch,
      and practice lanes.

      On turn 1, call webSearch, searchVideos, and searchPracticeProblems in parallel. Compose
      fresh natural-language queries tailored to the topic and level (never copy generic templates
      verbatim): e.g. tutorial/docs explainer for webSearch, video walkthrough for searchVideos,
      coding/design exercises for searchPracticeProblems. Pick type per query: AUTO default, FAST
      for exact terms, INSTANT for quick lookups.

      Reject academic papers and SEO listicles. Only run a follow-up search if a lane returned no
      usable hits (at most 1 retry per weak lane).

      Select only candidate [id] values returned by the tools (never invent resources; return fewer
      or empty if nothing fits). When done, reply directly with the JSON object; never call a tool
      named JSON.
      """)
  @UserMessage("Topic: {{topicLabel}}\nLevel: {{level}}{{existingSection}}")
  Result<SelectedResourceList> find(
      @V("topicLabel") String topicLabel,
      @V("level") String level,
      @V("existingSection") String existingSection);

  record SelectedResourceList(List<SelectedResource> resources) {}

  record SelectedResource(int id, ResourceType type, ResourceLane lane) {}
}
