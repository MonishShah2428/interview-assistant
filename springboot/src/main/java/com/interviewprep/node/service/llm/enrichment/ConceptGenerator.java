package com.interviewprep.node.service.llm.enrichment;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import java.util.List;

/**
 * Concept-list generation for a topic, scoped to the track's level and its parent chain — the
 * "upfront" half of the concept layer (the other half, extraction at tick time, belongs to the
 * future tick pipeline). Deliberately has no tools and never sees search results: this list is a
 * fixed vocabulary {@link ResourceFinder}-adjacent extraction classifies against later, so it can't
 * be a function of whatever a search happened to return that morning.
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatModel")
public interface ConceptGenerator {

  @SystemMessage(
      """
      Generate 10-20 concepts a candidate should know for one topic at the given interview level.
      This is a fixed classification vocabulary: each concept should map to one focused interview
      question (not too broad like "Caching", not overly specific like "Redis 7.2 eviction
      threshold").

      Keep concepts mutually exclusive (never emit overlapping entries like "LRU eviction" and
      "Eviction policies"). Use the parent chain to frame the topic accurately.
      """)
  @UserMessage(
      """
      Level: {{level}}
      Parent chain: {{parentChainText}}
      Topic: {{topicLabel}}
      """)
  List<ProposedConcept> generateConcepts(
      @V("level") String level,
      @V("topicLabel") String topicLabel,
      @V("parentChainText") String parentChainText);
}
