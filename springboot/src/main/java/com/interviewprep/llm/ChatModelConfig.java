package com.interviewprep.llm;

import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Builds the {@link ChatModel} beans every {@code @AiService} interface in this app shares. Wired
 * by hand rather than via {@code langchain4j-open-ai-spring-boot-starter}'s auto-config: that
 * starter's Spring RestClient bridge references {@code
 * org.springframework.boot.http.client.ClientHttpRequestFactorySettings}, a class Spring Boot 4
 * removed (see https://github.com/langchain4j/langchain4j/issues/4268). Building the model directly
 * against {@code langchain4j-http-client-jdk} sidesteps that Spring bridge entirely.
 *
 * <p>Points at Groq's OpenAI-compatible endpoint by default, on the one model family Groq
 * guarantees schema-constrained output on ({@code openai/gpt-oss-*}).
 *
 * <p>Two beans, not one, because Groq (like the underlying OpenAI API) rejects a request that
 * combines {@code response_format: json_schema} with tool calling ("json mode cannot be combined
 * with tool/function calling"). {@link #chatModel} is {@code @Primary} — every plain
 * {@code @AiService} here returns a typed POJO or {@code List<POJO>}, and langchain4j has no
 * text-prompted fallback for {@code List<POJO>} without native JSON-schema support (see
 * https://github.com/langchain4j/langchain4j/issues/3264), so strict JSON schema is load-bearing
 * for those. {@link #toolCallingChatModel} drops that capability for the one caller that needs
 * tools instead: {@code AgenticResourceFinder}.
 *
 * <p>Both support comma-separated keys in {@code groq.api-key} and wrap them in {@link
 * RateLimitRetryingChatModel} (with LangChain4j's built-in {@code maxRetries(0)} so a rate-limited
 * key fails over immediately rather than retrying the same exhausted key), alternating across keys
 * and waiting out a tokens-per-minute limit only when every key is exhausted. Both default {@code
 * reasoning_effort} to {@code low} (overridable via properties) to avoid burning excess reasoning
 * tokens on every round trip, and attach {@link TokenUsageLoggingListener} to log per-call usage.
 */
@Configuration
class ChatModelConfig {

  @Bean
  @Primary
  ChatModel chatModel(
      @Value("${groq.base-url}") String baseUrl,
      @Value("${groq.api-key}") String apiKey,
      @Value("${groq.model-name}") String modelName,
      @Value("${groq.reasoning-effort:low}") String reasoningEffort,
      @Value("${groq.max-completion-tokens:2048}") int maxCompletionTokens) {
    List<ChatModel> delegates =
        buildDelegates(
            baseUrl,
            apiKey,
            modelName,
            reasoningEffort,
            maxCompletionTokens,
            "structured",
            true,
            false);
    return new RateLimitRetryingChatModel(delegates);
  }

  @Bean
  @Qualifier("toolCallingChatModel")
  ChatModel toolCallingChatModel(
      @Value("${groq.base-url}") String baseUrl,
      @Value("${groq.api-key}") String apiKey,
      @Value("${groq.tool-calling.model-name:${groq.model-name}}") String modelName,
      @Value("${groq.tool-calling.reasoning-effort:${groq.reasoning-effort:low}}")
          String reasoningEffort,
      @Value("${groq.max-completion-tokens:2048}") int maxCompletionTokens) {
    List<ChatModel> delegates =
        buildDelegates(
            baseUrl,
            apiKey,
            modelName,
            reasoningEffort,
            maxCompletionTokens,
            "tool-calling",
            false,
            true);
    return new RateLimitRetryingChatModel(delegates);
  }

  private static List<ChatModel> buildDelegates(
      String baseUrl,
      String apiKey,
      String modelName,
      String reasoningEffort,
      int maxCompletionTokens,
      String listenerRole,
      boolean structuredJsonSchema,
      boolean parallelToolCalls) {
    TokenUsageLoggingListener listener = new TokenUsageLoggingListener(listenerRole);
    return parseKeys(apiKey).stream()
        .map(
            key -> {
              OpenAiChatModel.OpenAiChatModelBuilder builder =
                  OpenAiChatModel.builder()
                      .baseUrl(baseUrl)
                      .apiKey(key)
                      .modelName(modelName)
                      .maxRetries(0)
                      .listeners(listener);
              if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                builder.reasoningEffort(reasoningEffort.strip());
              }
              if (maxCompletionTokens > 0) {
                builder.maxCompletionTokens(maxCompletionTokens);
              }
              if (structuredJsonSchema) {
                builder
                    .strictJsonSchema(true)
                    .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
              }
              if (parallelToolCalls) {
                builder.parallelToolCalls(true);
              }
              return (ChatModel) builder.build();
            })
        .toList();
  }

  private static List<String> parseKeys(String rawKeys) {
    List<String> keys =
        Arrays.stream(rawKeys.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    return keys.isEmpty() ? List.of(rawKeys) : keys;
  }
}
