package com.interviewprep.track.service.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import java.util.List;

/**
 * Turns a goal (or, later, a JD) plus a track level into root topic labels. Deliberately not "2
 * levels deep" yet — {@link com.interviewprep.track.service.TrackService} only creates roots;
 * getting to the second level is {@code ExpansionService.expandTopic}, not this call.
 */
@AiService
public interface TrackDecomposer {

  @SystemMessage(
      """
      You are helping build an interview-prep topic tree for a candidate. Given a target goal and
      a level, propose the root topics for that goal: the broad areas an interview loop for this
      goal would have a whole round on. Roots are containers a candidate expands through, not
      individual facts to study — "Databases" is a root, "B-tree Indexes" is not.

      Target six to ten roots. Fewer than five is too broad to be useful ("Technical Skills");
      more than a dozen means you've produced a flat list instead of a tree, with things that
      belong one level deeper sitting at the top.

      Use the level to change what you propose, not just how deep you'd go for each root. A new
      grad goal should weight data structures and algorithms heavily and may skip distributed
      systems entirely; a staff-level goal inverts that. If your roots for "new grad" and "staff"
      versions of the same goal would be identical, you are not using the level.

      Reflect anything specific in the goal. A goal that mentions a domain (fintech, gaming,
      infra) should produce roots a generic version of the same role wouldn't.

      Roots must be mutually exclusive, non-overlapping areas. Do not split "Data Structures" and
      "Algorithms" into separate roots — that produces overlapping children later. Prefer one root
      that covers the combined area.

      Order roots foundational-first — areas a candidate needs earliest or most broadly come
      first, more specialized or advanced areas come later.

      Propose topic names only, never a study plan or schedule. Never emit sequencing language
      ("Week 1", "Phase 1", "Day 3") or numbering as part of a label.

      Format every label consistently: Title Case, no trailing punctuation, no leading articles
      ("the", "a"), no filler qualifiers ("basics of", "introduction to").
      """)
  @UserMessage(
      """
      Goal: {{goal}}
      Level: {{level}}

      Propose the root topics for this goal.
      """)
  List<DecomposedTopic> decompose(@V("goal") String goal, @V("level") String level);
}
