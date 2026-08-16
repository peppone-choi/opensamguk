// OPENSAM-41 [G0-C] 프론트 자체 fixture — G0C-a 데모 scene, G0C-h synthetic 2,000 catalog.
//
// 백엔드 계약(레인 B/C)이 아직 없으므로 프론트가 자체 read model을 만든다.
// 서버 read model이 착지하면 이 모듈은 fetch 어댑터로 교체된다 — 소비 측
// (projection/lod/렌더러)은 SpaceReadModel만 보므로 바뀌지 않는다.

import type {
  BattlefieldSeed,
  CatalogLodTier,
  CommandCamera,
  PhysicalPlace,
  PlaceBudgetClass,
  RouteCorridor,
  Season,
  SpaceReadModel,
  TerrainRegion,
  WorldPoint,
} from './contracts';
import { CATALOG_TIER_BUDGET, DEMO_VERSIONS, PLACE_BUDGET, PLACE_BUDGET_CLASSES } from './contracts';

const ALL_SEASONS: Season[] = ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'];

function bySeason(value: number | Partial<Record<Season, number>>): Record<Season, number> {
  const out = {} as Record<Season, number>;
  for (const s of ALL_SEASONS) out[s] = typeof value === 'number' ? value : (value[s] ?? 0);
  return out;
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-a 데모 scene: 도시 3 · route 2 · terrain 1
// ─────────────────────────────────────────────────────────────────────────────

const DEMO_PLACES: PhysicalPlace[] = [
  {
    placeId: 'place:luoyang',
    placeIdentityKey: 'sizhou/henan/luoyang',
    name: '낙양',
    budgetClass: 'ADMINISTRATIVE_SETTLEMENT',
    catalogTier: 'A',
    position: { x: 0, y: 12, z: 0 },
    uncertaintyRadius: 0,
    locationResolution: 'EXACT',
    reconstructionStatus: 'OBSERVED',
  },
  {
    placeId: 'place:changan',
    placeIdentityKey: 'sizhou/jingzhao/changan',
    name: '장안',
    budgetClass: 'ADMINISTRATIVE_SETTLEMENT',
    catalogTier: 'A',
    position: { x: -180, y: 20, z: -20 },
    uncertaintyRadius: 0,
    locationResolution: 'EXACT',
    reconstructionStatus: 'OBSERVED',
  },
  {
    // 위치 미확정 치소 — T1-G12 시각 구분 대상(개연 경계를 확정처럼 그리면 안 된다).
    placeId: 'place:hulao-depot',
    placeIdentityKey: 'sizhou/henan/hulao-depot',
    name: '호뢰 보급소',
    budgetClass: 'STRATEGIC_NON_ADMINISTRATIVE',
    catalogTier: 'B',
    position: { x: -70, y: 34, z: 30 },
    uncertaintyRadius: 18,
    locationResolution: 'REGION',
    reconstructionStatus: 'PLAUSIBLE',
  },
];

const DEMO_CORRIDORS: RouteCorridor[] = [
  {
    corridorId: 'route:hangu-plank',
    type: 'PASS',
    endpoints: ['place:changan', 'place:hulao-depot'],
    geometry: [
      { x: -180, y: 20, z: -20 },
      { x: -140, y: 46, z: 4 },
      { x: -70, y: 34, z: 30 },
    ],
    capacity: 12000,
    grade: 'PLANK', // 잔도
    seasonality: bySeason({ SPRING: 1, SUMMER: 1, AUTUMN: 1, WINTER: 0.6 }),
    control: 'CONTESTED',
    damageState: 'DEGRADED',
    geometryConfidence: 0.45,
    sourceRefs: ['후한서/군국지', 'g0c-demo'],
  },
  {
    corridorId: 'route:yellow-river',
    type: 'RIVER',
    endpoints: ['place:hulao-depot', 'place:luoyang'],
    geometry: [
      { x: -70, y: 34, z: 30 },
      { x: -36, y: 18, z: 22 },
      { x: 0, y: 12, z: 0 },
    ],
    capacity: 30000,
    grade: 'TRUNK',
    seasonality: bySeason({ SPRING: 1, SUMMER: 0.8, AUTUMN: 1, WINTER: 0.3 }),
    control: 'FRIENDLY',
    damageState: 'INTACT',
    geometryConfidence: 0.9,
    sourceRefs: ['수경주', 'g0c-demo'],
    // T1-G02 강 수송로 속성은 RIVER corridor에만 붙는다.
    river: {
      upstreamCostFactor: 2.4,
      downstreamCostFactor: 0.6,
      fordPlaceIds: ['place:hulao-depot'],
      requiredVesselClass: 'BARGE',
      navalProficiencyFloor: 25,
      floodRiskBySeason: bySeason({ SPRING: 0.1, SUMMER: 0.55, AUTUMN: 0.2, WINTER: 0.02 }),
    },
  },
];

const DEMO_REGION: TerrainRegion = {
  regionId: 'region:henan-basin',
  kind: 'BASIN',
  outline: [
    { x: -220, y: 0, z: -80 },
    { x: 60, y: 0, z: -80 },
    { x: 60, y: 0, z: 80 },
    { x: -220, y: 0, z: 80 },
  ],
  elevationProfile: { min: 8, max: 68, mean: 24 },
  drainage: 'RIVERINE',
  vegetationInference: { canopy: 0.35, confidence: 0.4 },
  reconstructionStatus: 'RECONSTRUCTED',
};

const DEMO_BATTLEFIELD: BattlefieldSeed = {
  operationId: 'op:hulao-relief',
  engagementGeoAnchor: { x: -70, y: 34, z: 30 },
  season: 'AUTUMN',
  weather: 'RAIN',
  contentVersion: 'g0c-demo-1',
  seed: 'g0c-hulao-0001',
  versions: DEMO_VERSIONS,
};

export function demoScene(): SpaceReadModel {
  return {
    versions: DEMO_VERSIONS,
    places: DEMO_PLACES,
    corridors: DEMO_CORRIDORS,
    regions: [DEMO_REGION],
    operations: [
      {
        operationId: 'op:hulao-relief',
        corridorIds: ['route:hangu-plank', 'route:yellow-river'],
        versions: DEMO_VERSIONS,
      },
    ],
    battlefields: [DEMO_BATTLEFIELD],
  };
}

export function demoCamera(): CommandCamera {
  return {
    target: { x: -70, y: 0, z: 0 },
    zoom: 2.2,
    azimuth: Math.PI / 6,
    pitch: Math.PI / 5,
    versions: DEMO_VERSIONS,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// G0C-h synthetic 2,000 catalog
// ─────────────────────────────────────────────────────────────────────────────

/** 결정적 LCG — replay 재현을 위해 Math.random을 쓰지 않는다. */
function lcg(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 0x1_0000_0000;
  };
}

/** 개수를 정확히 보존하는 결정적 셔플(Fisher–Yates). */
function dealExact<T>(spec: readonly (readonly [T, number])[], seed: number): T[] {
  const deck: T[] = [];
  for (const [value, count] of spec) for (let i = 0; i < count; i += 1) deck.push(value);
  const rnd = lcg(seed);
  for (let i = deck.length - 1; i > 0; i -= 1) {
    const j = Math.floor(rnd() * (i + 1));
    [deck[i], deck[j]] = [deck[j], deck[i]];
  }
  return deck;
}

/** 그리드 간격(투영 단위). demoCamera zoom·pitch에서 화면상 최소 간격이 hit radius를 넘는다. */
export const SYNTHETIC_GRID_SPACING = 40;
const GRID_COLUMNS = 45; // 45² = 2,025 ≥ 2,000

function gridPosition(index: number): WorldPoint {
  const col = index % GRID_COLUMNS;
  const row = Math.floor(index / GRID_COLUMNS);
  return {
    x: (col - GRID_COLUMNS / 2) * SYNTHETIC_GRID_SPACING,
    y: 0,
    z: (row - GRID_COLUMNS / 2) * SYNTHETIC_GRID_SPACING,
  };
}

/**
 * PlaceBudgetClass 예산(1,200/200/500/100)과 catalog Tier 예산(120/380/1,500)을
 * **정확히** 만족하는 2,000 거점. 두 축은 서로 독립적으로 배분된다.
 */
export function syntheticCatalog(seed = 41): PhysicalPlace[] {
  const classSpec = PLACE_BUDGET_CLASSES.map((c) => [c, PLACE_BUDGET[c]] as const);
  const tierSpec = (['A', 'B', 'C'] as const).map((t) => [t, CATALOG_TIER_BUDGET[t]] as const);

  const classes = dealExact<PlaceBudgetClass>(classSpec, seed);
  const tiers = dealExact<CatalogLodTier>(tierSpec, seed + 977);
  const rnd = lcg(seed + 31);

  return classes.map((budgetClass, i) => {
    // 사료 확정이 아닌 것만 uncertaintyRadius를 갖는다(T1-G12).
    const plausible = rnd() < 0.22;
    return {
      placeId: `syn:${String(i).padStart(4, '0')}`,
      placeIdentityKey: `syn/${String(i).padStart(4, '0')}`,
      name: `합성거점${i}`,
      budgetClass,
      catalogTier: tiers[i],
      position: gridPosition(i),
      uncertaintyRadius: plausible ? 6 + Math.floor(rnd() * 20) : 0,
      locationResolution: plausible ? 'REGION' : 'EXACT',
      reconstructionStatus: plausible ? 'PLAUSIBLE' : 'OBSERVED',
    };
  });
}

/** synthetic 2,000을 그대로 그리는 성능 시험용 scene(FPS 게이트 입력). */
export function syntheticScene(seed = 41): SpaceReadModel {
  const demo = demoScene();
  return { ...demo, places: syntheticCatalog(seed) };
}

/** synthetic 그리드에 맞춘 카메라 — 2,000개가 한 화면에 들어온다. */
export function syntheticCamera(): CommandCamera {
  return { target: { x: 0, y: 0, z: 0 }, zoom: 0.5, azimuth: 0, pitch: Math.PI / 4, versions: DEMO_VERSIONS };
}
