import type { ExpandTopicResponse, TopicDetail, Track, TrackSummary } from "./types";

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: { Accept: "application/json", ...(init?.body ? { "Content-Type": "application/json" } : {}) },
    });
  } catch {
    throw new ApiError(0, "Can't reach the server. Check that the backend is running on port 8080.");
  }
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed with status ${res.status}`);
  }
  if (res.status === 202 || res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export const api = {
  listTracks: () => request<TrackSummary[]>("/tracks"),
  getTrack: (trackId: number) => request<Track>(`/tracks/${trackId}`),
  createTrack: (goal: string, level: string) =>
    request<Track>("/tracks", { method: "POST", body: JSON.stringify({ goal, level }) }),
  getTopic: (trackId: number, topicId: number) =>
    request<TopicDetail>(`/tracks/${trackId}/topics/${topicId}`),
  expandTopic: (trackId: number, topicId: number) =>
    request<ExpandTopicResponse>(`/tracks/${trackId}/topics/${topicId}/expand`, { method: "POST" }),
  refreshResources: (trackId: number, topicId: number) =>
    request<void>(`/tracks/${trackId}/topics/${topicId}/resources/refresh`, { method: "POST" }),
  eventsUrl: (trackId: number) => `/tracks/${trackId}/events`,
};
