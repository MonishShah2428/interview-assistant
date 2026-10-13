import { useState, type KeyboardEvent } from "react";
import clsx from "clsx";
import { BookOpen, Code2, ExternalLink, GitBranch, PlayCircle, RefreshCw, Search } from "lucide-react";
import { useTopic } from "../api/queries";
import type { ResourceDetail, ResourceLane, TopicDetail, TopicNode } from "../api/types";
import { ApiError } from "../api/client";
import type { NodeInfo } from "../lib/tree";
import { domainOf, relativeTime } from "../lib/time";
import { Button, EnrichmentLabel, Skeleton, StatusDot, topicDisplayStatus } from "./ui";

const LANES: { lane: ResourceLane; label: string; icon: typeof BookOpen; tone: string; empty: string }[] = [
  { lane: "read", label: "Read", icon: BookOpen, tone: "text-lane-read", empty: "No reading found for this topic." },
  { lane: "watch", label: "Watch", icon: PlayCircle, tone: "text-lane-watch", empty: "No videos found for this topic." },
  {
    lane: "practice",
    label: "Practice",
    icon: Code2,
    tone: "text-lane-practice",
    empty: "No practice problems found for this topic.",
  },
];

const TYPE_LABEL: Record<ResourceDetail["type"], string> = {
  video: "Video",
  blog: "Article",
  docs: "Docs",
  book: "Book",
  problem: "Problem",
  other: "Link",
};

export function TopicPanel({
  trackId,
  topicId,
  info,
  expandPending,
  refreshing,
  onExpand,
  onRefresh,
  onSelect,
}: {
  trackId: number;
  topicId: number;
  info: NodeInfo | undefined;
  expandPending: boolean;
  refreshing: boolean;
  onExpand: () => void;
  onRefresh: () => void;
  onSelect: (topicId: number) => void;
}) {
  const { data: topic, error, isPending } = useTopic(trackId, topicId);

  if (error) {
    const notFound = error instanceof ApiError && error.status === 404;
    return (
      <div className="py-16">
        <h1 className="font-serif text-2xl font-semibold">{notFound ? "Topic not found" : "Couldn't load this topic"}</h1>
        <p className="mt-2 text-muted">
          {notFound ? "It isn't part of this track. Pick a topic from the map." : error.message}
        </p>
      </div>
    );
  }

  if (isPending || !topic) return <PanelSkeleton />;

  // "Search again" on a failed topic runs the append-only refresh, which never touches
  // enrichment_status. Once that lands resources, the topic is usable, so show it as ready.
  const status = topic.enrichmentStatus === "failed" && topic.resources.length > 0 ? "ready" : topic.enrichmentStatus;
  // A failed attempt with another already queued isn't final: say so, and don't offer a button
  // that would only queue a duplicate.
  const retrying = status === "failed" && topic.searchQueued;
  const paths = info?.paths ?? [[]];
  const children = info?.node.children ?? [];
  const expanding = expandPending || topic.expansionStatus === "expanding";

  return (
    <article aria-labelledby="topic-title" className="pb-24">
      <header className="border-b border-line pb-6">
        {paths[0].length > 0 && (
          <p className="text-sm text-muted">
            {paths[0].map((label, i) => (
              <span key={i}>
                {i > 0 && <span aria-hidden className="mx-1.5 text-line">/</span>}
                {label}
              </span>
            ))}
          </p>
        )}
        <h1 id="topic-title" className="mt-1 font-serif text-[28px] leading-tight font-semibold text-balance">
          {topic.label}
        </h1>
        {!topic.isCore && (
          <p className="mt-2 text-sm text-muted">Optional at this level. Worth it if you have time.</p>
        )}
        {paths.length > 1 && (
          <p className="mt-2 flex items-start gap-1.5 text-sm text-muted">
            <GitBranch aria-hidden strokeWidth={1.75} className="mt-0.5 size-4 shrink-0" />
            <span>
              Also under{" "}
              {paths
                .slice(1)
                .map((p) => p[p.length - 1])
                .join(", ")}
              . It's one topic, so its resources are shared.
            </span>
          </p>
        )}

        <div className="mt-5 flex flex-wrap items-center gap-x-4 gap-y-3">
          {topic.isRoot ? (
            <span className="text-sm text-muted">Topic group. Break it down to get subtopics with resources.</span>
          ) : (
            <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <EnrichmentLabel status={status} retrying={retrying} />
              {topic.searchedAt && (
                <span className="text-sm text-muted">Searched {relativeTime(topic.searchedAt)}</span>
              )}
            </span>
          )}
          <span className="flex flex-wrap gap-2 sm:ml-auto">
            <ExpandButton topic={topic} expanding={expanding} onExpand={onExpand} />
            {!topic.isRoot && (status === "ready" || (status === "failed" && !retrying)) && (
              <Button
                variant={status === "failed" ? "primary" : "secondary"}
                busy={refreshing}
                icon={<RefreshCw aria-hidden strokeWidth={1.75} className="size-4" />}
                onClick={onRefresh}
              >
                {refreshing ? "Searching" : status === "failed" ? "Search again" : "Find more resources"}
              </Button>
            )}
          </span>
        </div>
        {topic.expansionStatus === "failed" && !expanding && (
          <p role="alert" className="mt-3 text-sm text-failed">
            Breaking this topic down failed. Try again.
          </p>
        )}
      </header>

      {(children.length > 0 || expanding) && (
        <Subtopics children={children} expanding={expanding && children.length === 0} onSelect={onSelect} />
      )}

      {!topic.isRoot && <EnrichedContent topic={topic} status={status} retrying={retrying} />}
    </article>
  );
}

