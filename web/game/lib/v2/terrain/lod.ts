// OPENSAM-41 [G0-C] T1-G05/G06 LOD + T1-B07 PlaceBudgetClass 집계 (G0C-g~j).
//
// 핵심 계약 두 개:
//  1) catalog LOD Tier(A/B/C)는 **데이터 축**이다. 카메라 거리·기기 성능이 바꾸지 못한다.
//  2) runtime render LOD(CLUSTER|SYMBOL|KIT|FULL_SCENE)는 **표현 축**이다. tier와 독립이고,
//     어떤 조합에서도 stable identity 선택·검색이 가능해야 한다(Tier C + CLUSTER 포함).

import type {
  CatalogLodTier,
  PhysicalPlace,
  PlaceBudgetClass,
  RuntimeLod,
  WorldPoint,
} from './contracts';
import {
  CATALOG_TIER_BUDGET,
  PLACE_BUDGET,
  PLACE_BUDGET_CLASSES,
  PLACE_BUDGET_TOTAL,
  RUNTIME_LODS,
} from './contracts';

// ─────────────────────────────────────────────────────────────────────────────
// G0C-g PlaceBudgetClass 분류·count·합계
// ─────────────────────────────────────────────────────────────────────────────

export interface BudgetAudit {
  readonly counts: Readonly<Record<PlaceBudgetClass, number>>;
  readonly total: number;
  /** 상호배타 위반: 같은 identityKey가 서로 다른 class로 두 번 등장. */
  readonly multiClassKeys: readonly string[];
  /** class 값이 4종 밖이거나 비어 있음. */
  readonly unclassifiedIds: readonly string[];
  /** 좌표가 없는 = 물리 장소가 아닌 레코드. */
  readonly nonPlaceIds: readonly string[];
  /** identityKey 복제(G0A-q). */
  readonly duplicateKeys: readonly string[];
}

const VALID_CLASSES: ReadonlySet<string> = new Set(PLACE_BUDGET_CLASSES);

function isFinitePoint(p: WorldPoint | undefined): boolean {
  return !!p && Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z);
}

export function auditBudget(places: readonly PhysicalPlace[]): BudgetAudit {
  const counts: Record<PlaceBudgetClass, number> = {
    ADMINISTRATIVE_SETTLEMENT: 0,
    STRATEGIC_NON_ADMINISTRATIVE: 0,
    EXTERNAL_PLACE: 0,
    MARITIME_REMOTE_GATE: 0,
  };
  const classesByKey = new Map<string, Set<string>>();
  const seenKeys = new Map<string, number>();
  const unclassifiedIds: string[] = [];
  const nonPlaceIds: string[] = [];

  for (const place of places) {
    if (!isFinitePoint(place.position)) nonPlaceIds.push(place.placeId);

    seenKeys.set(place.placeIdentityKey, (seenKeys.get(place.placeIdentityKey) ?? 0) + 1);
    let set = classesByKey.get(place.placeIdentityKey);
    if (!set) {
      set = new Set();
      classesByKey.set(place.placeIdentityKey, set);
    }
    set.add(place.budgetClass);

    if (!VALID_CLASSES.has(place.budgetClass)) {
      unclassifiedIds.push(place.placeId);
      continue;
    }
    counts[place.budgetClass] += 1;
  }

  const multiClassKeys = [...classesByKey.entries()].filter(([, s]) => s.size > 1).map(([k]) => k);
  const duplicateKeys = [...seenKeys.entries()].filter(([, n]) => n > 1).map(([k]) => k);

  return {
    counts,
    total: places.length,
    multiClassKeys: multiClassKeys.sort(),
    unclassifiedIds: unclassifiedIds.sort(),
    nonPlaceIds: nonPlaceIds.sort(),
    duplicateKeys: duplicateKeys.sort(),
  };
}

