package com.interviewprep.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatModelConfigTest {

  private final ChatModelConfig config = new ChatModelConfig();

  @Test
  void configuresLowReasoningEffortParallelToolsMaxTokensAndListeners() {
    ChatModel structured =
        config.chatModel(
            "https://api.groq.com/openai/v1", "k1, k2", "openai/gpt-oss-120b", "low", 2048);
    ChatModel toolCalling =
        config.toolCallingChatModel(
            "https://api.groq.com/openai/v1", "k1, k2", "openai/gpt-oss-20b", "low", 2048);

    OpenAiChatRequestParameters structuredParams =
        (OpenAiChatRequestParameters) structured.defaultRequestParameters();
    assertThat(structuredParams.modelName()).isEqualTo("openai/gpt-oss-120b");
    assertThat(structuredParams.reasoningEffort()).isEqualTo("low");
    assertThat(structuredParams.maxCompletionTokens()).isEqualTo(2048);
    assertThat(structuredParams.parallelToolCalls()).isNull();
    assertThat(structured.supportedCapabilities()).contains(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    assertThat(structured.listeners())
        .hasSize(1)
        .first()
        .isInstanceOf(TokenUsageLoggingListener.class);

    OpenAiChatRequestParameters toolParams =
        (OpenAiChatRequestParameters) toolCalling.defaultRequestParameters();
    assertThat(toolParams.modelName()).isEqualTo("openai/gpt-oss-20b");
    assertThat(toolParams.reasoningEffort()).isEqualTo("low");
    assertThat(toolParams.maxCompletionTokens()).isEqualTo(2048);
    assertThat(toolParams.parallelToolCalls()).isTrue();
    assertThat(toolCalling.supportedCapabilities())
        .doesNotContain(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    assertThat(toolCalling.listeners())
        .hasSize(1)
        .first()
        .isInstanceOf(TokenUsageLoggingListener.class);
  }

  @Test
  void omitsBlankReasoningEffortAndNonPositiveMaxTokens() {
    ChatModel structured =
        config.chatModel("https://api.groq.com/openai/v1", "k1", "openai/gpt-oss-120b", "  ", 0);
    ChatModel toolCalling =
        config.toolCallingChatModel(
            "https://api.groq.com/openai/v1", "k1", "openai/gpt-oss-120b", "", -1);

    OpenAiChatRequestParameters structuredParams =
        (OpenAiChatRequestParameters) structured.defaultRequestParameters();
    assertThat(structuredParams.reasoningEffort()).isNull();
    assertThat(structuredParams.maxCompletionTokens()).isNull();

    OpenAiChatRequestParameters toolParams =
        (OpenAiChatRequestParameters) toolCalling.defaultRequestParameters();
    assertThat(toolParams.reasoningEffort()).isNull();
    assertThat(toolParams.maxCompletionTokens()).isNull();
    assertThat(toolParams.parallelToolCalls()).isTrue();
  }

  @Test
  void tokenUsageListenerHandlesOpenAiAndGenericTokenUsageSafely() {
    TokenUsageLoggingListener listener = new TokenUsageLoggingListener("tool-calling");
    ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();

    OpenAiTokenUsage detailed =
        OpenAiTokenUsage.builder()
            .inputTokenCount(400)
            .outputTokenCount(80)
            .totalTokenCount(480)
            .inputTokensDetails(
                OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(200).build())
            .outputTokensDetails(
                OpenAiTokenUsage.OutputTokensDetails.builder().reasoningTokens(30).build())
            .build();
    ChatResponse openAiResponse =
        ChatResponse.builder()
            .aiMessage(AiMessage.from("ok"))
            .modelName("openai/gpt-oss-120b")
            .tokenUsage(detailed)
            .build();
    ChatResponse basicResponse =
        ChatResponse.builder()
            .aiMessage(AiMessage.from("ok"))
            .tokenUsage(new TokenUsage(10, 5))
            .build();
    ChatResponse emptyResponse = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();

    assertThatNoException()
        .isThrownBy(
            () -> {
              listener.onResponse(
                  new ChatModelResponseContext(
                      openAiResponse, request, ModelProvider.OPEN_AI, Map.of()));
              listener.onResponse(
                  new ChatModelResponseContext(
                      basicResponse, request, ModelProvider.OPEN_AI, Map.of()));
              listener.onResponse(
                  new ChatModelResponseContext(
                      emptyResponse, request, ModelProvider.OPEN_AI, Map.of()));
            });
  }
}
