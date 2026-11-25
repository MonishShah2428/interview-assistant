import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { keys } from "../api/queries";
import type { EnrichmentEvent } from "../api/types";

export type ConnectionState = "idle" | "paused" | "connecting" | "live" | "reconnecting";

const HIDDEN_GRACE_MS = 60_000;

/**
 * One SSE connection per track, opened only while {@code enabled} (active enrichment work) so
 * completed tracks let Cloud Run scale to zero. When the browser tab hides, waits a 60s grace
 * delay before closing so in-flight enrichments can finish without wasting tokens, then pauses
 * until the tab becomes visible again.
 */
export function useTrackEvents(
  trackId: number,
  enabled: boolean,
  onEvent: (event: EnrichmentEvent) => void,
  onIdle?: () => void,
): ConnectionState {
  const qc = useQueryClient();
  const [state, setState] = useState<ConnectionState>("connecting");
  const onEventRef = useRef(onEvent);
  const onIdleRef = useRef(onIdle);

  useEffect(() => {
    onEventRef.current = onEvent;
    onIdleRef.current = onIdle;
  });

  useEffect(() => {
    if (!enabled) return;

    let source: EventSource | null = null;
    let retryTimer: number | undefined;
    let hideTimer: number | undefined;
    let initTimer: number | undefined;
    let attempt = 0;
    let closed = false;
    let pausedByHide = false;

    const closeSource = () => {
      window.clearTimeout(retryTimer);
      retryTimer = undefined;
      source?.close();
      source = null;
    };

    const connect = () => {
      if (closed || pausedByHide) return;
      closeSource();
      source = new EventSource(api.eventsUrl(trackId));
      source.onopen = () => {
        if (attempt > 0) void qc.invalidateQueries({ queryKey: keys.track(trackId) });
        attempt = 0;
        setState("live");
      };
      source.addEventListener("enrichment", (message) => {
        const event = JSON.parse((message as MessageEvent<string>).data) as EnrichmentEvent;
        void qc.invalidateQueries({ queryKey: keys.track(trackId) });
        void qc.invalidateQueries({ queryKey: keys.topic(trackId, event.topicId) });
        onEventRef.current(event);
      });
      source.addEventListener("idle", () => {
        window.clearTimeout(hideTimer);
        hideTimer = undefined;
        closeSource();
        setState("idle");
        void qc.invalidateQueries({ queryKey: keys.track(trackId) });
        onIdleRef.current?.();
      });
      source.onerror = () => {
        closeSource();
        if (closed || pausedByHide) return;
        setState("reconnecting");
        const delay = Math.min(30000, 1000 * 2 ** attempt++);
        retryTimer = window.setTimeout(connect, delay);
      };
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        window.clearTimeout(hideTimer);
        hideTimer = window.setTimeout(() => {
          hideTimer = undefined;
          if (closed) return;
          pausedByHide = true;
          closeSource();
          setState("paused");
        }, HIDDEN_GRACE_MS);
        return;
      }
      window.clearTimeout(hideTimer);
      hideTimer = undefined;
      if (pausedByHide) {
        pausedByHide = false;
        attempt = 0;
        void qc.invalidateQueries({ queryKey: keys.track(trackId) });
        setState("connecting");
        connect();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    if (document.visibilityState === "hidden") {
      pausedByHide = true;
      initTimer = window.setTimeout(() => !closed && setState("paused"), 0);
    } else {
      initTimer = window.setTimeout(() => {
        if (closed) return;
        setState("connecting");
        connect();
      }, 0);
    }

    return () => {
      closed = true;
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.clearTimeout(initTimer);
      window.clearTimeout(hideTimer);
      closeSource();
    };
  }, [trackId, enabled, qc]);

  return enabled ? state : "idle";
}
