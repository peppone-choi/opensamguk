// 지도 자료 모양 — 새 지도(topdown)와 남는 화면이 함께 쓴다(M2-9 1단계, 2026-10-05).
// 옛 지도(WorldMapCanvas · iso/ · provinceMap)에 있던 정의를 그대로 옮겼다. 옛 지도를 지울 때 이 파일은 남는다.
// 그리는 코드는 두지 않는다.

/** 시야 단계 — 삼모의 완전·첩보·안개와 같다. */
export type CommanderyVisibility = 'FULL' | 'INTEL' | 'FOG';

export type CityStatusBadge = 'isolated' | 'besieged' | 'battle' | 'works';

/** 城 상태 배지 한 개 — 지도 그림과 화면 목록이 함께 읽는다. */
export type IsoCityBadge =
  | { readonly kind: 'waterway'; readonly feature: 'port' | 'ferry' }
  | { readonly kind: 'event'; readonly code: number }
  | { readonly kind: 'supply'; readonly supplied: false }
  | { readonly kind: 'work'; readonly work: string; readonly label: string; readonly phase: 'active' | 'completed'; readonly percent?: number }
  | { readonly kind: 'siege' };

export interface IsoCityOverlay {
  id: number;
  name: string;
  /** 행정 단위가 붙은 원 표기("长安县") — 縣 판정용(cityName.ts). */
  nameCh?: string;
  level: number;
  nationId: number;
  nationName?: string;
  nationColor?: string;
  x: number;
  y: number;
  regionName?: string;
  commanderyName?: string;
  isCommanderySeat?: boolean;
  /** Canonical `provinceRecords[]` identity. Coordinates are presentation fallback only. */
  provinceId?: number;
  state?: number;
  supply?: boolean;
  isCapital?: boolean;
  /**
   * 휘하 상태 배지 — `isolated`(고립) · `besieged`(포위) · `battle`(전투) · `works`(공사).
   * 재해·사건(`state`) 배지와 함께 城 그림 왼쪽 위에 줄지어 붙는다. 무엇을 보일지는 호출부(서버 시야)가 정한다.
   */
  statusBadges?: readonly CityStatusBadge[];
  /** 공사 9종·포위처럼 상세 내용이 있는 별도 지도 배지. */
  cityBadges?: readonly IsoCityBadge[];
  jurisdictionId?: string;
  commanderyId?: string;
  interactive?: boolean;
  mapLabel?: string;
  administrativeKind?: 'COUNTY' | 'EXTERNAL_SETTLEMENT' | 'COMMANDERY';
}

export interface MapCorpsOverlay {
  readonly id: string;
  /** 군단이 선 칸(격자 좌표). 省 중심 칸을 서버나 호출부가 풀어 넘긴다. */
  readonly col: number;
  readonly row: number;
  /** 이름표 — 「하후돈」. 병력 띠는 [troopsLabel] 로 따로. */
  readonly label: string;
  readonly troopsLabel?: string;
  /** 세력 색. 없으면 무소속 회색. */
  readonly color?: string;
  /** 내 군단 — 테두리를 청동으로, 경로·요격 범위를 그린다. */
  readonly own: boolean;
  /** 마지막 목격(첩보 시야) — 흐리게, 「?」. */
  readonly stale?: boolean;
  /** 행군 경로(칸). 내 군단에만 온다. */
  readonly path?: readonly { readonly col: number; readonly row: number }[];
  /** 요격 범위(칸 반경). 요격 방침이 걸린 내 군단에만 온다. */
  readonly interceptRadiusCells?: number;
}

export interface Jun {
  name: string;
  nameCh: string;
  seat: number;
  col: number;
  row: number;
}

export interface AdjEdge {
  a: number;
  b: number;
  cells: number;
  cross: string;
  ford?: number[];
}

export interface BattlefieldMapProjection { cell: number; k: number; x0: number; y1: number; pad: number }

export interface ProvinceRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  administrativeSystem: string;
  kind: string;
  parentRegionId: string;
  cityIndex: number | null;
  geometryBasis: string;
  confidence: string;
  jurisdictionId?: string;
  assignmentBasis?: string;
  assignmentConfidence?: string;
}

export interface JurisdictionRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  kind: string;
  commanderyId: string;
  seatPlaceId: string;
  provinceIds: readonly string[];
}

export interface CommanderyRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  kind: string;
  /** 관할을 모두 이웃 城 관할에 접은 郡은 null 이다(tools/map/fold_cityless_jurisdictions.py). */
  seatJurisdictionId: string | null;
  jurisdictionIds: readonly string[];
}

export interface ParentRegionRecordDto {
  id: string;
  displayName: string;
  nameCh: string;
  administrativeSystem: string;
  /** Canonical 州 from build_han_world.assign_ju_to_juns; 東夷 is a peer region. */
  ju?: string;
  aliases?: readonly string[];
}

export interface WorldTiles {
  _meta: {
    cols: number;
    rows: number;
    resolutionScale?: number;
    year: number;
    terrainLegend: Record<string, string>;
    roadMaskBits?: Record<string, number>;
    projection?: BattlefieldMapProjection;
  };
  terrain: string[];
  owner: [number, number][];
  seatOwner?: [number, number][];
  parentOwner?: [number, number][];
  juns: Jun[];
  provinceRecords?: ProvinceRecordDto[];
  jurisdictionRecords?: JurisdictionRecordDto[];
  commanderyRecords?: CommanderyRecordDto[];
  parentRegions?: ParentRegionRecordDto[];
  adjacency: { county: AdjEdge[]; commandery: AdjEdge[] };
  regions: {
    name: string;
    nameCh: string;
    en: string;
    cls: string;
    col: number;
    row: number;
    cells: number;
  }[];
  cities: {
    id: string;
    name: string;
    nameCh: string;
    level: number;
    kind: string;
    seat: boolean;
    col: number;
    row: number;
    /**
     * CHGIS 실측 경위도. col/row 와 **다른 축**이다 — col/row 는 영역 래스터에 맞춰
     * 옮겨 심은 씨앗이라 여기서 밀려 있을 수 있다(citySeedReseat.ts).
     */
    lat: number;
    lon: number;
  }[];
}

/**
 * 지형 응답의 ETag 에서 sha256 지문을 뽑는다.
 *
 * 약한 ETag(`W/"sha256-…"`)도 강한 ETag 와 같이 받는다 — 프록시가 gzip 하면서 강한 태그를 약하게
 * 바꾸고, 브라우저는 항상 gzip 을 요청한다. 강한 태그만 받으면 프로덕션에서는 지문이 늘 null 이라
 * 수역이 영영 안 뜬다(2026-09-07 실측: sam.peppone.dev 가 `W/"sha256-…"` 를 준다). 해시 값은 같다.
 */
export function parseTerrainEtagHash(etag: string | null): string | null {
  return /^(?:W\/)?"sha256-([a-f0-9]{64})"$/.exec(etag ?? '')?.[1] ?? null;
}
