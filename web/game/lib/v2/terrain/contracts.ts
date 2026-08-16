// OPENSAM-41 [G0-C] 지형 모델 계약 T1-G01~G12 (프론트 측 타입 정본).
//
// 이 파일은 **프론트 자체 fixture 계약**이다. 레인 B/C가 만드는 백엔드 계약이
// 착지하면 여기 타입은 그 read model의 프론트 투영으로 교체/정렬한다. 지금
// 백엔드 타입에 의존하지 않는 이유는 병렬 레인 충돌 회피(작업 지시 명시).
//
// 근거: docs/superpowers/plans/2026-07-17-v2-ticket-backlog/04-systems-micro.md
//   그룹 G (T1-G01~G12), 01-backbone-micro.md Wave G0-C.

// ─────────────────────────────────────────────────────────────────────────────
// T1-G09 좌표·projectionVersion 계약
// 전략 scene과 전술(전장) scene은 **같은 좌표계·같은 projectionVersion**을 쓴다.
// 버전이 다른 read model끼리는 picking/anchor 왕복을 시도조차 하지 않는다(거부).
// ─────────────────────────────────────────────────────────────────────────────

/** 투영 단위 좌표. x=동, z=남, y=고도. 지리 좌표(위경도)는 서버가 이 값으로 사전 투영한다. */
export interface WorldPoint {
  readonly x: number;
  readonly y: number;
  readonly z: number;
}

/** replay 재현에 필요한 버전 축(04-systems-micro.md §replay 버전 필드). */
export interface GeographyVersions {
  readonly geographyVersion: string;
  readonly terrainTileVersion: string;
  readonly projectionVersion: string;
  readonly assetManifestVersion: string;
}

/** 이 프론트 증명이 고정하는 투영 버전. 투영 수식이 바뀌면 반드시 올린다. */
export const PROJECTION_VERSION = 'ortho-1';

export const DEMO_VERSIONS: GeographyVersions = {
  geographyVersion: 'g0c-demo-1',
  terrainTileVersion: 'g0c-demo-1',
  projectionVersion: PROJECTION_VERSION,
  assetManifestVersion: 'g0c-demo-1',
};

// ─────────────────────────────────────────────────────────────────────────────
// T1-B07 / G0C-g PlaceBudgetClass — 상호배타 4종, 합계 2,000
// ─────────────────────────────────────────────────────────────────────────────

export const PLACE_BUDGET_CLASSES = [
  'ADMINISTRATIVE_SETTLEMENT',
  'STRATEGIC_NON_ADMINISTRATIVE',
  'EXTERNAL_PLACE',
  'MARITIME_REMOTE_GATE',
] as const;
export type PlaceBudgetClass = (typeof PLACE_BUDGET_CLASSES)[number];

/** 04-systems-micro.md T1-B07 예산. 합계 2,000. */
export const PLACE_BUDGET: Readonly<Record<PlaceBudgetClass, number>> = {
  ADMINISTRATIVE_SETTLEMENT: 1200,
  STRATEGIC_NON_ADMINISTRATIVE: 200,
  EXTERNAL_PLACE: 500,
  MARITIME_REMOTE_GATE: 100,
};

export const PLACE_BUDGET_TOTAL = 2000;

/** T1-G08 지형 재구성 상태 — 확정 사료 / 재구성 / 개연. */
export const TERRAIN_RECONSTRUCTION_STATUSES = ['OBSERVED', 'RECONSTRUCTED', 'PLAUSIBLE'] as const;
export type TerrainReconstructionStatus = (typeof TERRAIN_RECONSTRUCTION_STATUSES)[number];

/** T1-G05 catalog LOD Tier — 카메라 거리·기기 성능과 **무관**한 데이터 축. */
export const CATALOG_LOD_TIERS = ['A', 'B', 'C'] as const;
export type CatalogLodTier = (typeof CATALOG_LOD_TIERS)[number];

/** 01-backbone-micro.md / T1-G05: 120 / 380 / 1,500 (합계 2,000). */
export const CATALOG_TIER_BUDGET: Readonly<Record<CatalogLodTier, number>> = { A: 120, B: 380, C: 1500 };

