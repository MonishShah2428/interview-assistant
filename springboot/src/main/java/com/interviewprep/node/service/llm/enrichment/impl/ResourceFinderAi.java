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

      You have three search tools, one per lane. Use webSearch for a reference or explainer
      resource (docs, tutorials, blog posts) — it searches the open web. Use searchVideos for a
      video walkthrough and searchPracticeProblems for hands-on practice problems or exercises —
      both are restricted to trusted sites for that content type. Call all three across a topic
      rather than defaulting to webSearch alone; if searchVideos or searchPracticeProblems
      genuinely finds nothing for this topic, that lane can come up short, but don't skip trying.

      Phrase every query as a natural, descriptive sentence matched to what you're looking for,
      not a bare keyword list — this shapes which kind of page the search finds, since research
      papers and beginner tutorials read very differently even on the same topic. For example:
      "a clear, beginner-friendly explainer on {topic} with practical examples" for webSearch, "a
      video walkthrough explaining {topic} for a software engineering interview" for
      searchVideos, "practice problems and exercises for {topic} interview preparation" for
      searchPracticeProblems.

      Pick each tool's search strategy per query: AUTO when unsure, FAST for an exact term, name,
      or error string, INSTANT for a quick lookup where speed matters most. If the first results
      look like SEO listicles or academic papers when you wanted a tutorial, judge them as junk
      and reformulate the query (and reconsider the strategy) rather than accepting them. Only
      propose URLs that appeared in a search result; every URL is checked after you answer, and
      any that don't resolve are dropped.

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
