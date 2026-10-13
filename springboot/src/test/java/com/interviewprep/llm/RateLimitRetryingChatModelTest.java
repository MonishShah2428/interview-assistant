package com.interviewprep.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RateLimitRetryingChatModelTest {

  private static final String GROQ_TPM_ERROR =
      "{\"error\":{\"message\":\"Rate limit reached for model `openai/gpt-oss-120b` ... on tokens"
          + " per minute (TPM): Limit 8000, Used 1348, Requested 7508. Please try again in 6.42s."
          + " Need more tokens?\"}}";

  private static final ChatRequest REQUEST =
      ChatRequest.builder().messages(UserMessage.from("hi")).build();
  private static final ChatResponse OK =
      ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();

  @Test
  void parsesGroqWaitHints() {
    assertThat(RateLimitRetryingChatModel.retryAfter(GROQ_TPM_ERROR))
        .contains(Duration.ofMillis(6420));
    assertThat(RateLimitRetryingChatModel.retryAfter("Please try again in 450ms."))
        .contains(Duration.ofMillis(450));
    assertThat(RateLimitRetryingChatModel.retryAfter("Please try again in 1m2.5s."))
        .contains(Duration.ofMillis(62_500));
    assertThat(RateLimitRetryingChatModel.retryAfter("Please try again in 2h13m."))
        .contains(Duration.ofMinutes(133));
    assertThat(RateLimitRetryingChatModel.retryAfter("Too many requests"))
        .isEqualTo(Optional.empty());
  }

  @Test
  void waitsTheAskedTimePlusFourSecondsThenRetries() {
    List<Duration> waits = new ArrayList<>();
    FlakyModel delegate = new FlakyModel(2, GROQ_TPM_ERROR);
    ChatModel model = new RateLimitRetryingChatModel(delegate, waits::add);

    assertThat(model.chat(REQUEST)).isSameAs(OK);
    assertThat(delegate.calls).isEqualTo(3);
    assertThat(waits).containsExactly(Duration.ofMillis(10_420), Duration.ofMillis(10_420));
  }

  @Test
  void fallsBackToATenSecondWaitWhenGroqGivesNoHint() {
    List<Duration> waits = new ArrayList<>();
    ChatModel model = new RateLimitRetryingChatModel(new FlakyModel(1, "rate limited"), waits::add);

    model.chat(REQUEST);

    assertThat(waits).containsExactly(Duration.ofSeconds(14));
  }

  @Test
  void doesNotWaitOutDailyLimits() {
    List<Duration> waits = new ArrayList<>();
    ChatModel model =
        new RateLimitRetryingChatModel(
            new FlakyModel(1, "tokens per day (TPD). Please try again in 2h13m."), waits::add);

    assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOf(RateLimitException.class);
    assertThat(waits).isEmpty();
  }

  @Test
  void givesUpAfterFourRetries() {
    List<Duration> waits = new ArrayList<>();
    FlakyModel delegate = new FlakyModel(Integer.MAX_VALUE, GROQ_TPM_ERROR);
    ChatModel model = new RateLimitRetryingChatModel(delegate, waits::add);

    assertThatThrownBy(() -> model.chat(REQUEST)).isInstanceOf(RateLimitException.class);
    assertThat(delegate.calls).isEqualTo(5);
    assertThat(waits).hasSize(4);
  }

  @Test
  void passesCapabilitiesThroughSoStrictJsonModeStaysOn() {
    ChatModel model = new RateLimitRetryingChatModel(new FlakyModel(0, ""), d -> {});

    assertThat(model.supportedCapabilities())
        .containsExactly(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
  }

  /** Fails with a rate limit {@code failures} times, then answers. */
  private static class FlakyModel implements ChatModel {
    private final int failures;
    private final String message;
    int calls;

    FlakyModel(int failures, String message) {
      this.failures = failures;
      this.message = message;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
      if (calls++ < failures) {
        throw new RateLimitException(message);
      }
      return OK;
    }

    @Override
    public Set<Capability> supportedCapabilities() {
      return Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA);
    }
  }
}
