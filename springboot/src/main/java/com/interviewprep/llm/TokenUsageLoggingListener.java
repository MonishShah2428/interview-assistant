package com.interviewprep.llm;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs per-request token usage (including cached and reasoning tokens when reported). */
class TokenUsageLoggingListener implements ChatModelListener {

  private static final Logger log = LoggerFactory.getLogger(TokenUsageLoggingListener.class);

  private final String role;

  TokenUsageLoggingListener(String role) {
    this.role = role;
  }

  @Override
  public void onResponse(ChatModelResponseContext ctx) {
    if (ctx == null || ctx.chatResponse() == null || ctx.chatResponse().metadata() == null) {
      return;
    }
    ChatResponse response = ctx.chatResponse();
    TokenUsage usage = response.metadata().tokenUsage();
    if (usage == null) {
      return;
    }

    Integer cached = null;
    Integer reasoning = null;
    if (usage instanceof OpenAiTokenUsage openAiUsage) {
      if (openAiUsage.inputTokensDetails() != null) {
        cached = openAiUsage.inputTokensDetails().cachedTokens();
      }
      if (openAiUsage.outputTokensDetails() != null) {
        reasoning = openAiUsage.outputTokensDetails().reasoningTokens();
      }
    }

    ChatRequest request = ctx.chatRequest();
    int messageCount =
        request != null && request.messages() != null ? request.messages().size() : 0;
    int toolCount =
        request != null && request.toolSpecifications() != null
            ? request.toolSpecifications().size()
            : 0;

    log.info(
        "LLM call [{}] model={} msgs={} tools={} tokens(in={}, cached={}, out={}, reasoning={},"
            + " total={})",
        role,
        response.metadata().modelName(),
        messageCount,
        toolCount,
        usage.inputTokenCount(),
        cached,
        usage.outputTokenCount(),
        reasoning,
        usage.totalTokenCount());
  }
}
