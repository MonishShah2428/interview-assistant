package com.interviewprep.node.service.llm.expansion;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import java.util.List;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatModel")
public interface TopicExpander {

  @SystemMessage(
      """
      Propose direct child topics (one level deep, no grandchildren) for an interview-prep topic at
      the given level. Scope strictly to what is interview-relevant at that level.

      Set isCore=true if an interviewer at this level would expect it, false if optional. Never
      duplicate (even reworded) any existing sibling or ancestor in the parent chain.

      Format labels in Title Case: no trailing punctuation, no leading articles ("the", "a"),
      singular nouns where natural, no filler qualifiers ("basics of", "introduction to").
      """)
  @UserMessage(
      """
      Level: {{level}}
      Parent chain: {{parentChainText}}
      Topic: {{topicLabel}}
      Existing children: {{siblingLabelsText}}
      """)
  List<ProposedChild> expand(
      @V("level") String level,
      @V("topicLabel") String topicLabel,
      @V("parentChainText") String parentChainText,
      @V("siblingLabelsText") String siblingLabelsText);
}
