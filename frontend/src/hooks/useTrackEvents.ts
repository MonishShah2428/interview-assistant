import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { keys } from "../api/queries";
import type { EnrichmentEvent } from "../api/types";

export type ConnectionState = "connecting" | "live" | "reconnecting";

/**
 * One SSE connection per open track. Every event invalidates the tree and that topic, so the UI
 * re-reads persisted rows rather than trusting event payloads. Reconnects with backoff, and
 * refetches the tree after a reconnect in case events were missed while disconnected.
 */
export function useTrackEvents(
  trackId: number,
  onEvent: (event: EnrichmentEvent) => void,
): ConnectionState {
  const qc = useQueryClient();
  const [state, setState] = useState<ConnectionState>("connecting");
  const onEventRef = useRef(onEvent);

  useEffect(() => {
    onEventRef.current = onEvent;
  });

  useEffect(() => {
    let source: EventSource | null = null;
    let retryTimer: number | undefined;
    let attempt = 0;
    let closed = false;

    const connect = () => {
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
      source.onerror = () => {
        source?.close();
        if (closed) return;
        setState("reconnecting");
        const delay = Math.min(30000, 1000 * 2 ** attempt++);
        retryTimer = window.setTimeout(connect, delay);
      };
    };

    connect();
    return () => {
      closed = true;
      window.clearTimeout(retryTimer);
      source?.close();
    };
  }, [trackId, qc]);

  return state;
}
