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
  List<ProposedResource> find(@V("topicLabel") String topicLabel, @V("level") String level);
}
