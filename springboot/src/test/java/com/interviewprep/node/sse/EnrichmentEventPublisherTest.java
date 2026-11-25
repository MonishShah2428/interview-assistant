package com.interviewprep.node.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class EnrichmentEventPublisherTest {

  @Test
  void activeTrackIdsReflectsSubscribeAndCompletion() {
    EnrichmentJobRepository jobRepository = mock(EnrichmentJobRepository.class);
    MutableClock clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    List<RecordingEmitter> created = new ArrayList<>();
    EnrichmentEventPublisher publisher =
        new EnrichmentEventPublisher(jobRepository, false, Duration.ofSeconds(30), clock) {
          @Override
          SseEmitter createEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
          }
        };

    publisher.subscribe(1L);
    publisher.subscribe(2L);
    assertThat(publisher.activeTrackIds()).containsExactlyInAnyOrder(1L, 2L);

    created.get(0).triggerCompletion();
    assertThat(publisher.activeTrackIds()).containsExactly(2L);
  }

  @Test
  void sendHeartbeatsSkipsDbWhenNoSubscribers() {
    EnrichmentJobRepository jobRepository = mock(EnrichmentJobRepository.class);
    MutableClock clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    EnrichmentEventPublisher publisher =
        new EnrichmentEventPublisher(jobRepository, true, Duration.ofSeconds(30), clock);

    publisher.sendHeartbeats();

    verifyNoInteractions(jobRepository);
  }

  @Test
  void autoCloseIdleEmittersKeepsOpenWhileWorkActiveThenClosesAfterIdleGrace() {
    EnrichmentJobRepository jobRepository = mock(EnrichmentJobRepository.class);
    when(jobRepository.hasLiveJobForTrack(7L, EnrichmentJob.MAX_ATTEMPTS)).thenReturn(true);

    MutableClock clock = new MutableClock(Instant.parse("2026-11-25T10:00:00Z"));
    List<RecordingEmitter> created = new ArrayList<>();
    EnrichmentEventPublisher publisher =
        new EnrichmentEventPublisher(jobRepository, true, Duration.ofSeconds(30), clock) {
          @Override
          SseEmitter createEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
          }
        };

    publisher.subscribe(7L);
    RecordingEmitter emitter = created.get(0);

    clock.advance(Duration.ofSeconds(60));
    publisher.sendHeartbeats();
    assertThat(emitter.completed).isFalse();
    assertThat(emitter.eventStrings).noneMatch(s -> s.contains("event:idle"));

    when(jobRepository.hasLiveJobForTrack(7L, EnrichmentJob.MAX_ATTEMPTS)).thenReturn(false);
    clock.advance(Duration.ofSeconds(10));
    publisher.sendHeartbeats();
    assertThat(emitter.completed).isFalse();
    assertThat(publisher.activeTrackIds()).containsExactly(7L);

    clock.advance(Duration.ofSeconds(25));
    publisher.sendHeartbeats();
    assertThat(emitter.completed).isTrue();
    assertThat(emitter.eventStrings).anyMatch(s -> s.contains("event:idle"));
    assertThat(publisher.activeTrackIds()).isEmpty();
  }

  private static final class RecordingEmitter extends SseEmitter {
    private final List<String> eventStrings = new ArrayList<>();
    private Runnable onCompletionCallback;
    private boolean completed;

    RecordingEmitter() {
      super(60_000L);
    }

    @Override
    public void onCompletion(Runnable callback) {
      this.onCompletionCallback = callback;
      super.onCompletion(callback);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      String joined =
          builder.build().stream()
              .map(ResponseBodyEmitter.DataWithMediaType::getData)
              .map(Object::toString)
              .reduce("", String::concat);
      eventStrings.add(joined);
      super.send(builder);
    }

    @Override
    public void complete() {
      completed = true;
      super.complete();
    }

    void triggerCompletion() {
      if (onCompletionCallback != null) {
        onCompletionCallback.run();
      }
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    MutableClock(Instant initial) {
      this.current = new AtomicReference<>(initial);
    }

    void advance(Duration delta) {
      current.updateAndGet(t -> t.plus(delta));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current.get();
    }
  }
}
