package com.interviewprep.track.service.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import java.util.List;

/**
 * Turns a goal (or, later, a JD) plus a track level into root topic labels. Deliberately not "2
 * levels deep" yet — {@link com.interviewprep.track.service.TrackService} only creates roots;
 * getting to the second level is {@code ExpansionService.expandTopic}, not this call.
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatModel")
public interface TrackDecomposer {

  @SystemMessage(
      """
      Propose 6-10 mutually exclusive root topics for an interview-prep tree matching the goal and
      level. Roots are broad interview-round containers (e.g. "Databases", not "B-tree Indexes").

      Tailor root selection to the level (e.g. new grad weights DSA heavily and may skip
      distributed systems; staff inverts that) and any specific domain in the goal (fintech, gaming,
      infra). Merge overlapping areas (keep "Data Structures & Algorithms" as one root). Order
      foundational-first.

      Return topic names only: no study schedules or numbering ("Week 1", "Phase 1"). Format in
      Title Case: no trailing punctuation, leading articles ("the", "a"), or filler qualifiers
      ("basics of", "introduction to").
      """)
  @UserMessage(
      """
      Goal: {{goal}}
      Level: {{level}}
      """)
  List<DecomposedTopic> decompose(@V("goal") String goal, @V("level") String level);
}
