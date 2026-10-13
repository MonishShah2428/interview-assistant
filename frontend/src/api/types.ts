// Mirrors the Spring DTOs in springboot/src/main/java/com/interviewprep/**/dto and node/entity enums.

export type ExpansionStatus = "not_expanded" | "expanding" | "expanded" | "failed";
export type EnrichmentStatus = "pending" | "enriching" | "ready" | "failed";
export type ResourceType = "video" | "blog" | "docs" | "book" | "problem" | "other";
export type ResourceLane = "read" | "watch" | "practice";
export type ConceptOrigin = "upfront" | "extracted";
export type EnrichmentJobKind = "initial" | "resource_refresh";

export interface TrackSummary {
  id: number;
  goal: string;
  level: string;
  createdAt: string;
}

export interface TopicNode {
  id: number;
  label: string;
  isCore: boolean;
  expansionStatus: ExpansionStatus;
  enrichmentStatus: EnrichmentStatus;
  children: TopicNode[];
}

export interface Track {
  id: number;
  goal: string;
  level: string;
  roots: TopicNode[];
}

export interface ResourceDetail {
  id: number;
  title: string;
  url: string | null;
  citation: string | null;
  type: ResourceType;
  lane: ResourceLane;
  sourceQuery: string | null;
  position: number;
}

export interface Concept {
  id: number;
  label: string;
  origin: ConceptOrigin;
}

export interface TopicDetail {
  id: number;
  label: string;
  isCore: boolean;
  isRoot: boolean;
  expansionStatus: ExpansionStatus;
  enrichmentStatus: EnrichmentStatus;
  searchedAt: string | null;
  resources: ResourceDetail[];
  concepts: Concept[];
}

export interface ExpandTopicResponse {
  status: ExpansionStatus;
  children: { topicId: number; label: string; isCore: boolean }[];
}

export interface EnrichmentEvent {
  topicId: number;
  kind: EnrichmentJobKind;
  status: EnrichmentStatus;
  newResourceCount: number | null;
}