/** 예산과의 차이를 사람이 읽을 수 있는 위반 목록으로. 비어 있으면 통과. */
export function budgetViolations(audit: BudgetAudit): readonly string[] {
  const out: string[] = [];
  for (const cls of PLACE_BUDGET_CLASSES) {
    if (audit.counts[cls] !== PLACE_BUDGET[cls]) {
      out.push(`${cls}: ${audit.counts[cls]} ≠ ${PLACE_BUDGET[cls]}`);
    }
  }
  if (audit.total !== PLACE_BUDGET_TOTAL) out.push(`total: ${audit.total} ≠ ${PLACE_BUDGET_TOTAL}`);
  if (audit.multiClassKeys.length) out.push(`다중 class: ${audit.multiClassKeys.length}`);
  if (audit.unclassifiedIds.length) out.push(`무분류: ${audit.unclassifiedIds.length}`);
  if (audit.nonPlaceIds.length) out.push(`비장소: ${audit.nonPlaceIds.length}`);
  if (audit.duplicateKeys.length) out.push(`identityKey 복제: ${audit.duplicateKeys.length}`);
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-h catalog LOD Tier 집계
// ─────────────────────────────────────────────────────────────────────────────

export function auditCatalogTiers(
  places: readonly PhysicalPlace[],
): { counts: Record<CatalogLodTier, number>; violations: string[] } {
  const counts: Record<CatalogLodTier, number> = { A: 0, B: 0, C: 0 };
  const violations: string[] = [];
  for (const p of places) {
    if (p.catalogTier !== 'A' && p.catalogTier !== 'B' && p.catalogTier !== 'C') {
      violations.push(`${p.placeId}: catalogTier 값이 A/B/C가 아님`);
      continue;
    }
    counts[p.catalogTier] += 1;
  }
  for (const tier of ['A', 'B', 'C'] as const) {
    if (counts[tier] !== CATALOG_TIER_BUDGET[tier]) {
      violations.push(`Tier ${tier}: ${counts[tier]} ≠ ${CATALOG_TIER_BUDGET[tier]}`);
    }
  }
  return { counts, violations };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-i runtime LOD — catalog tier와 독립
// ─────────────────────────────────────────────────────────────────────────────

export type DevicePerfTier = 'LOW' | 'MID' | 'HIGH';

/**
 * runtime LOD 선택. 입력은 카메라 거리와 기기 성능뿐이며 **catalogTier를 읽지 않는다**
 * — 이게 "독립축"의 실질이다. 시그니처에 PhysicalPlace가 없는 것이 그 증명이다.
 */
export function selectRuntimeLod(cameraDistance: number, perf: DevicePerfTier): RuntimeLod {
  // 저사양 기기는 한 단계 멀리 있는 것처럼 취급한다(판정이 아니라 표현만 바뀐다).
  const bias = perf === 'LOW' ? 2 : perf === 'MID' ? 1 : 0;
  const thresholds = [60, 220, 700]; // FULL_SCENE | KIT | SYMBOL | CLUSTER 경계
  let level = 0;
  for (const t of thresholds) if (cameraDistance >= t) level += 1;
  const idx = Math.min(RUNTIME_LODS.length - 1, level + bias);
  // RUNTIME_LODS는 CLUSTER가 0번(가장 거침)이므로 뒤집어 매핑한다.
  return RUNTIME_LODS[RUNTIME_LODS.length - 1 - idx];
}

/** CLUSTER 표현에서도 개별 identity가 살아 있어야 한다(T1-G06 완료기준). */
export interface RenderCluster {
  readonly clusterId: string;
  readonly representativeId: string;
  readonly memberIds: readonly string[];
  readonly center: WorldPoint;
}

export interface RenderPlan {
  readonly lod: RuntimeLod;
  /** 개별 마커로 그려지는 거점. */
  readonly markers: readonly PhysicalPlace[];
  /** CLUSTER LOD에서만 비지 않는다. */
  readonly clusters: readonly RenderCluster[];
}

/**
 * 표현만 바꾸는 렌더 계획. LOD가 무엇이든 read model의 모든 거점은 markers 또는
 * cluster.memberIds 중 정확히 한 곳에 **정확히 한 번** 나타난다 — 그래야 검색·선택이
 * LOD와 무관하게 성립한다.
 */
export function planRender(
  places: readonly PhysicalPlace[],
  lod: RuntimeLod,
  cellSize = 64,
): RenderPlan {
  if (lod !== 'CLUSTER') return { lod, markers: places, clusters: [] };

  const buckets = new Map<string, PhysicalPlace[]>();
  for (const p of places) {
    const key = `${Math.floor(p.position.x / cellSize)}:${Math.floor(p.position.z / cellSize)}`;
    const bucket = buckets.get(key);
    if (bucket) bucket.push(p);
    else buckets.set(key, [p]);
  }

  const clusters: RenderCluster[] = [...buckets.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([key, members]) => {
      const sorted = [...members].sort((a, b) => (a.placeId < b.placeId ? -1 : a.placeId > b.placeId ? 1 : 0));
      const n = sorted.length;
      return {
        clusterId: `cl:${key}`,
        // Tier A(가장 중요한 카탈로그 등급)를 대표로, 없으면 placeId 최소값 — 결정적.
        representativeId: (sorted.find((p) => p.catalogTier === 'A') ?? sorted[0]).placeId,
        memberIds: sorted.map((p) => p.placeId),
        center: {
          x: sorted.reduce((s, p) => s + p.position.x, 0) / n,
          y: sorted.reduce((s, p) => s + p.position.y, 0) / n,
          z: sorted.reduce((s, p) => s + p.position.z, 0) / n,
        },
      };
    });

  return { lod, markers: [], clusters };
}

/** LOD와 무관하게 placeId로 되찾을 수 있는지 — 왕복 검사의 실질. */
export function resolveIdentity(plan: RenderPlan, placeId: string): { found: boolean; via: 'MARKER' | 'CLUSTER' | 'NONE'; clusterId?: string } {
  if (plan.markers.some((m) => m.placeId === placeId)) return { found: true, via: 'MARKER' };
  const cluster = plan.clusters.find((c) => c.memberIds.includes(placeId));
  if (cluster) return { found: true, via: 'CLUSTER', clusterId: cluster.clusterId };
  return { found: false, via: 'NONE' };
}

/**
 * G0C-g~j 공용 검사. synthetic 2,000과 실제 source catalog가 **같은 함수**를 탄다
 * — 검사가 두 벌이면 "동일 검사"라고 말할 수 없다.
 */
export function auditCatalog(places: readonly PhysicalPlace[]): readonly string[] {
  return [
    ...budgetViolations(auditBudget(places)),
    ...auditCatalogTiers(places).violations,
    ...auditLodIndependence(places),
  ];
}

/** catalog tier × runtime LOD 12조합 전부에서 identity가 보존되는지. 위반 목록 반환. */
export function auditLodIndependence(places: readonly PhysicalPlace[]): readonly string[] {
  const out: string[] = [];
  const tiers: readonly CatalogLodTier[] = ['A', 'B', 'C'];

  for (const lod of RUNTIME_LODS) {
    const plan = planRender(places, lod);

    const covered = new Set<string>([
      ...plan.markers.map((m) => m.placeId),
      ...plan.clusters.flatMap((c) => c.memberIds),
    ]);
    const totalSlots = plan.markers.length + plan.clusters.reduce((s, c) => s + c.memberIds.length, 0);
    if (covered.size !== places.length) out.push(`${lod}: 커버 ${covered.size} ≠ ${places.length}`);
    if (totalSlots !== places.length) out.push(`${lod}: 중복 배치 (${totalSlots} slots)`);

    for (const tier of tiers) {
      for (const place of places.filter((p) => p.catalogTier === tier)) {
        const r = resolveIdentity(plan, place.placeId);
        if (!r.found) {
          out.push(`${lod} × Tier ${tier}: ${place.placeId} identity 유실`);
          break; // tier당 첫 위반만 — 목록 폭발 방지
        }
      }
    }
  }
  return out;
}
