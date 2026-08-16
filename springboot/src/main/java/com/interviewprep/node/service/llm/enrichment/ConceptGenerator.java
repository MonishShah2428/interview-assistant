package com.interviewprep.node.service.llm.enrichment;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import java.util.List;

/**
 * Concept-list generation for a topic, scoped to the track's level and its parent chain — the
 * "upfront" half of the concept layer (the other half, extraction at tick time, belongs to the
 * future tick pipeline). Deliberately has no tools and never sees search results: this list is a
 * fixed vocabulary {@link ResourceFinder}-adjacent extraction classifies against later, so it can't
 * be a function of whatever a search happened to return that morning.
 */
@AiService
public interface ConceptGenerator {

  @SystemMessage(
      """
      You generate the fixed set of concepts a candidate should know for one topic, scoped to the
      given interview level. This list is a stable vocabulary other parts of the system classify
      against — get the granularity right: each concept should be something an interviewer could
      ask one focused question about. Not "caching" (too coarse to ask one question about), not
      "the exact eviction threshold in Redis 7.2" (too fine to ever get covered). Produce 10-20
      concepts for a typical topic; if you're near 50, you're too fine-grained.

      Concepts must be mutually exclusive — no two entries should cover the same ground under
      different wording (e.g. "LRU eviction" and "eviction policies" as separate entries is
      wrong).

      Use the parent chain to scope framing: the same topic label means something different under
      different parents.
      """)
  @UserMessage(
      """
      Track level: {{level}}
      Parent chain (root to immediate parent): {{parentChainText}}
      Topic: {{topicLabel}}

      Generate the concept list for "{{topicLabel}}".
      """)
  List<ProposedConcept> generateConcepts(
      @V("level") String level,
      @V("topicLabel") String topicLabel,
      @V("parentChainText") String parentChainText);
}
