import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import clsx from "clsx";
import { ArrowLeft, Menu, X } from "lucide-react";
import { useExpandTopic, useRefreshResources, useTrack } from "../api/queries";
import { ApiError } from "../api/client";
import type { EnrichmentEvent, Track } from "../api/types";
import { TrackRail } from "../components/TrackRail";
import { TopicPanel } from "../components/TopicPanel";
import { ThemeToggle } from "../components/ThemeToggle";
import { Button, Skeleton, StatusDot, topicDisplayStatus } from "../components/ui";
import { useTrackEvents, type ConnectionState } from "../hooks/useTrackEvents";
import { indexTree } from "../lib/tree";
import { useToast } from "../lib/toast";

function withId(set: Set<number>, id: number, present: boolean): Set<number> {
  if (set.has(id) === present) return set;
  const next = new Set(set);
  if (present) next.add(id);
  else next.delete(id);
  return next;
}

export function WorkspacePage() {
  const params = useParams();
  const trackId = Number(params.trackId);
  const topicId = params.topicId ? Number(params.topicId) : null;
  const navigate = useNavigate();
  const toast = useToast();

  const { data: track, error, isPending } = useTrack(trackId);
  const index = useMemo(() => indexTree(track?.roots ?? []), [track]);
  const indexRef = useRef(index);
  useEffect(() => {
    indexRef.current = index;
  }, [index]);

  const [pendingExpandIds, setPendingExpandIds] = useState<Set<number>>(() => new Set());
  const [refreshingIds, setRefreshingIds] = useState<Set<number>>(() => new Set());
  const refreshingRef = useRef(refreshingIds);
  useEffect(() => {
    refreshingRef.current = refreshingIds;
  }, [refreshingIds]);
  const [drawerOpen, setDrawerOpen] = useState(false);

  const expandTopic = useExpandTopic(trackId);
  const refreshResources = useRefreshResources(trackId);

  const labelOf = (id: number) => indexRef.current.get(id)?.node.label ?? "this topic";

  const onEvent = useCallback(
    (event: EnrichmentEvent) => {
      const label = indexRef.current.get(event.topicId)?.node.label ?? "A topic";
      if (event.kind === "initial") {
        // "Search again" on a failed topic re-runs the initial enrichment, so its result
        // arrives as an initial event; that's when the button stops searching.
        const retriedByUser = refreshingRef.current.has(event.topicId);
        setRefreshingIds((s) => withId(s, event.topicId, false));
        if (event.status === "ready") {
          if (retriedByUser) toast.show(`Found resources for ${label}.`, "success");
          else toast.announce(`${label}: resources ready.`);
        } else {
          toast.show(`The search for ${label} failed. It retries automatically.`, "error");
        }
        return;
      }
      setRefreshingIds((s) => withId(s, event.topicId, false));
      if (event.status === "failed") {
        toast.show(`Finding more resources for ${label} failed. It retries automatically.`, "error");
      } else if (event.newResourceCount) {
        const n = event.newResourceCount;
        toast.show(`Found ${n} new resource${n === 1 ? "" : "s"} for ${label}.`, "success");
      } else {
        toast.show(`No new resources found for ${label}.`, "info");
      }
    },
    [toast],
  );

  const connection = useTrackEvents(trackId, onEvent);

  const select = (id: number) => {
    setDrawerOpen(false);
    navigate(`/tracks/${trackId}/topics/${id}`);
  };

  const expand = (id: number) => {
    setPendingExpandIds((s) => withId(s, id, true));
    expandTopic.mutate(id, {
      onSuccess: (res) => {
        if (res.status === "expanded") {
          const n = res.children.length;
          toast.announce(`${labelOf(id)} broken into ${n} subtopic${n === 1 ? "" : "s"}.`);
        }
      },
      onError: () => toast.show(`Couldn't break ${labelOf(id)} into subtopics. Try again.`, "error"),
      onSettled: () => setPendingExpandIds((s) => withId(s, id, false)),
    });
  };

  const refresh = (id: number) => {
    setRefreshingIds((s) => withId(s, id, true));
    refreshResources.mutate(id, {
      onSuccess: () => toast.announce(`Searching for more resources for ${labelOf(id)}.`),
      onError: () => {
        setRefreshingIds((s) => withId(s, id, false));
        toast.show(`Couldn't start a new search for ${labelOf(id)}. Try again.`, "error");
      },
    });
  };

  useEffect(() => {
    if (!drawerOpen) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawerOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawerOpen]);

  if (!Number.isFinite(trackId) || (error instanceof ApiError && error.status === 404)) {
    return <FullPageMessage title="Track not found" body="It may have been created on another database. Pick one from your tracks." />;
  }
  if (error) return <FullPageMessage title="Couldn't load this track" body={error.message} />;

  const rail = track ? (
    <TrackRail
      track={track}
      selectedId={topicId}
      pendingExpandIds={pendingExpandIds}
      onSelect={select}
      onExpand={expand}
    />
  ) : (
    <RailSkeleton />
  );

  return (
    <div className="flex h-dvh flex-col">
      <a href="#main" className="sr-only z-50 rounded bg-mark px-3 py-2 text-on-mark focus:not-sr-only focus:fixed focus:top-2 focus:left-2">
        Skip to topic
      </a>
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-line bg-surface px-3 sm:px-4">
        <button
          type="button"
          onClick={() => setDrawerOpen(true)}
          aria-label="Open topic map"
          aria-expanded={drawerOpen}
          aria-controls="map-drawer"
          className="flex size-9 cursor-pointer items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-fg lg:hidden"
        >
          <Menu aria-hidden strokeWidth={1.75} className="size-5" />
        </button>
        <Link
          to="/"
          aria-label="All tracks"
          title="All tracks"
          className="flex size-9 shrink-0 items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-fg"
        >
          <ArrowLeft aria-hidden strokeWidth={1.75} className="size-4" />
        </Link>
        <div className="min-w-0 flex-1">
          {track ? (
            <Link to={`/tracks/${trackId}`} className="block truncate font-serif font-semibold hover:text-mark">
              {track.goal}
            </Link>
          ) : (
            <Skeleton className="h-4 w-56" />
          )}
        </div>
        {track && <span className="hidden shrink-0 text-sm text-muted capitalize sm:inline">{track.level}</span>}
        <ConnectionIndicator state={connection} />
        <ThemeToggle />
      </header>

      <div className="flex min-h-0 flex-1">
        <aside aria-label="Topic map" className="hidden w-[300px] shrink-0 overflow-y-auto border-r border-line bg-surface lg:block">
          {rail}
        </aside>

        {drawerOpen && (
          <div className="fixed inset-0 z-40 lg:hidden">
            <div className="absolute inset-0 bg-black/55" onClick={() => setDrawerOpen(false)} aria-hidden />
            <aside
              id="map-drawer"
              aria-label="Topic map"
              className="absolute inset-y-0 left-0 flex w-[min(320px,88vw)] flex-col border-r border-line bg-surface"
            >
              <div className="flex h-14 shrink-0 items-center justify-between border-b border-line px-4">
                <span className="font-medium">Topic map</span>
                <button
                  type="button"
                  onClick={() => setDrawerOpen(false)}
                  aria-label="Close topic map"
                  className="flex size-9 cursor-pointer items-center justify-center rounded-md text-muted hover:bg-surface-2 hover:text-fg"
                >
                  <X aria-hidden strokeWidth={1.75} className="size-5" />
                </button>
              </div>
              <div className="overflow-y-auto">{rail}</div>
            </aside>
          </div>
        )}

        <main id="main" tabIndex={-1} className="min-w-0 flex-1 overflow-y-auto focus:outline-none">
          <div className="mx-auto max-w-[760px] px-5 pt-8 sm:px-10 sm:pt-12">
            {topicId != null ? (
              <TopicPanel
                key={topicId}
                trackId={trackId}
                topicId={topicId}
                info={index.get(topicId)}
                expandPending={pendingExpandIds.has(topicId)}
                refreshing={refreshingIds.has(topicId)}
                onExpand={() => expand(topicId)}
                onRefresh={() => refresh(topicId)}
                onSelect={select}
              />
            ) : isPending || !track ? (
              <RailSkeleton />
            ) : (
              <TrackOverview track={track} pendingExpandIds={pendingExpandIds} onSelect={select} onExpand={expand} />
            )}
          </div>
        </main>
      </div>
    </div>
  );
}

