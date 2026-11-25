package com.interviewprep.node.sse;

import com.interviewprep.node.entity.EnrichmentJob;
import com.interviewprep.node.repository.EnrichmentJobRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Subscription is per-<b>track</b>, not per-topic: a client watches a track's whole tree, and
 * several of its topics can be enriching concurrently after an expand, so one connection per track
 * (opened once, e.g. while enrichment work is active) is the natural fit — a per-topic connection
 * would require the client to open a new stream for every child topic {@code ExpansionService} just
 * created.
 *
 * <p>When {@code autoCloseIdleEmitters} is enabled (on request-billed Cloud Run), heartbeats check
 * whether each subscribed track still has a live {@link EnrichmentJob}; after {@code idleGrace}
 * with no remaining live jobs or published events, the server emits an {@code idle} event and
 * completes the emitter so idle browser tabs never pin a Cloud Run instance indefinitely.
 */
@Component
public class EnrichmentEventPublisher {

  private static final long TIMEOUT_MS = 30 * 60 * 1000L;

  private final EnrichmentJobRepository jobRepository;
  private final boolean autoCloseIdleEmitters;
  private final Duration idleGrace;
  private final Clock clock;
  private final Map<Long, List<SseEmitter>> emittersByTrack = new ConcurrentHashMap<>();
  private final Map<Long, Instant> lastActiveWorkByTrack = new ConcurrentHashMap<>();

  @Autowired
  public EnrichmentEventPublisher(
      EnrichmentJobRepository jobRepository,
      @Value("${enrichment.polling.require-active-subscriber:false}") boolean autoCloseIdleEmitters,
      @Value("${enrichment.sse.idle-grace-seconds:30}") long idleGraceSeconds) {
    this(
        jobRepository,
        autoCloseIdleEmitters,
        Duration.ofSeconds(idleGraceSeconds),
        Clock.systemUTC());
  }

  EnrichmentEventPublisher(
      EnrichmentJobRepository jobRepository,
      boolean autoCloseIdleEmitters,
      Duration idleGrace,
      Clock clock) {
    this.jobRepository = jobRepository;
    this.autoCloseIdleEmitters = autoCloseIdleEmitters;
    this.idleGrace = idleGrace;
    this.clock = clock;
  }

  /** Tracks that currently have at least one open SSE subscriber. */
  public Set<Long> activeTrackIds() {
    return emittersByTrack.entrySet().stream()
        .filter(e -> !e.getValue().isEmpty())
        .map(Map.Entry::getKey)
        .collect(Collectors.toUnmodifiableSet());
  }

  public SseEmitter subscribe(Long trackId) {
    SseEmitter emitter = createEmitter();
    emittersByTrack.compute(
        trackId,
        (id, existing) -> {
          List<SseEmitter> list = existing != null ? existing : new CopyOnWriteArrayList<>();
          list.add(emitter);
          return list;
        });
    lastActiveWorkByTrack.put(trackId, clock.instant());
    emitter.onCompletion(() -> removeEmitter(trackId, emitter));
    emitter.onTimeout(() -> removeEmitter(trackId, emitter));
    emitter.onError(e -> removeEmitter(trackId, emitter));
    // SseEmitter writes nothing until its first send, so without this the response headers never
    // flush and a browser EventSource sits in CONNECTING until the first real event. An early send
    // is buffered and flushed as soon as the handler returns. Comments are ignored by clients.
    try {
      emitter.send(SseEmitter.event().comment("connected"));
    } catch (IOException e) {
      removeEmitter(trackId, emitter);
    }
    return emitter;
  }

  public void publish(Long trackId, EnrichmentEvent event) {
    lastActiveWorkByTrack.put(trackId, clock.instant());
    List<SseEmitter> emitters = emittersByTrack.get(trackId);
    if (emitters == null) {
      return;
    }
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("enrichment").data(event));
      } catch (IOException | IllegalStateException e) {
        removeEmitter(trackId, emitter);
      }
    }
  }

  /**
   * Keeps active SSE streams alive through reverse proxies and, when configured, closes idle
   * streams once their track has no remaining live enrichment jobs. Skips DB queries altogether
   * when no subscribers are connected.
   */
  @Scheduled(fixedRate = 25_000)
  void sendHeartbeats() {
    if (emittersByTrack.isEmpty()) {
      return;
    }
    for (Map.Entry<Long, List<SseEmitter>> entry : emittersByTrack.entrySet()) {
      Long trackId = entry.getKey();
      List<SseEmitter> emitters = entry.getValue();
      if (emitters.isEmpty()) {
        continue;
      }
      if (autoCloseIdleEmitters && shouldCloseIdle(trackId)) {
        closeAsIdle(trackId, emitters);
        continue;
      }
      for (SseEmitter emitter : emitters) {
        try {
          emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
          removeEmitter(trackId, emitter);
        }
      }
    }
  }

  SseEmitter createEmitter() {
    return new SseEmitter(TIMEOUT_MS);
  }

  private boolean shouldCloseIdle(Long trackId) {
    boolean hasLive = jobRepository.hasLiveJobForTrack(trackId, EnrichmentJob.MAX_ATTEMPTS);
    Instant now = clock.instant();
    if (hasLive) {
      lastActiveWorkByTrack.put(trackId, now);
      return false;
    }
    Instant lastActive = lastActiveWorkByTrack.computeIfAbsent(trackId, ignored -> now);
    return !Duration.between(lastActive, now).minus(idleGrace).isNegative();
  }

  private void closeAsIdle(Long trackId, List<SseEmitter> emitters) {
    for (SseEmitter emitter : List.copyOf(emitters)) {
      try {
        emitter.send(SseEmitter.event().name("idle").data("idle"));
        emitter.complete();
      } catch (IOException | IllegalStateException ignored) {
        // Client already gone; removal below finishes cleanup.
      } finally {
        removeEmitter(trackId, emitter);
      }
    }
  }

  private void removeEmitter(Long trackId, SseEmitter emitter) {
    emittersByTrack.computeIfPresent(
        trackId,
        (id, list) -> {
          list.remove(emitter);
          if (list.isEmpty()) {
            lastActiveWorkByTrack.remove(trackId);
            return null;
          }
          return list;
        });
  }
}
