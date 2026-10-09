import type { RetinueHierarchy } from '@/lib/api/retinue-hierarchy';

/** Synthetic structural fixture, not a claim about historical affiliations. */
export function hierarchyFixture(empty = false): RetinueHierarchy {
  return {
    status: 'READY', actorGeneralId: 7,
    superiors: [{ generalId: 2, name: '가상 상관', parentId: 1 }, { generalId: 1, name: '가상 최상위', parentId: null }],
    nodes: [
      { generalId: 7, name: '하후돈', parentId: 2, directCount: empty ? 0 : 2, descendantCount: empty ? 0 : 3 },
      ...(empty ? [] : [
        { generalId: 101, name: '가상 직속', parentId: 7, directCount: 1, descendantCount: 1 },
        { generalId: 201, name: '가상 하위', parentId: 101, directCount: 0, descendantCount: 0 },
        { generalId: 102, name: '가상 다른 직속', parentId: 7, directCount: 0, descendantCount: 0 },
      ]),
    ],
  };
}