/** T1-G06 runtime render LOD — catalog tier와 독립축. */
export const RUNTIME_LODS = ['CLUSTER', 'SYMBOL', 'KIT', 'FULL_SCENE'] as const;
export type RuntimeLod = (typeof RUNTIME_LODS)[number];

// ─────────────────────────────────────────────────────────────────────────────
// PhysicalPlace (프론트 렌더 투영본)
// ─────────────────────────────────────────────────────────────────────────────

export interface PhysicalPlace {
  readonly placeId: string;
  /** G0A-q placeIdentityKey — 복제 검출용 안정 식별자. */
  readonly placeIdentityKey: string;
  readonly name: string;
  readonly budgetClass: PlaceBudgetClass;
  readonly catalogTier: CatalogLodTier;
  readonly position: WorldPoint;
  /** T1-G12: 위치 개연성 반경(투영 단위). 0이면 사료 확정 위치. */
  readonly uncertaintyRadius: number;
  readonly locationResolution: 'EXACT' | 'REGION';
  readonly reconstructionStatus: TerrainReconstructionStatus;
  /** 시나리오 날짜 밖 항목(연대기 모드)에서만 등장하는지. G0C-j. */
  readonly chronicleOnly?: boolean;
}

// ─────────────────────────────────────────────────────────────────────────────
// T1-G01 RouteCorridor / T1-G02 강 수송로
// ─────────────────────────────────────────────────────────────────────────────

export const ROUTE_CORRIDOR_TYPES = ['LAND', 'RIVER', 'CANAL', 'PASS', 'FERRY', 'COASTAL'] as const;
export type RouteCorridorType = (typeof ROUTE_CORRIDOR_TYPES)[number];

export type RouteGrade = 'TRUNK' | 'SECONDARY' | 'TRACK' | 'PLANK'; // 잔도 = PLANK
export type RouteDamageState = 'INTACT' | 'DEGRADED' | 'BROKEN';
export type RouteControl = 'FRIENDLY' | 'CONTESTED' | 'HOSTILE' | 'UNCONTROLLED';

/** T1-G02: 강 corridor에만 붙는 수송 속성. 육로 corridor는 이 값을 갖지 않는다. */
export interface RiverTransportAttributes {
  /** 상행/하행 비대칭 비용 배수. */
  readonly upstreamCostFactor: number;
  readonly downstreamCostFactor: number;
  /** 나루(도하 지점) placeId. */
  readonly fordPlaceIds: readonly string[];
  /** 선박 등급 요구치. */
  readonly requiredVesselClass: 'NONE' | 'RAFT' | 'BARGE' | 'WARSHIP';
  /** 수군 숙련 하한(0~100). 미달이면 통과 실패가 아니라 비용·위험 증가. */
  readonly navalProficiencyFloor: number;
  /** 계절별 홍수 위험(0~1). */
  readonly floodRiskBySeason: Readonly<Record<Season, number>>;
}

export type Season = 'SPRING' | 'SUMMER' | 'AUTUMN' | 'WINTER';

export interface RouteCorridor {
  readonly corridorId: string;
  readonly type: RouteCorridorType;
  /** endpoints = PhysicalPlace 2개. 경유 geometry는 polyline. */
  readonly endpoints: readonly [string, string];
  readonly geometry: readonly WorldPoint[];
  /** 1턴 통과 가능 병력 상한. */
  readonly capacity: number;
  readonly grade: RouteGrade;
  /** 계절별 통행 가능 여부/배수. */
  readonly seasonality: Readonly<Record<Season, number>>;
  readonly control: RouteControl;
  readonly damageState: RouteDamageState;
  /** geometry 자체의 신뢰도 — 0(추정)~1(사료 확정). T1-G12 렌더 구분에 쓴다. */
  readonly geometryConfidence: number;
  readonly sourceRefs: readonly string[];
  readonly river?: RiverTransportAttributes;
}

// ─────────────────────────────────────────────────────────────────────────────
// T1-G03 TerrainRegion
// ─────────────────────────────────────────────────────────────────────────────

export const TERRAIN_REGION_KINDS = ['BASIN', 'PLAIN', 'MOUNTAIN', 'WETLAND', 'PASTURE', 'COAST'] as const;
export type TerrainRegionKind = (typeof TERRAIN_REGION_KINDS)[number];

