package com.interviewprep.node.sse;

import com.interviewprep.node.entity.EnrichmentStatus;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Subscription is per-<b>track</b>, not per-topic: a client watches a track's whole tree, and
 * several of its topics can be enriching concurrently after an expand, so one connection per track
 * (opened once, e.g. when the track's page loads) is the natural fit — a per-topic connection would
 * require the client to open a new stream for every child topic {@code ExpansionService} just
 * created.
 */
@Component
public class EnrichmentEventPublisher {

  private static final long TIMEOUT_MS = 30 * 60 * 1000L;

  private final Map<Long, List<SseEmitter>> emittersByTrack = new ConcurrentHashMap<>();

  public SseEmitter subscribe(Long trackId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    List<SseEmitter> emitters =
        emittersByTrack.computeIfAbsent(trackId, id -> new CopyOnWriteArrayList<>());
    emitters.add(emitter);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    return emitter;
  }

  public void publish(Long trackId, Long topicId, EnrichmentStatus status) {
    List<SseEmitter> emitters = emittersByTrack.get(trackId);
    if (emitters == null) {
      return;
    }
    EnrichmentEvent event = new EnrichmentEvent(topicId, status);
    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("enrichment").data(event));
      } catch (IOException e) {
        emitters.remove(emitter);
      }
    }
  }
}
