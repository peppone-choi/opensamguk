import type { HierarchyNode, RetinueHierarchy } from './api/retinue-hierarchy';

export interface HierarchyRow {
  readonly node: HierarchyNode;
  readonly depth: number;
  readonly parentName: string | null;
}

/** Preserve the validated server preorder; hierarchy IDs are general IDs, never retainer/card IDs. */
export function hierarchyRows(data: RetinueHierarchy): readonly HierarchyRow[] {
  const depths = new Map<number, number>();
  const names = new Map([...data.superiors, ...data.nodes].map((n) => [n.generalId, n.name]));
  return data.nodes.map((node, i) => {
    const depth = i === 0 ? 0 : (depths.get(node.parentId!) ?? 0) + 1;
    depths.set(node.generalId, depth);
    return { node, depth, parentName: node.parentId === null ? null : names.get(node.parentId) ?? null };
  });
}