export interface TerrainRegion {
  readonly regionId: string;
  readonly kind: TerrainRegionKind;
  /** 볼록 다각형(투영 좌표). 렌더·포함판정 공용. */
  readonly outline: readonly WorldPoint[];
  /** 현대 DEM에서 온 물리 기초값. 사료 대체가 아님(T1-G03 완료기준). */
  readonly elevationProfile: { readonly min: number; readonly max: number; readonly mean: number };
  readonly drainage: 'ENDORHEIC' | 'RIVERINE' | 'COASTAL' | 'ARID';
  /** 식생은 추론값 — 확정 사료로 표시하지 않는다. */
  readonly vegetationInference: { readonly canopy: number; readonly confidence: number };
  readonly reconstructionStatus: TerrainReconstructionStatus;
}

// ─────────────────────────────────────────────────────────────────────────────
// T1-G07 BattlefieldSeed / T1-G08 TerrainPatch
// ─────────────────────────────────────────────────────────────────────────────

export interface BattlefieldSeed {
  readonly operationId: string;
  /** 전략 scene과 **같은 좌표계**의 교전 지점. T1-G09. */
  readonly engagementGeoAnchor: WorldPoint;
  readonly season: Season;
  readonly weather: 'CLEAR' | 'RAIN' | 'SNOW' | 'FOG' | 'STORM';
  readonly contentVersion: string;
  readonly seed: string;
  readonly versions: GeographyVersions;
}

export interface TerrainPatch {
  readonly patchId: string;
  readonly origin: WorldPoint;
  readonly elevation: number;
  readonly slope: number;
  readonly soilMoisture: number;
  readonly vegetation: number;
  readonly water: 'NONE' | 'STREAM' | 'RIVER' | 'MARSH' | 'FORD';
  readonly road: 'NONE' | 'TRACK' | 'ROAD' | 'PLANK';
  readonly builtObstacle: 'NONE' | 'WALL' | 'PALISADE' | 'GATE' | 'TOWER';
  /**
   * T1-G08 완료기준: 사료 확인 고지·나루·성벽은 authored 고정(OBSERVED),
   * 나머지는 PLAUSIBLE. replay에 상태 그대로 기록한다.
   */
  readonly terrainReconstructionStatus: TerrainReconstructionStatus;
}

// ─────────────────────────────────────────────────────────────────────────────
// 작전 경로(G0C-c) — corridor 시퀀스
// ─────────────────────────────────────────────────────────────────────────────

export interface OperationPath {
  readonly operationId: string;
  readonly corridorIds: readonly string[];
  readonly versions: GeographyVersions;
}

// ─────────────────────────────────────────────────────────────────────────────
// T1-G10 정사영 지휘 카메라 / G0C-e replay camera
// 서버가 좌표·가시성·충돌·피해를 계산하고 클라이언트는 presentation만 한다.
// 카메라는 판정에 영향을 주지 않는 순수 표현 상태다.
// ─────────────────────────────────────────────────────────────────────────────

export interface CommandCamera {
  readonly target: WorldPoint;
  /** 투영 단위 → 픽셀 배율. */
  readonly zoom: number;
  /** Y축 회전(라디안). */
  readonly azimuth: number;
  /** 부각(라디안). 0 = 정수직 내려보기. */
  readonly pitch: number;
  readonly versions: GeographyVersions;
}

export interface Viewport {
  readonly width: number;
  readonly height: number;
}

/** T1-G11 렌더 모드 — WebGL 불가시 fallback도 **같은 read model**을 쓴다. */
export type RenderMode = 'WEBGL' | 'FALLBACK_TABLE';

/** 전략 scene 하나를 그리는 데 필요한 서버 read model 전부. */
export interface SpaceReadModel {
  readonly versions: GeographyVersions;
  readonly places: readonly PhysicalPlace[];
  readonly corridors: readonly RouteCorridor[];
  readonly regions: readonly TerrainRegion[];
  readonly operations: readonly OperationPath[];
  readonly battlefields: readonly BattlefieldSeed[];
}
