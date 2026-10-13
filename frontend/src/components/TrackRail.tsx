import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import clsx from "clsx";
import { ChevronRight, Plus } from "lucide-react";
import type { TopicNode, Track } from "../api/types";
import { Skeleton, StatusDot, topicDisplayStatus, type DisplayStatus } from "./ui";

interface Row {
  key: string;
  node: TopicNode;
  depth: number;
  isRoot: boolean;
  parentKey: string | null;
  hasChildren: boolean;
  isOpen: boolean;
}

const STATUS_WORDS: Record<DisplayStatus, string> = {
  idle: "waiting to search",
  working: "working",
  ready: "resources ready",
  failed: "failed",
};

function buildRows(roots: TopicNode[], openKeys: Set<string>): Row[] {
  const rows: Row[] = [];
  const walk = (node: TopicNode, depth: number, parentKey: string | null) => {
    const key = parentKey ? `${parentKey}/${node.id}` : String(node.id);
    const hasChildren = node.children.length > 0;
    const isOpen = hasChildren && openKeys.has(key);
    rows.push({ key, node, depth, isRoot: depth === 0, parentKey, hasChildren, isOpen });
    if (isOpen) for (const child of node.children) walk(child, depth + 1, key);
  };
  for (const root of roots) walk(root, 0, null);
  return rows;
}

/**
 * Keys to open so every on-screen copy of `topicId` is visible: all its ancestors, plus the topic
 * itself when it has children, so its subtopics show in the map as they do in the panel.
 */
function branchKeysOf(roots: TopicNode[], topicId: number): string[] {
  const found: string[] = [];
  const walk = (node: TopicNode, path: number[]) => {
    const here = [...path, node.id];
    if (node.id === topicId) {
      const depth = node.children.length > 0 ? here.length : here.length - 1;
      for (let i = 1; i <= depth; i++) found.push(here.slice(0, i).join("/"));
    }
    for (const child of node.children) walk(child, here);
  };
  for (const root of roots) walk(root, []);
  return found;
}

