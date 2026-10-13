package com.interviewprep.llm;

import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits out Groq's tokens-per-minute limit instead of failing. When a request is rate-limited,
 * Groq's error says how long to wait ("Please try again in 6.42s"); this waits that long plus
 * {@link #SAFETY_MARGIN} and sends the same request again.
 *
 * <p>Retrying here, per request, matters for the tool-calling agent: without it a rate limit late
 * in the loop fails the whole search, and the job queue's retry restarts it from round 1, which
 * reaches the same limit at the same point every time.
 *
 * <p>Waits longer than {@link #MAX_WAIT} are not attempted: those come from daily limits ("try
 * again in 2h13m"), where blocking a worker thread would only hide the failure.
 */
public class RateLimitRetryingChatModel implements ChatModel {

  private static final Logger log = LoggerFactory.getLogger(RateLimitRetryingChatModel.class);

  static final Duration SAFETY_MARGIN = Duration.ofSeconds(4);
  static final Duration MAX_WAIT = Duration.ofSeconds(60);

  /** Used when the error doesn't say how long to wait. */
  static final Duration FALLBACK_WAIT = Duration.ofSeconds(10);

  private static final int MAX_RETRIES = 4;

  private static final Pattern RETRY_AFTER = Pattern.compile("try again in ([0-9hms.]+)");
  private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");

  private final ChatModel delegate;
  private final Sleeper sleeper;

  public RateLimitRetryingChatModel(ChatModel delegate) {
    this(delegate, duration -> Thread.sleep(duration.toMillis()));
  }

  RateLimitRetryingChatModel(ChatModel delegate, Sleeper sleeper) {
    this.delegate = delegate;
    this.sleeper = sleeper;
  }

  @Override
  public ChatResponse chat(ChatRequest request) {
    return withRetry(() -> delegate.chat(request));
  }

  @Override
  public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
    return withRetry(() -> delegate.chat(request, options));
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    return withRetry(() -> delegate.doChat(request));
  }

  // Everything below passes straight through. supportedCapabilities in particular must: strict
  // JSON-schema output is switched on by the delegate advertising RESPONSE_FORMAT_JSON_SCHEMA.

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return delegate.defaultRequestParameters();
  }

  @Override
  public List<ChatModelListener> listeners() {
    return delegate.listeners();
  }

  @Override
  public ModelProvider provider() {
    return delegate.provider();
  }

  @Override
  public Set<Capability> supportedCapabilities() {
    return delegate.supportedCapabilities();
  }

  private ChatResponse withRetry(Supplier<ChatResponse> call) {
    for (int retry = 0; ; retry++) {
      try {
        return call.get();
      } catch (RateLimitException e) {
        Optional<Duration> asked = retryAfter(e.getMessage());
        Duration base = asked.orElse(FALLBACK_WAIT);
        if (retry == MAX_RETRIES || base.compareTo(MAX_WAIT) > 0) {
          throw e;
        }
        Duration wait = base.plus(SAFETY_MARGIN);
        log.info(
            "Groq rate limit hit; waiting {} ms (asked {}) before retry {} of {}",
            wait.toMillis(),
            asked.map(d -> d.toMillis() + " ms").orElse("nothing"),
            retry + 1,
            MAX_RETRIES);
        try {
          sleeper.sleep(wait);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  /** Parses Groq's "try again in 6.42s" / "1m2.5s" / "450ms" hint, if the message has one. */
  static Optional<Duration> retryAfter(String message) {
    if (message == null) {
      return Optional.empty();
    }
    Matcher hint = RETRY_AFTER.matcher(message);
    if (!hint.find()) {
      return Optional.empty();
    }
    Matcher part = DURATION_PART.matcher(hint.group(1));
    double millis = 0;
    boolean any = false;
    while (part.find()) {
      double value = Double.parseDouble(part.group(1));
      millis +=
          switch (part.group(2)) {
            case "h" -> value * 3_600_000;
            case "m" -> value * 60_000;
            case "s" -> value * 1_000;
            default -> value; // ms
          };
      any = true;
    }
    return any ? Optional.of(Duration.ofMillis(Math.round(millis))) : Optional.empty();
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
