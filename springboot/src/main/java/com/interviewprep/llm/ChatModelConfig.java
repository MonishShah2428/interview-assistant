package com.interviewprep.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the {@link ChatModel} every {@code @AiService} interface in this app shares. Wired by hand
 * rather than via {@code langchain4j-open-ai-spring-boot-starter}'s auto-config: that starter's
 * Spring RestClient bridge references {@code
 * org.springframework.boot.http.client.ClientHttpRequestFactorySettings}, a class Spring Boot 4
 * removed (see https://github.com/langchain4j/langchain4j/issues/4268). Building the model directly
 * against {@code langchain4j-http-client-jdk} sidesteps that Spring bridge entirely.
 *
 * <p>Points at Groq's OpenAI-compatible endpoint by default.
 */
@Configuration
class ChatModelConfig {

  @Bean
  ChatModel chatModel(
      @Value("${groq.base-url}") String baseUrl,
      @Value("${groq.api-key}") String apiKey,
      @Value("${groq.model-name}") String modelName) {
    return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName).build();
  }
}