function ExpandButton({ topic, expanding, onExpand }: { topic: TopicDetail; expanding: boolean; onExpand: () => void }) {
  if (topic.expansionStatus === "expanded") return null;
  return (
    <Button
      variant={topic.isRoot ? "primary" : "secondary"}
      busy={expanding}
      icon={<GitBranch aria-hidden strokeWidth={1.75} className="size-4" />}
      onClick={onExpand}
    >
      {expanding ? "Breaking down" : topic.expansionStatus === "failed" ? "Try again" : "Break into subtopics"}
    </Button>
  );
}

function Subtopics({
  children,
  expanding,
  onSelect,
}: {
  children: TopicNode[];
  expanding: boolean;
  onSelect: (topicId: number) => void;
}) {
  return (
    <section aria-labelledby="subtopics-heading" className="border-b border-line py-6">
      <h2 id="subtopics-heading" className="text-sm font-semibold">
        Subtopics
      </h2>
      {expanding ? (
        <div aria-hidden className="mt-3 grid gap-2.5">
          {[40, 56, 32, 48].map((w) => (
            <Skeleton key={w} className="h-4" />
          ))}
        </div>
      ) : (
        <ul className="mt-2 grid gap-x-6 sm:grid-cols-2">
          {children.map((child) => {
            const status = topicDisplayStatus(false, child.expansionStatus, child.enrichmentStatus);
            return (
              <li key={child.id}>
                <button
                  type="button"
                  onClick={() => onSelect(child.id)}
                  className="flex min-h-10 w-full cursor-pointer items-center gap-2.5 rounded text-left text-[15px] hover:text-mark"
                >
                  {status && <StatusDot status={status} label={status} />}
                  <span className={clsx("truncate", !child.isCore && "text-muted")}>{child.label}</span>
                  {!child.isCore && <span className="shrink-0 text-xs text-muted">optional</span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

function EnrichedContent({
  topic,
  status,
  retrying,
}: {
  topic: TopicDetail;
  status: TopicDetail["enrichmentStatus"];
  retrying: boolean;
}) {
  if (status === "pending" || status === "enriching") {
    return (
      <section className="py-8" aria-busy="true">
        <p className="max-w-[60ch] text-muted">
          {status === "pending"
            ? "Queued. The search agent will look for articles, videos and practice problems, and they'll appear here on their own."
            : "Searching for articles, videos and practice problems. They'll appear here on their own."}
        </p>
        <div aria-hidden className="mt-6 grid gap-5">
          {[0, 1, 2].map((i) => (
            <div key={i} className="grid gap-2">
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          ))}
        </div>
      </section>
    );
  }

  return (
    <>
      <Concepts topic={topic} />
      {status === "failed" ? (
        <section aria-labelledby="resources-heading" className="py-6">
          <h2 id="resources-heading" className="text-sm font-semibold">
            Resources
          </h2>
          <p className="mt-3 max-w-[60ch] text-muted">
            {retrying
              ? "The last search attempt didn't finish, usually because the language model was busy. Another attempt is queued and starts within a minute, and results will appear here on their own."
              : "The resource search didn't finish for this topic, even after retrying. This usually means the language model's rate limit was hit. Use Search again to try once more."}
          </p>
        </section>
      ) : (
        <Resources resources={topic.resources} />
      )}
    </>
  );
}

function Concepts({ topic }: { topic: TopicDetail }) {
  if (topic.concepts.length === 0) return null;
  const hasExtracted = topic.concepts.some((c) => c.origin === "extracted");
  return (
    <section aria-labelledby="concepts-heading" className="border-b border-line py-6">
      <h2 id="concepts-heading" className="text-sm font-semibold">
        What this topic covers
      </h2>
      <ul className="mt-3 flex max-w-[72ch] flex-wrap gap-x-4 gap-y-2 text-[15px]">
        {topic.concepts.map((c) => (
          <li key={c.id}>
            <span className={clsx(c.origin === "upfront" ? "marker-soft" : "underline decoration-dashed decoration-muted underline-offset-4")}>
              {c.label}
            </span>
            {c.origin === "extracted" && <span className="sr-only"> (found in a resource)</span>}
          </li>
        ))}
      </ul>
      {hasExtracted && (
        <p className="mt-3 text-xs text-muted">Dashed concepts were found in a resource rather than planned up front.</p>
      )}
    </section>
  );
}

type LaneFilter = ResourceLane | "all";

function Resources({ resources }: { resources: ResourceDetail[] }) {
  const counts = Object.fromEntries(LANES.map((l) => [l.lane, resources.filter((r) => r.lane === l.lane).length])) as Record<
    ResourceLane,
    number
  >;
  const [filter, setFilter] = useState<LaneFilter>("all");

  const tabs: { id: LaneFilter; label: string; count: number; icon?: typeof BookOpen; tone?: string }[] = [
    { id: "all", label: "All", count: resources.length },
    ...LANES.map((l) => ({ id: l.lane, label: l.label, count: counts[l.lane], icon: l.icon, tone: l.tone })),
  ];

  const shown = filter === "all" ? resources : resources.filter((r) => r.lane === filter);
  const lane = LANES.find((l) => l.lane === filter);

  const onTabKey = (e: KeyboardEvent, index: number) => {
    const delta = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!delta) return;
    e.preventDefault();
    const next = tabs[(index + delta + tabs.length) % tabs.length];
    setFilter(next.id);
    document.getElementById(`lane-tab-${next.id}`)?.focus();
  };

  return (
    <section aria-labelledby="resources-heading" className="py-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="resources-heading" className="text-sm font-semibold">
          Resources
        </h2>
        <p className="text-xs text-muted">These are alternatives. Pick whichever way you learn best.</p>
      </div>

      <div role="tablist" aria-label="Filter by lane" className="mt-3 flex gap-1 overflow-x-auto border-b border-line">
        {tabs.map((t, i) => {
          const Icon = t.icon;
          const active = filter === t.id;
          return (
            <button
              key={t.id}
              id={`lane-tab-${t.id}`}
              type="button"
              role="tab"
              aria-selected={active}
              aria-controls="lane-panel"
              tabIndex={active ? 0 : -1}
              onClick={() => setFilter(t.id)}
              onKeyDown={(e) => onTabKey(e, i)}
              className={clsx(
                "-mb-px flex min-h-10 shrink-0 cursor-pointer items-center gap-1.5 border-b-2 px-3 text-sm transition-colors duration-150",
                active ? "border-ring text-fg" : "border-transparent text-muted hover:text-fg",
              )}
            >
              {Icon && <Icon aria-hidden strokeWidth={1.75} className={clsx("size-4", t.tone)} />}
              {t.label}
              <span className="text-xs text-muted">{t.count}</span>
            </button>
          );
        })}
      </div>

      <div id="lane-panel" role="tabpanel" aria-labelledby={`lane-tab-${filter}`}>
        {shown.length === 0 ? (
          <p className="py-6 text-muted">{lane ? lane.empty : "No resources found for this topic yet."}</p>
        ) : (
          <ul className="divide-y divide-line">
            {shown.map((r) => (
              <ResourceRow key={r.id} resource={r} />
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function ResourceRow({ resource }: { resource: ResourceDetail }) {
  const lane = LANES.find((l) => l.lane === resource.lane)!;
  const Icon = lane.icon;
  const domain = domainOf(resource.url);
  const title = resource.title.trim() || domain || "Untitled resource";

  return (
    <li className="flex gap-3 py-4">
      <Icon aria-label={lane.label} role="img" strokeWidth={1.75} className={clsx("mt-1 size-4 shrink-0", lane.tone)} />
      <div className="min-w-0 flex-1">
        {resource.url ? (
          <a
            href={resource.url}
            target="_blank"
            rel="noopener noreferrer"
            className="group inline-flex max-w-full items-start gap-1.5 font-medium text-fg hover:text-mark"
          >
            <span className="break-words">{title}</span>
            <ExternalLink aria-hidden strokeWidth={1.75} className="mt-1 size-3.5 shrink-0 text-muted group-hover:text-mark" />
            <span className="sr-only">(opens in a new tab)</span>
          </a>
        ) : (
          <span className="font-medium">{title}</span>
        )}
        <p className="mt-0.5 flex flex-wrap gap-x-3 text-sm text-muted">
          <span>{TYPE_LABEL[resource.type]}</span>
          {domain && <span>{domain}</span>}
          {resource.citation && <span>{resource.citation}</span>}
        </p>
        {resource.sourceQuery && (
          <details className="mt-1.5 text-sm text-muted">
            <summary className="inline-flex cursor-pointer list-none items-center gap-1 hover:text-fg [&::-webkit-details-marker]:hidden">
              <Search aria-hidden strokeWidth={1.75} className="size-3.5" />
              How the agent found this
            </summary>
            <p className="mt-1 font-mono text-[13px] break-words text-fg/80">{resource.sourceQuery}</p>
          </details>
        )}
      </div>
    </li>
  );
}

function PanelSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading topic" className="grid gap-4 py-2">
      <Skeleton className="h-3 w-40" />
      <Skeleton className="h-8 w-2/3" />
      <Skeleton className="h-4 w-48" />
      <div className="mt-6 grid gap-5">
        {[0, 1, 2].map((i) => (
          <div key={i} className="grid gap-2">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-1/3" />
          </div>
        ))}
      </div>
    </div>
  );
}
