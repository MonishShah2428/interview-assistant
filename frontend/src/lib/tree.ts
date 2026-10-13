import type { TopicNode } from "../api/types";

export interface NodeInfo {
  node: TopicNode;
  isRoot: boolean;
  /** Every path of ancestor labels that leads to this topic. More than one means DAG reuse. */
  paths: string[][];
}

/** Indexes the materialized tree by topic id. The same topic can appear under several parents. */
export function indexTree(roots: TopicNode[]): Map<number, NodeInfo> {
  const index = new Map<number, NodeInfo>();
  const walk = (node: TopicNode, path: string[], isRoot: boolean) => {
    const existing = index.get(node.id);
    if (existing) {
      existing.paths.push(path);
    } else {
      index.set(node.id, { node, isRoot, paths: [path] });
    }
    for (const child of node.children) walk(child, [...path, node.label], false);
  };
  for (const root of roots) walk(root, [], true);
  return index;
}
