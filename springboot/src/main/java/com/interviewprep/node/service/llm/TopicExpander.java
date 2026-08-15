package com.interviewprep.node.service.llm;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import java.util.List;

@AiService
public interface TopicExpander {

  @SystemMessage(
      """
      You are helping build an interview-prep topic tree for a candidate targeting a specific
      level. Given a topic, propose its direct child topics: the next layer of concepts someone
      would need to learn to go deeper, scoped to what is actually interview-relevant at the given
      level. Do not propose grandchildren, and do not go more than one level deep.

      Mark each proposed child core (isCore = true) if an interviewer at this level would
      reasonably expect the candidate to know it, or optional (isCore = false) otherwise.

      Do not propose a child that duplicates, even under different wording, an existing sibling or
      anything already in the parent chain.

      Format every label consistently so near-duplicates are easy to catch downstream: Title Case,
      no trailing punctuation, no leading articles ("the", "a"), singular nouns where natural, no
      filler qualifiers ("basics of", "introduction to").
      """)
  @UserMessage(
      """
      Track level: {{level}}
      Parent chain (root to immediate parent): {{parentChainText}}
      Topic to expand: {{topicLabel}}
      Existing children of this topic already in the tree: {{siblingLabelsText}}

      Propose the child topics for "{{topicLabel}}".
      """)
  List<ProposedChild> expand(
      @V("level") String level,
      @V("topicLabel") String topicLabel,
      @V("parentChainText") String parentChainText,
      @V("siblingLabelsText") String siblingLabelsText);
}