export function TrackRail({
  track,
  selectedId,
  pendingExpandIds,
  onSelect,
  onExpand,
}: {
  track: Track;
  selectedId: number | null;
  pendingExpandIds: Set<number>;
  onSelect: (topicId: number) => void;
  onExpand: (topicId: number) => void;
}) {
  // Collapsed by default except the active branch (spec: "The rail needs collapse").
  const [openKeys, setOpenKeys] = useState<Set<string>>(() => new Set());
  const [focusKey, setFocusKey] = useState<string | null>(null);
  const rowRefs = useRef(new Map<string, HTMLDivElement>());

  useEffect(() => {
    if (selectedId == null) return;
    const ancestors = branchKeysOf(track.roots, selectedId);
    if (ancestors.length === 0) return;
    setOpenKeys((prev) => {
      if (ancestors.every((k) => prev.has(k))) return prev;
      const next = new Set(prev);
      ancestors.forEach((k) => next.add(k));
      return next;
    });
  }, [selectedId, track.roots]);

  // A topic the user just asked to expand should show its children once they arrive.
  useEffect(() => {
    if (pendingExpandIds.size === 0) return;
    setOpenKeys((prev) => {
      const next = new Set(prev);
      const walk = (node: TopicNode, key: string) => {
        if (pendingExpandIds.has(node.id)) next.add(key);
        node.children.forEach((c) => walk(c, `${key}/${c.id}`));
      };
      track.roots.forEach((r) => walk(r, String(r.id)));
      return next;
    });
  }, [pendingExpandIds, track.roots]);

  const rows = useMemo(() => buildRows(track.roots, openKeys), [track.roots, openKeys]);
  const activeKey =
    (focusKey && rows.some((r) => r.key === focusKey) ? focusKey : null) ??
    rows.find((r) => r.node.id === selectedId)?.key ??
    rows[0]?.key ??
    null;

  const toggle = (key: string, open?: boolean) =>
    setOpenKeys((prev) => {
      const next = new Set(prev);
      const shouldOpen = open ?? !next.has(key);
      if (shouldOpen) next.add(key);
      else next.delete(key);
      return next;
    });

  const focusRow = (key: string) => {
    setFocusKey(key);
    rowRefs.current.get(key)?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>, row: Row, index: number) => {
    switch (event.key) {
      case "ArrowDown":
        if (rows[index + 1]) focusRow(rows[index + 1].key);
        break;
      case "ArrowUp":
        if (rows[index - 1]) focusRow(rows[index - 1].key);
        break;
      case "ArrowRight":
        if (row.hasChildren && !row.isOpen) toggle(row.key, true);
        else if (row.isOpen && rows[index + 1]) focusRow(rows[index + 1].key);
        break;
      case "ArrowLeft":
        if (row.isOpen) toggle(row.key, false);
        else if (row.parentKey) focusRow(row.parentKey);
        break;
      case "Home":
        if (rows[0]) focusRow(rows[0].key);
        break;
      case "End":
        if (rows.length) focusRow(rows[rows.length - 1].key);
        break;
      case "Enter":
      case " ":
        onSelect(row.node.id);
        break;
      default:
        return;
    }
    event.preventDefault();
  };

  return (
    <div role="tree" aria-label="Topic map" className="py-2">
      {rows.map((row, index) => {
        const { node } = row;
        const expanding = node.expansionStatus === "expanding" || pendingExpandIds.has(node.id);
        const status = topicDisplayStatus(row.isRoot, expanding ? "expanding" : node.expansionStatus, node.enrichmentStatus);
        const selected = node.id === selectedId;
        const canExpand = !expanding && (node.expansionStatus === "not_expanded" || node.expansionStatus === "failed");
        return (
          <div key={row.key}>
            <div
              ref={(el) => {
                if (el) rowRefs.current.set(row.key, el);
                else rowRefs.current.delete(row.key);
              }}
              role="treeitem"
              aria-level={row.depth + 1}
              aria-selected={selected}
              aria-expanded={row.hasChildren ? row.isOpen : undefined}
              aria-label={`${node.label}${node.isCore ? "" : ", optional"}${status ? `, ${STATUS_WORDS[status]}` : ""}`}
              tabIndex={row.key === activeKey ? 0 : -1}
              onKeyDown={(e) => onKeyDown(e, row, index)}
              onFocus={() => setFocusKey(row.key)}
              onClick={() => onSelect(node.id)}
              className={clsx(
                "group relative flex min-h-9 cursor-pointer items-center gap-1.5 pr-2 text-sm outline-offset-[-2px]",
                "hover:bg-surface-2",
                row.isRoot && "mt-1 font-medium",
              )}
              style={{ paddingLeft: `${8 + row.depth * 16}px` }}
            >
              <span
                aria-hidden
                onClick={(e) => {
                  if (!row.hasChildren) return;
                  e.stopPropagation();
                  toggle(row.key);
                }}
                className={clsx(
                  "flex size-5 shrink-0 items-center justify-center rounded text-muted",
                  row.hasChildren && "hover:text-fg",
                )}
              >
                {row.hasChildren && (
                  <ChevronRight
                    strokeWidth={1.75}
                    className={clsx("size-4 transition-transform duration-150", row.isOpen && "rotate-90")}
                  />
                )}
              </span>
              {status ? (
                <StatusDot status={status} label={STATUS_WORDS[status]} />
              ) : (
                !row.isRoot && <span className="size-2 shrink-0" />
              )}
              {/* px-1.5 leaves room inside the truncating box for the marker's ragged ends. */}
              <span
                title={node.label}
                className={clsx("min-w-0 flex-1 truncate px-1.5", !node.isCore && !selected && "text-muted")}
              >
                <span className={clsx(selected && "marker")}>{node.label}</span>
              </span>
              {!node.isCore && <span className="shrink-0 text-xs text-muted">optional</span>}
              {canExpand && (
                <button
                  type="button"
                  tabIndex={-1}
                  aria-label={`Break ${node.label} into subtopics`}
                  title="Break into subtopics"
                  onClick={(e) => {
                    e.stopPropagation();
                    onExpand(node.id);
                  }}
                  className="flex size-6 shrink-0 cursor-pointer items-center justify-center rounded text-muted opacity-0 group-hover:opacity-100 hover:bg-line hover:text-fg focus:opacity-100 max-lg:opacity-100"
                >
                  <Plus aria-hidden strokeWidth={1.75} className="size-3.5" />
                </button>
              )}
            </div>
            {expanding && !row.hasChildren && (
              <div aria-hidden className="space-y-2 py-2" style={{ paddingLeft: `${44 + (row.depth + 1) * 16}px` }}>
                <Skeleton className="h-3 w-32" />
                <Skeleton className="h-3 w-40" />
                <Skeleton className="h-3 w-24" />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
