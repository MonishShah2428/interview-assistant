import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import type { Track, TopicNode } from "./types";

export const keys = {
  tracks: ["tracks"] as const,
  track: (trackId: number) => ["track", trackId] as const,
  topic: (trackId: number, topicId: number) => ["topic", trackId, topicId] as const,
};

function anyNode(nodes: TopicNode[], test: (n: TopicNode, depth: number) => boolean, depth = 0): boolean {
  return nodes.some((n) => test(n, depth) || anyNode(n.children, test, depth + 1));
}

/**
 * Live updates come over SSE, but polling covers what SSE can't: an expansion owned by another
 * request (expand returns "expanding" without children) and any event missed while reconnecting.
 */
function trackRefetchInterval(track: Track | undefined): number | false {
  if (!track) return false;
  if (anyNode(track.roots, (n) => n.expansionStatus === "expanding")) return 2000;
  if (
    anyNode(
      track.roots,
      (n, depth) => depth > 0 && (n.enrichmentStatus === "pending" || n.enrichmentStatus === "enriching"),
    )
  ) {
    return 15000;
  }
  return false;
}

export function useTracks() {
  return useQuery({ queryKey: keys.tracks, queryFn: api.listTracks });
}

export function useTrack(trackId: number) {
  return useQuery({
    queryKey: keys.track(trackId),
    queryFn: () => api.getTrack(trackId),
    refetchInterval: (query) => trackRefetchInterval(query.state.data),
  });
}

export function useTopic(trackId: number, topicId: number | null) {
  return useQuery({
    queryKey: keys.topic(trackId, topicId ?? -1),
    queryFn: () => api.getTopic(trackId, topicId!),
    enabled: topicId != null,
  });
}

export function useCreateTrack() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ goal, level }: { goal: string; level: string }) => api.createTrack(goal, level),
    onSuccess: (track) => {
      qc.setQueryData(keys.track(track.id), track);
      void qc.invalidateQueries({ queryKey: keys.tracks });
    },
  });
}

export function useExpandTopic(trackId: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (topicId: number) => api.expandTopic(trackId, topicId),
    onSettled: (_data, _err, topicId) => {
      void qc.invalidateQueries({ queryKey: keys.track(trackId) });
      void qc.invalidateQueries({ queryKey: keys.topic(trackId, topicId) });
    },
  });
}

export function useRefreshResources(trackId: number) {
  return useMutation({ mutationFn: (topicId: number) => api.refreshResources(trackId, topicId) });
}