function TrackOverview({
  track,
  pendingExpandIds,
  onSelect,
  onExpand,
}: {
  track: Track;
  pendingExpandIds: Set<number>;
  onSelect: (id: number) => void;
  onExpand: (id: number) => void;
}) {
  return (
    <article aria-labelledby="track-title" className="pb-24">
      <p className="text-sm text-muted capitalize">{track.level} level</p>
      <h1 id="track-title" className="mt-1 font-serif text-[28px] leading-tight font-semibold text-balance">
        {track.goal}
      </h1>
      <p className="mt-3 max-w-[62ch] text-muted">
        Your goal is split into {track.roots.length} topic groups. Break a group into subtopics, then open a subtopic to
        see what to read, watch and practice.
      </p>
      <ol className="mt-8 divide-y divide-line border-y border-line">
        {track.roots.map((root) => {
          const expanding = pendingExpandIds.has(root.id) || root.expansionStatus === "expanding";
          const status = topicDisplayStatus(true, expanding ? "expanding" : root.expansionStatus, root.enrichmentStatus);
          return (
            <li key={root.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-4">
              <button
                type="button"
                onClick={() => onSelect(root.id)}
                className="flex min-w-0 flex-1 cursor-pointer items-center gap-3 text-left"
              >
                {status && <StatusDot status={status} label={status} />}
                <span className="font-serif text-lg font-semibold hover:text-mark">{root.label}</span>
              </button>
              {root.expansionStatus === "expanded" ? (
                <span className="text-sm text-muted">
                  {root.children.length} subtopic{root.children.length === 1 ? "" : "s"}
                </span>
              ) : (
                <Button busy={expanding} onClick={() => onExpand(root.id)}>
                  {expanding ? "Breaking down" : root.expansionStatus === "failed" ? "Try again" : "Break into subtopics"}
                </Button>
              )}
            </li>
          );
        })}
      </ol>
    </article>
  );
}

function ConnectionIndicator({ state }: { state: ConnectionState }) {
  const live = state === "live";
  return (
    <span
      className={clsx("hidden shrink-0 items-center gap-1.5 text-xs sm:flex", live ? "text-muted" : "text-working")}
      title={live ? "Updates arrive as they happen" : "Reconnecting to live updates"}
    >
      <span className={clsx("size-1.5 rounded-full", live ? "bg-ready" : "bg-working pulse-dot")} aria-hidden />
      {live ? "Live" : state === "connecting" ? "Connecting" : "Reconnecting"}
    </span>
  );
}

function RailSkeleton() {
  return (
    <div aria-busy="true" aria-label="Loading" className="grid gap-3 p-4">
      {[48, 36, 56, 40].map((w) => (
        <Skeleton key={w} className="h-4" />
      ))}
    </div>
  );
}

function FullPageMessage({ title, body }: { title: string; body: string }) {
  return (
    <main id="main" className="mx-auto max-w-xl px-5 py-24">
      <h1 className="font-serif text-2xl font-semibold">{title}</h1>
      <p className="mt-2 text-muted">{body}</p>
      <Link to="/" className="mt-6 inline-block font-medium text-mark hover:underline">
        See all tracks
      </Link>
    </main>
  );
}
