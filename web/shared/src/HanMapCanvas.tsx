'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import {
  cellToScreen,
  clampView,
  effectiveDpr,
  fitScale,
  maxScaleForDpr,
  pinchGesture,
  screenToCell,
  viewAt,
  zoomAt,
  type GridSize,
  type IsoView,
} from './isoMap';
import {
  bindCompleteProvinceOwnership,
  bindAdministrativeOwnership,
  buildCountyAdministrativeIndex,
  buildProvinceAdministrativeIndex,
  buildProvinceVisualAnchors,
  composeProvincePixels,
  formatProvinceTooltip,
  loadProvinceIdentityMap,
  resolveProvincePlacement,
  type CommanderyRecordDto,
  type ParentRegionRecordDto,
  type JurisdictionRecordDto,
  type CountyAdministrativeIndex,
  type ProvinceRecordDto,
  type ProvinceEdge,
  type ProvinceIdentityMap,
  type ProvinceOwnershipBinding,
  type ProvinceVisualAnchor,
  type AdministrativeLayer,
  type AdministrativeOwnershipData,
} from './provinceMap';
import { isOwnedNationVisual } from './nationVisual';
import {
  buildStrategicMapScene, validStrategicBinding, validatedWaterControls, waterControlLabel,
  strategicModeLabel, strategicZoneLabel, strategicCapacityLabel, serverRoutePoints,
  type StrategicMapSnapshot, type StrategicMapRoute, type StrategicMapScene, type StrategicWaterControl,
} from './strategicMap';

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

export interface HanTiles {
  _meta: {
    cols: number;
    rows: number;
    year: number;
    terrainLegend: Record<string, string>;
    roadMaskBits?: Record<string, number>;
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
  }[];
}

export interface IsoSourceSize {
  width: number;
  height: number;
}

export interface IsoCityOverlay {
  id: number;
  name: string;
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
  jurisdictionId?: string;
  commanderyId?: string;
  interactive?: boolean;
  mapLabel?: string;
  administrativeKind?: 'COUNTY' | 'EXTERNAL_SETTLEMENT' | 'COMMANDERY';
}

export interface IsoSceneCity extends IsoCityOverlay {
  col: number;
  row: number;
  color: string;
  territoryColor: string;
  iconColor: string;
  layers: string[];
  provinceId?: number;
  visualClearance?: number;
  mapLabel: string;
  provinceKind?: string;
}

export interface IsoScene {
  terrain: string[];
  roads: { from: [number, number]; to: [number, number] }[];
  cities: IsoSceneCity[];
}

interface IsoMarkerPosition {
  col: number;
  row: number;
  provinceId?: number;
  visualClearance?: number;
}

interface CityHitBox {
  city: IsoCityOverlay;
  provinceId?: number;
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface IsoSceneOptions {
  currentCityId?: number | null;
  selectedCityId?: number | null;
  markerPositions?: ReadonlyMap<number, IsoMarkerPosition>;
  provinceRecords?: readonly ProvinceRecordDto[];
  jurisdictionRecords?: readonly JurisdictionRecordDto[];
  markerPlacement?: {
    provinceMap: ProvinceIdentityMap;
    countyIndex: CountyAdministrativeIndex;
    preferredByProvince?: ReadonlyMap<number, { col: number; row: number }>;
  };
}

export interface IsoHoverPoint {
  x: number;
  y: number;
}

export interface IsoCountyHover {
  provinceId: number;
  commanderyId: number;
  provinceRecordId?: string;
  jurisdictionId?: string;
  commanderyRecordId?: string;
  spatialProvinceName?: string;
  jurisdictionNameCh?: string;
  commanderyNameCh?: string;
  hierarchyPath?: string;
  provinceOccupantNationId?: number;
  provinceOccupantNationName?: string;
  jurisdictionOwnerNationId?: number;
  jurisdictionOwnerNationName?: string;
  commanderyControllerNationId?: number;
  commanderyControllerNationName?: string;
  displayedOwnerNationName?: string;
  provinceJurisdictionMismatch?: boolean;
  jurisdictionCommanderyMismatch?: boolean;
  ownershipMismatch?: boolean;
  regionName: string;
  commanderyName: string;
  countyName: string;
  displayName?: string;
  level: number;
  nationId: number;
  nationName?: string;
  nationColor?: string;
}

export interface IsoActivation {
  pointerType: string;
}

export type InitialFocusProfile = 'current-city-close';

export interface HanMapCanvasProps extends IsoSceneOptions {
  mapCode: string;
  tiles?: HanTiles | null;
  /** Strong byte identity for explicitly supplied, already-validated terrain. */
  tilesSha256?: string;
  strategicTopology?: StrategicMapSnapshot;
  selectedServerRoute?: StrategicMapRoute | null;
  currentServerId?: string;
  terrainUrl?: string | ((mapCode: string) => string);
  provinceUrl?: string | ((mapCode: string) => string);
  provinceMap?: ProvinceIdentityMap | null;
  cities?: readonly IsoCityOverlay[];
  administrativeOwnership?: AdministrativeOwnershipData;
  sourceSize?: IsoSourceSize;
  initialFocus?: InitialFocusProfile;
  hideCityNames?: boolean;
  className?: string;
  style?: CSSProperties;
  ariaLabel?: string;
  onCityHover?: (city: IsoCityOverlay | null, point?: IsoHoverPoint) => void;
  onCountyHover?: (county: IsoCountyHover | null, point?: IsoHoverPoint) => void;
  onCityActivate?: (city: IsoCityOverlay, activation?: IsoActivation) => void;
  onMissing?: () => void;
  onViewChange?: (view: IsoView) => void;
}

const TERRAIN = [
  '#1d3f5c',
  '#5f7f4a',
  '#6b6257',
  '#3d6b8a',
  '#2f5f7a',
  '#b3a271',
  '#8a7f5c',
  '#7a7050',
  '#6f7a4e',
  '#000000',
] as const;

export function terrainColorFor(value: number | string): string {
  return TERRAIN[Number(value)] ?? TERRAIN[0];
}

const NEUTRAL_COLOR = '#555555';
const CASTLE_FILL = '#8b8172';
const CASTLE_STROKE = '#f3dfb0';
const CITY_LEVELS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] as const;
const CITY_MARKER_URLS: Record<number, string> = Object.fromEntries(
  CITY_LEVELS.map((level) => [level, `/city/cast_${level}.png`]),
);
const CITY_LEVEL_VISUAL_EXTENT: Record<number, number> = {
  1: 42, 2: 40, 3: 44, 4: 40,
  5: 48, 6: 52, 7: 56, 8: 60, 9: 62,
  10: 34, 11: 27,
};
const CITY_LEVEL_MARKER_SPEC = {
  pixelWidth: 64,
  pixelHeight: 64,
  anchorX: 32,
  anchorY: 63,
  pixelRatio: 2,
} as const;
// 구형 3-tier export 검증 계약. 새 한 지도 런타임은 CITY_LEVEL_MARKER_SPEC을 쓴다.
export const CITY_MARKER_SPECS = {
  county: { pixelWidth: 28, pixelHeight: 32, anchorX: 14, anchorY: 30, pixelRatio: 2 },
  commandery: { pixelWidth: 36, pixelHeight: 40, anchorX: 18, anchorY: 38, pixelRatio: 2 },
  capital: { pixelWidth: 44, pixelHeight: 48, anchorX: 22, anchorY: 46, pixelRatio: 2 },
} as const;
const PROVINCE_BORDER = 'rgba(18,20,22,0.58)';
const COMMANDERY_BORDER_DARK = 'rgba(10,12,14,0.82)';
const COMMANDERY_BORDER_LIGHT = 'rgba(225,210,163,0.76)';
const DEFAULT_SOURCE: IsoSourceSize = { width: 700, height: 610 };
export const TIER2_MARKER_ZOOM: Record<string, number> = { COUNTY: 2.19, MARQUISATE: 2.19 };
export const TIER2_LABEL_ZOOM: Record<string, number> = {
  COUNTY: 5.5,
  EXTERNAL_SETTLEMENT: 5.5,
  MARQUISATE: 5.5,
};

export function tierZoom(table: Record<string, number>, kind: string, fit: number): number | undefined {
  const factor = table[kind];
  return factor === undefined ? undefined : factor * fit;
}

export function labelZoomFor(kind: string, fit: number, dpr = 1): number | undefined {
  const absolute = TIER2_LABEL_ZOOM[kind];
  if (absolute === undefined) return undefined;
  const backingRatio = effectiveDpr(dpr);
  const absoluteBacking = absolute * backingRatio;
  return Math.min(
    maxScaleForDpr(dpr) - 0.5 * backingRatio,
    Math.max(absoluteBacking, tierZoom(TIER2_MARKER_ZOOM, kind, fit) ?? absoluteBacking),
  );
}

export function seatLabel(name: string): string {
  return name.length > 1 && name.endsWith('현') ? name.slice(0, -1) : name;
}

export function labelledRegions(regions: HanTiles['regions'], minCells = 120) {
  return regions.filter((region) => region.cells >= minCells);
}

export function initialView(
  width: number,
  height: number,
  grid: GridSize,
  _tiles: HanTiles,
  dpr = 1,
): IsoView {
  const scale = Math.min(fitScale(width, height, grid), maxScaleForDpr(dpr) * 0.9);
  return viewAt(width, height, (grid.cols - 1) / 2, (grid.rows - 1) / 2, scale);
}

export function initialFocusedView(
  width: number,
  height: number,
  grid: GridSize,
  tiles: HanTiles,
  dpr = 1,
  current?: { col: number; row: number },
  profile?: InitialFocusProfile,
): IsoView {
  const fitted = initialView(width, height, grid, tiles, dpr);
  if (!current) return fitted;
  const targetScale = profile === 'current-city-close'
    ? 10 * effectiveDpr(dpr)
    : labelZoomFor('COUNTY', fitted.scale, dpr) ?? fitted.scale;
  const scale = Math.min(maxScaleForDpr(dpr) * 0.9, Math.max(fitted.scale, targetScale));
  return viewAt(width, height, current.col, current.row, scale);
}

export function expandOwner(rle: [number, number][], cells: number): Int16Array {
  const result = new Int16Array(cells);
  let index = 0;
  for (const [value, count] of rle) {
    for (let offset = 0; offset < count && index < cells; offset += 1) {
      result[index] = value;
      index += 1;
    }
  }
  return result;
}

export function mapCityToTile(
  city: Pick<IsoCityOverlay, 'x' | 'y'>,
  grid: GridSize,
  source: IsoSourceSize,
): { col: number; row: number } {
  return {
    col: city.x * grid.cols / source.width,
    row: city.y * grid.rows / source.height,
  };
}

export function provinceLayerRuntimeCities(
  cities: readonly IsoCityOverlay[],
): IsoCityOverlay[] {
  return cities.filter((city) => (
    city.provinceId !== undefined
    && Number.isInteger(city.provinceId)
    && city.provinceId >= 0
  ));
}

function cityMarkerTile(
  city: IsoCityOverlay,
  grid: GridSize,
  source: IsoSourceSize,
  placement?: IsoSceneOptions['markerPlacement'],
): IsoMarkerPosition {
  const mapped = mapCityToTile(city, grid, source);
  if (!placement) return mapped;

  const { provinceMap, countyIndex, preferredByProvince } = placement;
  const commandery = city.commanderyName == null
    ? undefined
    : countyIndex.commanderyByName.get(city.commanderyName);
  if (city.commanderyName != null && commandery === undefined) return mapped;
  if (city.provinceId !== undefined
    && city.provinceId >= 0
    && city.provinceId < countyIndex.commanderyByProvince.length) {
    const preferred = preferredByProvince?.get(city.provinceId);
    if (preferred) return { ...preferred, provinceId: city.provinceId };
    for (let index = 0; index < provinceMap.provinces.length; index += 1) {
      if (provinceMap.provinces[index] !== city.provinceId) continue;
      return {
        col: index % provinceMap.width,
        row: Math.floor(index / provinceMap.width),
        provinceId: city.provinceId,
      };
    }
  }
  const resolved = resolveProvincePlacement(
    provinceMap, countyIndex, mapped.col, mapped.row, commandery,
  );
  return resolved ?? mapped;
}

export function buildIsoScene(
  tiles: HanTiles,
  cities: readonly IsoCityOverlay[],
  source: IsoSourceSize,
  options: IsoSceneOptions,
): IsoScene {
  const grid = { cols: tiles._meta.cols, rows: tiles._meta.rows };
  const jurisdictionById = new Map(
    options.jurisdictionRecords?.map((record) => [record.id, record]) ?? [],
  );
  const sceneCities = cities.map((city) => {
    const markerPosition = options.markerPositions?.get(city.id)
      ?? cityMarkerTile(city, grid, source, options.markerPlacement);
    const { col, row } = markerPosition;
    const owned = isOwnedNationVisual(city.nationId, city.nationColor);
    const provinceRecord = markerPosition.provinceId === undefined
      ? undefined
      : options.provinceRecords?.[markerPosition.provinceId];
    const jurisdiction = city.jurisdictionId
      ? jurisdictionById.get(city.jurisdictionId)
      : provinceRecord?.jurisdictionId
        ? jurisdictionById.get(provinceRecord.jurisdictionId)
        : undefined;
    const territoryColor = owned && city.nationColor ? city.nationColor : NEUTRAL_COLOR;
    const layers = [`castle:${city.level}`];
    if (owned) layers.push('flag');
    if (city.isCapital) layers.push('capital');
    if ((city.state ?? 0) > 0) layers.push(`event:${city.state}`);
    layers.push(`supply:${owned && city.supply === false ? 'off' : 'on'}`);
    if (options.currentCityId === city.id) layers.push('current');
    if (options.selectedCityId === city.id) layers.push('selected');
    layers.push(`name:${city.name}`);
    return {
      ...city,
      col,
      row,
      provinceId: markerPosition.provinceId,
      visualClearance: markerPosition.visualClearance,
      mapLabel: city.mapLabel ?? jurisdiction?.displayName ?? provinceRecord?.displayName ?? city.name,
      provinceKind: city.administrativeKind ?? jurisdiction?.kind ?? provinceRecord?.kind,
      color: territoryColor,
      territoryColor,
      iconColor: CASTLE_FILL,
      layers,
    };
  });
  const markerByAdministrativeKey = new Map<string, IsoSceneCity>();
  const unbound: IsoSceneCity[] = [];
  const declaredProvinceByCity = new Map(cities.map((city) => [city.id, city.provinceId]));
  const priority = (city: IsoSceneCity): number => (
    (declaredProvinceByCity.get(city.id) === city.provinceId ? 10000 : 0)
    + (city.isCapital ? 1000 : 0)
    + (city.isCommanderySeat ? 100 : 0)
    + (city.level >= 5 && city.level <= 9 ? 10 : 0)
    + (options.currentCityId === city.id ? 2 : 0)
    + (options.selectedCityId === city.id ? 1 : 0)
  );
  for (const city of sceneCities) {
    if (city.provinceId === undefined) {
      unbound.push(city);
      continue;
    }
    const renderKey = city.administrativeKind === 'COMMANDERY' && city.commanderyId
      ? `commandery:${city.commanderyId}`
      : city.jurisdictionId
        ? `jurisdiction:${city.jurisdictionId}`
        : `province:${city.provinceId}`;
    const prior = markerByAdministrativeKey.get(renderKey);
    const selected = !prior || priority(city) > priority(prior)
      || (priority(city) === priority(prior) && city.id < prior.id)
      ? city
      : prior;
    const other = selected === city ? prior : city;
    const representativeLayers = ['capital', 'current', 'selected']
      .filter((layer) => selected.layers.includes(layer) || other?.layers.includes(layer));
    markerByAdministrativeKey.set(renderKey, {
      ...selected,
      isCapital: selected.isCapital || other?.isCapital,
      layers: [
        ...selected.layers.filter((layer) => !representativeLayers.includes(layer)),
        ...representativeLayers,
      ],
    });
  }
  return {
    terrain: tiles.terrain,
    roads: [],
    cities: [...markerByAdministrativeKey.values(), ...unbound].sort((left, right) => left.id - right.id),
  };
}

export function completeJurisdictionOverlays(
  tiles: HanTiles,
  runtimeCities: readonly IsoCityOverlay[],
  anchors: readonly (ProvinceVisualAnchor | undefined)[],
  source: IsoSourceSize,
  layer: 'COUNTY' | 'COMMANDERY' = 'COUNTY',
  _resolvedPositions?: ReadonlyMap<number, { provinceId?: number }>,
  currentCityId?: number | null,
  provinceMap?: ProvinceIdentityMap | null,
): IsoCityOverlay[] {
  const jurisdictions = tiles.jurisdictionRecords;
  const provinces = tiles.provinceRecords;
  if (!jurisdictions || !provinces) return [...runtimeCities];

  const provinceIndexById = new Map(provinces.map((province, index) => [province.id, index]));
  const parentById = new Map(tiles.parentRegions?.map((parent) => [parent.id, parent]) ?? []);
  // Coordinate recovery is presentation-only. Administrative identity is valid
  // only when the runtime payload carries an explicit canonical provinceId.
  const locatedRuntimeCities = runtimeCities;
  const seatProvinceIndex = (jurisdiction: JurisdictionRecordDto): number => {
    const memberIndexes = jurisdiction.provinceIds
      .map((provinceId) => provinceIndexById.get(provinceId))
      .filter((provinceIndex): provinceIndex is number => provinceIndex !== undefined);
    const direct = provinceIndexById.get(jurisdiction.seatPlaceId);
    if (direct !== undefined && memberIndexes.includes(direct)) return direct;
    const seatPlace = tiles.cities.find((city) => city.id === jurisdiction.seatPlaceId);
    if (seatPlace && provinceMap) {
      const col = Math.round(seatPlace.col);
      const row = Math.round(seatPlace.row);
      const resolved = col >= 0 && row >= 0 && col < provinceMap.width && row < provinceMap.height
        ? provinceMap.provinces[row * provinceMap.width + col] : -1;
      if (resolved >= 0 && memberIndexes.includes(resolved)) return resolved;
    }
    throw new Error(`Jurisdiction ${jurisdiction.id} seat ${jurisdiction.seatPlaceId} has no spatial province`);
  };
  if (layer === 'COMMANDERY') {
    return (tiles.parentRegions ?? []).flatMap((parent, parentIndex): IsoCityOverlay[] => {
      const commandery = tiles.commanderyRecords?.find((record) => record.id === parent.id);
      const seatJurisdiction = commandery
        ? jurisdictions.find((jurisdiction) => jurisdiction.id === commandery.seatJurisdictionId)
        : undefined;
      const parentProvinceIndexes = provinces.flatMap((province, provinceIndex) => (
        province.parentRegionId === parent.id ? [provinceIndex] : []
      ));
      const legacySeatCityIndex = commandery ? undefined : tiles.juns[parentIndex]?.seat;
      const canonicalSeatIndex = seatJurisdiction ? seatProvinceIndex(seatJurisdiction) : undefined;
      const provinceId = (canonicalSeatIndex !== undefined && anchors[canonicalSeatIndex]
        ? canonicalSeatIndex : undefined)
        ?? parentProvinceIndexes.find((candidate) => (
          legacySeatCityIndex !== undefined && provinces[candidate]?.cityIndex === legacySeatCityIndex
        ))
        ?? parentProvinceIndexes.find((candidate) => anchors[candidate] !== undefined);
      if (provinceId === undefined) return [];
      const anchor = anchors[provinceId];
      if (!anchor) return [];
      const runtime = locatedRuntimeCities.find((city) => city.provinceId === provinceId);
      const isCurrentSeat = runtime?.id === currentCityId;
      const jurisdictionId = provinces[provinceId]?.jurisdictionId;
      const seatLabel = seatJurisdiction?.displayName
        ?? jurisdictions.find((jurisdiction) => jurisdiction.id === jurisdictionId)?.displayName;
      return [{
        ...(runtime ?? {
          id: -(100_000 + parentIndex),
          level: 8,
          nationId: 0,
          interactive: false,
        }),
        name: parent.displayName,
        mapLabel: isCurrentSeat && seatLabel
          ? `${parent.displayName} · ${seatLabel}`
          : parent.displayName,
        administrativeKind: 'COMMANDERY',
        x: anchor.col * source.width / tiles._meta.cols,
        y: anchor.row * source.height / tiles._meta.rows,
        provinceId,
        jurisdictionId,
        commanderyId: parent.id,
      }];
    });
  }
  const runtimeByJurisdiction = new Map<string, IsoCityOverlay>();
  for (const city of [...locatedRuntimeCities].sort((left, right) => left.id - right.id)) {
    const jurisdictionId = city.jurisdictionId
      ?? (city.provinceId === undefined ? undefined : provinces[city.provinceId]?.jurisdictionId);
    if (!jurisdictionId) continue;
    const prior = runtimeByJurisdiction.get(jurisdictionId);
    if (prior) {
      throw new Error(`Runtime cities ${prior.id}, ${city.id} resolve to the same jurisdiction ${jurisdictionId}`);
    }
    runtimeByJurisdiction.set(jurisdictionId, { ...city, jurisdictionId });
  }

  return jurisdictions.map((jurisdiction, index): IsoCityOverlay => {
    const provinceId = seatProvinceIndex(jurisdiction);
    const anchor = anchors[provinceId];
    if (!anchor) throw new Error(`Jurisdiction ${jurisdiction.id} seat province ${provinceId} has no visual anchor`);
    const tileCityIndex = provinces[provinceId]?.cityIndex;
    const tileCity = tileCityIndex == null ? undefined : tiles.cities[tileCityIndex];
    const runtime = runtimeByJurisdiction.get(jurisdiction.id);
    return {
      ...(runtime ?? {
        id: -(index + 1),
        nationId: 0,
        interactive: false,
      }),
      name: jurisdiction.displayName,
      mapLabel: jurisdiction.displayName,
      level: runtime?.level ?? tileCity?.level ?? 5,
      x: anchor.col * source.width / tiles._meta.cols,
      y: anchor.row * source.height / tiles._meta.rows,
      commanderyName: parentById.get(jurisdiction.commanderyId)?.displayName,
      provinceId,
      jurisdictionId: jurisdiction.id,
      commanderyId: jurisdiction.commanderyId,
    };
  });
}

export function sceneGolden(scene: IsoScene): string {
  const lines = [`terrain:${scene.terrain.join('/')}`];
  for (const road of scene.roads) {
    lines.push(`road:${road.from.join(',')}>${road.to.join(',')}`);
  }
  for (const city of scene.cities) {
    lines.push(
      `city:${city.id}@${city.col.toFixed(3)},${city.row.toFixed(3)}` +
      ` territory=${city.territoryColor} icon=${city.iconColor}` +
      `[${city.layers.join(',')}]`,
    );
  }
  return lines.join('\n');
}

function bakeTerrain(tiles: HanTiles): HTMLCanvasElement | null {
  const canvas = document.createElement('canvas');
  canvas.width = tiles._meta.cols;
  canvas.height = tiles._meta.rows;
  const context = canvas.getContext('2d');
  if (!context) return null;
  const image = context.createImageData(tiles._meta.cols, tiles._meta.rows);
  for (let row = 0; row < tiles._meta.rows; row += 1) {
    const terrainRow = tiles.terrain[row] ?? '';
    for (let col = 0; col < tiles._meta.cols; col += 1) {
      const color = terrainColorFor(terrainRow[col]);
      const offset = (row * tiles._meta.cols + col) * 4;
      image.data[offset] = Number.parseInt(color.slice(1, 3), 16);
      image.data[offset + 1] = Number.parseInt(color.slice(3, 5), 16);
      image.data[offset + 2] = Number.parseInt(color.slice(5, 7), 16);
      image.data[offset + 3] = 255;
    }
  }
  context.putImageData(image, 0, 0);
  return canvas;
}

interface PoliticalPaths {
  province: Path2D;
  jurisdiction: Path2D;
  commandery: Path2D;
}

function pathFromEdges(edges: readonly ProvinceEdge[]): Path2D {
  const path = new Path2D();
  for (const edge of edges) {
    path.moveTo(edge.x1, edge.y1);
    path.lineTo(edge.x2, edge.y2);
  }
  return path;
}

function bakePoliticalPaths(map: ProvinceIdentityMap, countyIndex: CountyAdministrativeIndex | null): PoliticalPaths {
  return {
    province: pathFromEdges(map.provinceEdges),
    jurisdiction: pathFromEdges(countyIndex?.jurisdictionEdges ?? map.provinceEdges),
    commandery: pathFromEdges(map.commanderyEdges),
  };
}

function bakePoliticalFill(
  map: ProvinceIdentityMap,
  binding: ProvinceOwnershipBinding,
): HTMLCanvasElement | null {
  const canvas = document.createElement('canvas');
  canvas.width = map.width;
  canvas.height = map.height;
  const context = canvas.getContext('2d');
  if (!context) return null;
  const image = context.createImageData(map.width, map.height);
  image.data.set(composeProvincePixels(map, binding));
  context.putImageData(image, 0, 0);
  return canvas;
}

function matchesGrid(map: ProvinceIdentityMap | null, grid: GridSize): map is ProvinceIdentityMap {
  return map != null
    && map.width === grid.cols
    && map.height === grid.rows
    && map.provinces.length === grid.cols * grid.rows
    && map.commanderies.length === grid.cols * grid.rows;
}

function politicalOwnershipKey(cities: readonly IsoCityOverlay[]): string {
  return JSON.stringify(cities.map((city) => [
    city.id,
    city.x,
    city.y,
    city.nationId,
    city.nationColor ?? null,
    city.commanderyName ?? null,
    city.provinceId ?? null,
  ]));
}

type CityMarkerImages = Partial<Record<number, HTMLImageElement>>;

export type CityMarkerZoom = 0.5 | 0.75 | 1 | 1.5;

export function cityMarkerZoomStep(scale: number, dpr: number): CityMarkerZoom {
  const cssTileScale = scale / effectiveDpr(dpr);
  if (cssTileScale >= 16) return 1.5;
  if (cssTileScale >= 10) return 1;
  if (cssTileScale >= 4) return 0.75;
  return 0.5;
}

export function cityLabelMetrics(scale: number, dpr: number) {
  const backingRatio = effectiveDpr(dpr);
  const cssTileScale = scale / backingRatio;
  const cssFontSize = Math.max(11, Math.min(14, 10 + cssTileScale * 0.25));
  return {
    cssFontSize,
    fontSize: cssFontSize * backingRatio,
    strokeWidth: 2.5 * backingRatio,
  };
}

export function cityMarkerDrawBox(level: number, x: number, y: number, dpr: number, zoom = 1) {
  const spec = CITY_LEVEL_MARKER_SPEC;
  const scale = dpr * zoom / spec.pixelRatio;
  return {
    x: x - spec.anchorX * scale,
    y: y - spec.anchorY * scale,
    width: spec.pixelWidth * scale,
    height: spec.pixelHeight * scale,
    visualExtent: (CITY_LEVEL_VISUAL_EXTENT[level] ?? CITY_LEVEL_VISUAL_EXTENT[5]) * zoom,
  };
}

export function cityMarkerHitBox(level: number, x: number, y: number, dpr: number, zoom = 1) {
  const box = cityMarkerDrawBox(level, x, y, dpr, zoom);
  const padding = 6 * dpr * zoom;
  return {
    left: box.x - padding,
    top: box.y - padding,
    right: box.x + box.width + padding,
    bottom: box.y + box.height + padding,
  };
}

export function cityMarkerRadius(level: number, dpr: number): number {
  const cssRadius = Math.max(7, Math.min(18, 5 + (CITY_LEVEL_VISUAL_EXTENT[level] ?? 48) * 0.2));
  return cssRadius * dpr;
}

export function flagClothPoints(
  x: number,
  y: number,
  radius: number,
  supplied: boolean,
  phase: number,
): [number, number][] {
  const poleX = x + radius * 0.55;
  const top = y - radius * 1.6;
  if (!supplied) {
    return [
      [poleX, top],
      [poleX + radius * 0.42, top + radius * 0.45],
      [poleX + radius * 0.28, top + radius * 1.05],
      [poleX, top + radius * 0.76],
    ];
  }
  const wave = [-0.12, 0.08, -0.04][((phase % 3) + 3) % 3] * radius;
  return [
    [poleX, top],
    [poleX + radius * 0.9, top + radius * 0.25 + wave],
    [poleX + radius * 0.86, top + radius * 0.7 - wave * 0.5],
    [poleX, top + radius * 0.65],
  ];
}

export function cityFallbackHitBox(x: number, y: number, radius: number) {
  return {
    left: x - radius * 0.7,
    top: y - radius * 0.9,
    right: x + radius * 0.7,
    bottom: y + radius * 0.45,
  };
}

export function provinceAtScreenPoint(
  map: ProvinceIdentityMap,
  view: IsoView,
  x: number,
  y: number,
): number {
  const [rawCol, rawRow] = screenToCell(x, y, view);
  const col = Math.round(rawCol);
  const row = Math.round(rawRow);
  if (col < 0 || row < 0 || col >= map.width || row >= map.height) return -1;
  return map.provinces[row * map.width + col];
}

export function screenBoxInsideProvince(
  map: ProvinceIdentityMap,
  provinceId: number,
  view: IsoView,
  box: { left: number; top: number; right: number; bottom: number },
): boolean {
  if (provinceId < 0 || ![box.left, box.top, box.right, box.bottom].every(Number.isFinite)
    || box.right < box.left || box.bottom < box.top) return false;
  for (let y = Math.floor(box.top); y <= Math.ceil(box.bottom); y += 1) {
    for (let x = Math.floor(box.left); x <= Math.ceil(box.right); x += 1) {
      if (provinceAtScreenPoint(map, view, x, y) !== provinceId) return false;
    }
  }
  return true;
}

/**
 * Constant-work containment proof for a box centered on a visual anchor.
 * The anchor clearance is the Chebyshev cell distance to the province edge,
 * so an affine screen-space rectangle is safe when all four inverse-mapped
 * corners remain inside that guaranteed same-province cell square.
 */
export function screenBoxInsideVisualClearance(
  anchorCol: number,
  anchorRow: number,
  clearance: number,
  view: IsoView,
  box: { left: number; top: number; right: number; bottom: number },
): boolean {
  if (clearance < 0 || !Number.isFinite(clearance)
    || ![box.left, box.top, box.right, box.bottom].every(Number.isFinite)
    || box.right < box.left || box.bottom < box.top) return false;
  const safeRadius = clearance + 0.5 - 1e-6;
  return [
    [box.left, box.top], [box.right, box.top],
    [box.left, box.bottom], [box.right, box.bottom],
  ].every(([x, y]) => {
    const [col, row] = screenToCell(x, y, view);
    return Math.abs(col - anchorCol) <= safeRadius
      && Math.abs(row - anchorRow) <= safeRadius;
  });
}

function markerLevel(city: IsoCityOverlay): number {
  return Number.isInteger(city.level) && city.level >= 1 && city.level <= 11 ? city.level : 5;
}

function starPath(context: CanvasRenderingContext2D, x: number, y: number, radius: number) {
  context.beginPath();
  for (let point = 0; point < 10; point += 1) {
    const angle = -Math.PI / 2 + point * Math.PI / 5;
    const length = point % 2 === 0 ? radius : radius * 0.45;
    const px = x + Math.cos(angle) * length;
    const py = y + Math.sin(angle) * length;
    if (point === 0) context.moveTo(px, py);
    else context.lineTo(px, py);
  }
  context.closePath();
}

const CITY_MARKER_ZOOM_STEPS: readonly CityMarkerZoom[] = [1.5, 1, 0.75, 0.5];

function detailedCityVisualBox(
  city: IsoSceneCity,
  level: number,
  x: number,
  y: number,
  dpr: number,
  zoom: CityMarkerZoom,
) {
  const marker = cityMarkerDrawBox(level, x, y, dpr, zoom);
  const radius = cityMarkerRadius(level, dpr) * zoom;
  const bounds = {
    left: marker.x,
    top: marker.y,
    right: marker.x + marker.width,
    bottom: marker.y + marker.height,
  };
  const include = (left: number, top: number, right: number, bottom: number) => {
    bounds.left = Math.min(bounds.left, left);
    bounds.top = Math.min(bounds.top, top);
    bounds.right = Math.max(bounds.right, right);
    bounds.bottom = Math.max(bounds.bottom, bottom);
  };
  const strokePadding = 2 * dpr;
  if (isOwnedNationVisual(city.nationId, city.nationColor)) {
    const cloth = flagClothPoints(x, y, radius, city.supply !== false, 0);
    include(
      Math.min(...cloth.map(([px]) => px)) - strokePadding,
      Math.min(y - radius * 1.65, ...cloth.map(([, py]) => py)) - strokePadding,
      Math.max(x + radius * 0.55, ...cloth.map(([px]) => px)) + strokePadding,
      Math.max(y - radius * 0.45, ...cloth.map(([, py]) => py)) + strokePadding,
    );
  }
  if (city.isCapital) {
    const starRadius = Math.max(4, radius * 0.42);
    include(
      x + radius * 1.2 - starRadius - dpr,
      y - radius * 1.8 - starRadius - dpr,
      x + radius * 1.2 + starRadius + dpr,
      y - radius * 1.8 + starRadius + dpr,
    );
  }
  if ((city.state ?? 0) > 0) {
    const badgeRadius = Math.max(5, radius * 0.42);
    include(
      x - radius * 1.05 - badgeRadius,
      y - radius * 0.9 - badgeRadius,
      x - radius * 1.05 + badgeRadius,
      y - radius * 0.9 + badgeRadius,
    );
  }
  if (city.layers.includes('current')) {
    include(
      x - radius * 1.35 - strokePadding,
      y - radius * 1.35 - strokePadding,
      x + radius * 1.35 + strokePadding,
      y + radius * 1.35 + strokePadding,
    );
  }
  if (city.layers.includes('selected')) {
    include(
      x - radius - strokePadding,
      y - radius - strokePadding,
      x + radius + strokePadding,
      y + radius + strokePadding,
    );
  }
  return bounds;
}

function containedCityMarkerZoom(
  city: IsoSceneCity,
  level: number,
  x: number,
  y: number,
  requested: CityMarkerZoom,
  dpr: number,
  view: IsoView,
): CityMarkerZoom | undefined {
  if (city.provinceId === undefined || city.visualClearance === undefined) return requested;
  for (const zoom of CITY_MARKER_ZOOM_STEPS) {
    if (zoom > requested) continue;
    if (screenBoxInsideVisualClearance(
      city.col,
      city.row,
      city.visualClearance,
      view,
      detailedCityVisualBox(city, level, x, y, dpr, zoom),
    )) return zoom;
  }
  return undefined;
}

export function overviewCityVisualBox(
  x: number,
  y: number,
  scale: number,
  _dpr: number,
  clearance: number,
) {
  const safeRadius = Math.max(0, clearance + 0.5 - 1e-3);
  const horizontal = safeRadius * scale * 0.72;
  const vertical = safeRadius * scale * 0.28;
  return {
    left: x - horizontal,
    top: y - vertical,
    right: x + horizontal,
    bottom: y + vertical,
  };
}

function drawOverviewCityGlyph(
  context: CanvasRenderingContext2D,
  city: IsoSceneCity,
  x: number,
  y: number,
  scale: number,
  dpr: number,
) {
  const box = overviewCityVisualBox(x, y, scale, dpr, city.visualClearance ?? 0);
  const horizontal = (box.right - box.left) / 2;
  const vertical = (box.bottom - box.top) / 2;
  const detail = Math.min(horizontal, vertical);
  const stroke = Math.max(detail * 0.08, Math.min(dpr, detail * 0.2));
  const pathHorizontal = Math.max(0, horizontal - stroke / 2);
  const pathVertical = Math.max(0, vertical - stroke / 2);
  context.lineJoin = 'bevel';
  context.beginPath();
  context.moveTo(x, y - pathVertical);
  context.lineTo(x + pathHorizontal, y);
  context.lineTo(x, y + pathVertical);
  context.lineTo(x - pathHorizontal, y);
  context.closePath();
  context.fillStyle = CASTLE_FILL;
  context.fill();
  context.strokeStyle = CASTLE_STROKE;
  context.lineWidth = stroke;
  context.stroke();

  if (isOwnedNationVisual(city.nationId, city.nationColor)) {
    context.beginPath();
    const flagTop = y - pathVertical * 0.72;
    context.moveTo(x, flagTop);
    context.lineTo(x + pathHorizontal * 0.45, flagTop + (city.supply === false ? pathVertical * 0.45 : 0));
    context.lineTo(x, y - pathVertical * 0.08);
    context.closePath();
    context.fillStyle = city.territoryColor;
    context.fill();
    context.strokeStyle = '#21180f';
    context.lineWidth = stroke;
    context.stroke();
  }
  if (city.isCapital) {
    context.beginPath();
    context.arc(x + pathHorizontal * 0.38, y, detail * 0.18, 0, Math.PI * 2);
    context.fillStyle = '#ffd84f';
    context.fill();
  }
  if ((city.state ?? 0) > 0) {
    context.beginPath();
    context.arc(x - pathHorizontal * 0.38, y, detail * 0.18, 0, Math.PI * 2);
    context.fillStyle = '#b72f2f';
    context.fill();
  }
  if (city.layers.includes('selected')) {
    context.strokeStyle = '#ffd84f';
    context.lineWidth = stroke;
    context.strokeRect(
      x - pathHorizontal * 0.82,
      y - pathVertical * 0.82,
      pathHorizontal * 1.64,
      pathVertical * 1.64,
    );
  }
  return box;
}

function drawCurrentLocationOverlay(
  context: CanvasRenderingContext2D,
  x: number,
  y: number,
  markerRadius: number,
  dpr: number,
  phase: number,
) {
  const ratio = effectiveDpr(dpr);
  const radius = Math.max(markerRadius, 7 * ratio);
  const haloRadius = radius * 1.35 + phase * 0.75 * ratio;
  const chevronCenterY = y - haloRadius - 7 * ratio;
  const chipCenterY = y - haloRadius - 28 * ratio;
  const chipText = '내 위치';

  context.save();
  context.lineJoin = 'round';
  context.lineCap = 'round';

  context.beginPath();
  context.arc(x, y, haloRadius, 0, Math.PI * 2);
  context.strokeStyle = 'rgba(18,12,6,0.92)';
  context.lineWidth = 6 * ratio;
  context.stroke();

  context.beginPath();
  context.arc(x, y, haloRadius, 0, Math.PI * 2);
  context.strokeStyle = '#ffffff';
  context.lineWidth = 3.5 * ratio;
  context.stroke();

  context.beginPath();
  context.arc(x, y, haloRadius, 0, Math.PI * 2);
  context.globalAlpha = phase === 0 ? 0.86 : 1;
  context.strokeStyle = '#ffd84f';
  context.lineWidth = 1.5 * ratio;
  context.stroke();
  context.globalAlpha = 1;

  context.beginPath();
  context.moveTo(x - 6 * ratio, chevronCenterY + 4 * ratio);
  context.lineTo(x, chevronCenterY - 4 * ratio);
  context.lineTo(x + 6 * ratio, chevronCenterY + 4 * ratio);
  context.lineTo(x, chevronCenterY + 1 * ratio);
  context.closePath();
  context.fillStyle = '#ffd84f';
  context.fill();
  context.strokeStyle = 'rgba(18,12,6,0.92)';
  context.lineWidth = 2 * ratio;
  context.stroke();

  context.font = `bold ${10 * ratio}px sans-serif`;
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  const chipWidth = context.measureText(chipText).width + 10 * ratio;
  const chipHeight = 16 * ratio;
  context.fillStyle = 'rgba(18,12,6,0.92)';
  context.fillRect(
    x - chipWidth / 2,
    chipCenterY - chipHeight / 2,
    chipWidth,
    chipHeight,
  );
  context.strokeStyle = '#ffd84f';
  context.lineWidth = ratio;
  context.strokeRect(
    x - chipWidth / 2,
    chipCenterY - chipHeight / 2,
    chipWidth,
    chipHeight,
  );
  context.fillStyle = '#ffffff';
  context.fillText(chipText, x, chipCenterY);
  context.restore();
}

function drawScene(
  canvas: HTMLCanvasElement,
  terrain: HTMLCanvasElement,
  political: HTMLCanvasElement | null,
  paths: PoliticalPaths | null,
  provinceMap: ProvinceIdentityMap | null,
  scene: IsoScene,
  view: IsoView,
  hideCityNames: boolean,
  dpr: number,
  markerImages: CityMarkerImages,
  flagPhase: number,
  selfLocationPhase: number,
  administrativeLayer: AdministrativeLayer,
  strategic: { scene: StrategicMapScene; controls: ReadonlyMap<string, StrategicWaterControl>;
    visible: boolean; route: readonly { col: number; row: number }[] | null } | null,
): CityHitBox[] {
  const context = canvas.getContext('2d');
  if (!context) return [];
  const width = canvas.width;
  const height = canvas.height;
  const scale = view.scale;
  const fittedScale = provinceMap
    ? fitScale(width, height, { cols: provinceMap.width, rows: provinceMap.height })
    : 0;
  context.setTransform(1, 0, 0, 1, 0, 0);
  context.clearRect(0, 0, width, height);
  context.save();
  context.transform(scale, scale / 2, -scale, scale / 2, view.ox, view.oy);
  context.imageSmoothingEnabled = scale < 2;
  context.drawImage(terrain, -0.5, -0.5);
  if (political) {
    context.imageSmoothingEnabled = false;
    context.drawImage(political, -0.5, -0.5);
  }
  if (strategic?.visible) {
    for (const shape of strategic.scene.zones) {
      context.fillStyle = shape.zone.kind === 'COASTAL_SEA' ? 'rgba(49,190,222,0.42)' : 'rgba(73,131,248,0.42)';
      context.fill(shape.fill);
      const control = strategic.controls.get(shape.zone.id);
      context.strokeStyle = control?.status === 'BLOCKED' ? '#ff8686'
        : control?.status === 'CONTESTED' ? '#ffc35c' : '#b6e8ff';
      context.lineWidth = 1.5 * dpr / scale;
      context.stroke(shape.outline);
      if (control?.status !== 'OPEN') {
        context.lineWidth = 0.7 * dpr / scale;
        context.stroke(shape.hatch);
      }
    }
  }
  if (paths) {
    if (administrativeLayer === 'PROVINCE') {
      context.strokeStyle = PROVINCE_BORDER;
      context.lineWidth = dpr / scale;
      context.stroke(paths.province);
    } else if (administrativeLayer === 'JURISDICTION') {
      context.strokeStyle = PROVINCE_BORDER;
      context.lineWidth = 1.5 * dpr / scale;
      context.stroke(paths.jurisdiction);
    } else {
      context.strokeStyle = COMMANDERY_BORDER_DARK;
      context.lineWidth = 3 * dpr / scale;
      context.stroke(paths.commandery);
      context.strokeStyle = COMMANDERY_BORDER_LIGHT;
      context.lineWidth = 1.5 * dpr / scale;
      context.stroke(paths.commandery);
    }
  }
  context.restore();

  if (strategic?.route) {
    context.save();
    context.strokeStyle = '#fff178';
    context.lineWidth = 3 * dpr;
    context.beginPath();
    strategic.route.forEach((point, index) => {
      const [x, y] = cellToScreen(point.col, point.row, view);
      if (index === 0) context.moveTo(x, y); else context.lineTo(x, y);
    });
    context.stroke();
    context.restore();
  }

  const hits: CityHitBox[] = [];
  for (const city of scene.cities) {
    const [x, y] = cellToScreen(city.col, city.row, view);
    const level = markerLevel(city);
    const requestedMarkerZoom = cityMarkerZoomStep(scale, dpr);
    const markerZoom = containedCityMarkerZoom(
      city, level, x, y, requestedMarkerZoom, dpr, view,
    );
    const radius = cityMarkerRadius(level, dpr) * (markerZoom ?? 0.5);
    const marker = markerImages[level];
    const owned = isOwnedNationVisual(city.nationId, city.nationColor);
    context.save();

    if (markerZoom === undefined) {
      const overviewBox = drawOverviewCityGlyph(context, city, x, y, scale, dpr);
      hits.push({ city, provinceId: city.provinceId, ...overviewBox });
      if (city.layers.includes('current')) {
        const detail = Math.min(
          (overviewBox.right - overviewBox.left) / 2,
          (overviewBox.bottom - overviewBox.top) / 2,
        );
        drawCurrentLocationOverlay(context, x, y, detail, dpr, selfLocationPhase);
      }
    } else if (marker) {
      hits.push({
        city,
        provinceId: city.provinceId,
        ...cityMarkerHitBox(level, x, y, dpr, markerZoom),
      });
      const box = cityMarkerDrawBox(level, x, y, dpr, markerZoom);
      context.imageSmoothingEnabled = false;
      context.drawImage(marker, box.x, box.y, box.width, box.height);
    } else {
      hits.push({
        city,
        provinceId: city.provinceId,
        ...cityFallbackHitBox(x, y, radius),
      });
      context.fillStyle = city.iconColor;
      context.strokeStyle = CASTLE_STROKE;
      context.lineWidth = 1.5;
      context.fillRect(x - radius * 0.7, y - radius * 0.45, radius * 1.4, radius * 0.9);
      context.strokeRect(x - radius * 0.7, y - radius * 0.45, radius * 1.4, radius * 0.9);
      context.fillRect(x - radius * 0.5, y - radius * 0.9, radius * 0.3, radius * 0.5);
      context.fillRect(x + radius * 0.2, y - radius * 0.9, radius * 0.3, radius * 0.5);
    }

    if (markerZoom !== undefined && owned) {
      context.strokeStyle = '#e8dec5';
      context.beginPath();
      context.moveTo(x + radius * 0.55, y - radius * 0.45);
      context.lineTo(x + radius * 0.55, y - radius * 1.65);
      context.stroke();
      context.fillStyle = city.territoryColor;
      const cloth = flagClothPoints(x, y, radius, city.supply !== false, flagPhase);
      context.beginPath();
      context.moveTo(...cloth[0]);
      for (const point of cloth.slice(1)) context.lineTo(...point);
      context.closePath();
      context.fill();
      context.strokeStyle = '#21180f';
      context.lineWidth = Math.max(2, 1.5 * dpr);
      context.stroke();
      context.strokeStyle = 'rgba(255,244,208,0.92)';
      context.lineWidth = Math.max(1, 0.55 * dpr);
      context.stroke();
    }

    if (markerZoom !== undefined && city.isCapital) {
      starPath(context, x + radius * 1.2, y - radius * 1.8, Math.max(4, radius * 0.42));
      context.fillStyle = '#ffd84f';
      context.fill();
      context.strokeStyle = '#6a4b00';
      context.stroke();
    }

    if (markerZoom !== undefined && (city.state ?? 0) > 0) {
      const badgeX = x - radius * 1.05;
      const badgeY = y - radius * 0.9;
      context.fillStyle = '#b72f2f';
      context.beginPath();
      context.arc(badgeX, badgeY, Math.max(5, radius * 0.42), 0, Math.PI * 2);
      context.fill();
      context.fillStyle = '#fff';
      context.font = `bold ${Math.max(8, radius * 0.65)}px sans-serif`;
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.fillText(String(city.state), badgeX, badgeY);
    }

    if (markerZoom !== undefined && city.layers.includes('current')) {
      drawCurrentLocationOverlay(context, x, y, radius, dpr, selfLocationPhase);
    }
    if (markerZoom !== undefined && city.layers.includes('selected')) {
      context.strokeStyle = '#ffd84f';
      context.lineWidth = 3;
      context.strokeRect(x - radius, y - radius, radius * 2, radius * 2);
    }

    if (!hideCityNames) {
      const labelKind = city.provinceKind === 'SETTLEMENT' ? 'COUNTY' : city.provinceKind;
      const labelThreshold = labelKind ? labelZoomFor(labelKind, fittedScale, dpr) : undefined;
      const labelVisibleAtZoom = labelThreshold === undefined || scale >= labelThreshold;
      const metrics = cityLabelMetrics(scale, dpr);
      const labelX = x;
      context.textAlign = 'center';
      context.textBaseline = 'alphabetic';
      context.font = `bold ${metrics.fontSize}px sans-serif`;
      context.lineWidth = metrics.strokeWidth;
      context.strokeStyle = 'rgba(0,0,0,0.8)';
      context.fillStyle = '#fff';
      const labelWidth = context.measureText(city.mapLabel).width + 4 * dpr;
      const labelYs = [y + radius * 1.2, y - radius * 1.8, y + metrics.fontSize * 0.35];
      const labelY = labelVisibleAtZoom ? labelYs.find((candidateY) => {
        const labelBox = {
          left: labelX - labelWidth / 2,
          top: candidateY - metrics.fontSize - 2 * dpr,
          right: labelX + labelWidth / 2,
          bottom: candidateY + metrics.fontSize * 0.25 + 2 * dpr,
        };
        return city.provinceId === undefined || city.visualClearance === undefined
          || screenBoxInsideVisualClearance(
            city.col, city.row, city.visualClearance, view, labelBox,
          );
      }) : undefined;
      if (labelY !== undefined) {
        context.strokeText(city.mapLabel, labelX, labelY);
        context.fillText(city.mapLabel, labelX, labelY);
      }
    }
    context.restore();
  }
  return hits;
}

function resolveTerrainUrl(
  terrainUrl: HanMapCanvasProps['terrainUrl'],
  mapCode: string,
): string {
  if (typeof terrainUrl === 'function') return terrainUrl(mapCode);
  if (typeof terrainUrl === 'string') return terrainUrl;
  return `/api/game/api/map/terrain?mapCode=${encodeURIComponent(mapCode)}`;
}

function resolveProvinceUrl(
  provinceUrl: HanMapCanvasProps['provinceUrl'],
  mapCode: string,
): string {
  if (typeof provinceUrl === 'function') return provinceUrl(mapCode);
  if (typeof provinceUrl === 'string') return provinceUrl;
  return `/api/game/api/map/provinces?mapCode=${encodeURIComponent(mapCode)}`;
}

export function HanMapCanvas({
  mapCode,
  tiles: suppliedTiles,
  tilesSha256,
  strategicTopology,
  selectedServerRoute,
  currentServerId,
  terrainUrl,
  provinceUrl,
  provinceMap: suppliedProvinceMap,
  cities = [],
  administrativeOwnership,
  sourceSize = DEFAULT_SOURCE,
  initialFocus,
  currentCityId,
  selectedCityId,
  hideCityNames = false,
  className = '',
  style,
  ariaLabel,
  onCityHover,
  onCountyHover,
  onCityActivate,
  onMissing,
  onViewChange,
}: HanMapCanvasProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const terrainRef = useRef<HTMLCanvasElement | null>(null);
  const politicalRef = useRef<HTMLCanvasElement | null>(null);
  const politicalPathsRef = useRef<PoliticalPaths | null>(null);
  const provinceMapRef = useRef<ProvinceIdentityMap | null>(null);
  const markerImagesRef = useRef<CityMarkerImages>({});
  const flagPhaseRef = useRef(0);
  const selfLocationPhaseRef = useRef(0);
  const viewRef = useRef<IsoView | null>(null);
  const userModifiedViewRef = useRef(false);
  const initialFocusAppliedRef = useRef(false);
  const sizeRef = useRef({ width: 0, height: 0, dpr: 1 });
  const hitRef = useRef<CityHitBox[]>([]);
  const dragRef = useRef(new Map<number, { x: number; y: number }>());
  const dragMovedRef = useRef(false);
  const activeCityRef = useRef<IsoCityOverlay | null>(null);
  const pointerTypeRef = useRef('mouse');
  const [loadedTiles, setLoadedTiles] = useState<HanTiles | null>(suppliedTiles ?? null);
  const [terrainIdentity, setTerrainIdentity] = useState<{ mapCode: string; hash: string | null } | null>(null);
  const [showWater, setShowWater] = useState(true);
  const [inspectedWater, setInspectedWater] = useState<string | null>(null);
  const [loadedProvince, setLoadedProvince] = useState<{
    url: string;
    map: ProvinceIdentityMap | null;
  }>({ url: '', map: null });
  const [missing, setMissing] = useState(false);
  const [administrativeLayer, setAdministrativeLayer] = useState<AdministrativeLayer>('JURISDICTION');

  useEffect(() => {
    if (suppliedTiles !== undefined) {
      setLoadedTiles(suppliedTiles);
      setMissing(suppliedTiles == null);
      setTerrainIdentity({ mapCode, hash: tilesSha256 ?? null });
      return;
    }
    let alive = true;
    setMissing(false);
    setTerrainIdentity(null);
    fetch(resolveTerrainUrl(terrainUrl, mapCode))
      .then((response) => {
        if (!response.ok) throw new Error(`terrain fetch failed: ${response.status}`);
        const hash = /^"sha256-([a-f0-9]{64})"$/.exec(response.headers?.get('etag') ?? '')?.[1] ?? null;
        return (response.json() as Promise<HanTiles>).then(tiles => ({ tiles, hash }));
      })
      .then(({ tiles, hash }) => {
        if (alive) {
          setLoadedTiles(tiles);
          setTerrainIdentity({ mapCode, hash });
        }
      })
      .catch(() => {
        if (!alive) return;
        setLoadedTiles(null);
        setMissing(true);
        onMissing?.();
      });
    return () => {
      alive = false;
    };
  }, [mapCode, onMissing, suppliedTiles, terrainUrl, tilesSha256]);

  useEffect(() => {
    if (suppliedProvinceMap !== undefined) return;
    let alive = true;
    const url = resolveProvinceUrl(provinceUrl, mapCode);
    setLoadedProvince({ url, map: null });
    loadProvinceIdentityMap(url)
      .then((map) => {
        if (alive) setLoadedProvince({ url, map });
      })
      .catch(() => {
        if (alive) setLoadedProvince({ url, map: null });
      });
    return () => {
      alive = false;
    };
  }, [mapCode, provinceUrl, suppliedProvinceMap]);

  const provinceMap = useMemo(() => {
    if (!loadedTiles) return null;
    const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
    const requestedUrl = resolveProvinceUrl(provinceUrl, mapCode);
    const candidate = suppliedProvinceMap !== undefined
      ? suppliedProvinceMap
      : loadedProvince.url === requestedUrl ? loadedProvince.map : null;
    return matchesGrid(candidate, grid) ? candidate : null;
  }, [loadedProvince, loadedTiles, mapCode, provinceUrl, suppliedProvinceMap]);

  const gridCols = loadedTiles?._meta.cols ?? 0;
  const gridRows = loadedTiles?._meta.rows ?? 0;
  const sourceWidth = sourceSize.width;
  const sourceHeight = sourceSize.height;
  const ownershipKey = politicalOwnershipKey(cities);
  const countyIndex = useMemo(() => (
    provinceMap && loadedTiles
      ? (loadedTiles.provinceRecords && loadedTiles.parentRegions
        ? buildProvinceAdministrativeIndex(
          provinceMap, loadedTiles.provinceRecords, loadedTiles.parentRegions,
          loadedTiles.jurisdictionRecords,
        )
        : buildCountyAdministrativeIndex(provinceMap, loadedTiles.cities, loadedTiles.juns))
      : null
  ), [loadedTiles?.cities, loadedTiles?.juns, loadedTiles?.jurisdictionRecords, loadedTiles?.parentRegions, loadedTiles?.provinceRecords, provinceMap]);
  const canonicalMarkerPositions = useMemo(() => {
    if (!loadedTiles) return undefined;
    const hasCanonicalHierarchy = Boolean(
      loadedTiles.provinceRecords && loadedTiles.jurisdictionRecords,
    );
    const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
    const preferredByProvince = new Map<number, { col: number; row: number }>();
    loadedTiles.provinceRecords?.forEach((record, provinceId) => {
      if (record.cityIndex == null) return;
      const city = loadedTiles.cities[record.cityIndex];
      if (city) preferredByProvince.set(provinceId, { col: city.col, row: city.row });
    });
    const placement = provinceMap && countyIndex
      ? { provinceMap, countyIndex, preferredByProvince }
      : undefined;
    return new Map(cities.map((city) => [
      city.id,
      cityMarkerTile(
        city,
        grid,
        sourceSize,
        hasCanonicalHierarchy && city.provinceId === undefined ? undefined : placement,
      ),
    ]));
  }, [cities, countyIndex, loadedTiles, provinceMap, sourceSize]);
  const provinceAnchors = useMemo(() => {
    if (!canonicalMarkerPositions || !provinceMap) return undefined;
    const preferredByProvince = new Map<number, { col: number; row: number }>();
    for (const city of [...cities].sort((left, right) => left.id - right.id)) {
      const placement = canonicalMarkerPositions.get(city.id);
      if (placement?.provinceId === undefined || preferredByProvince.has(placement.provinceId)) continue;
      preferredByProvince.set(placement.provinceId, { col: placement.col, row: placement.row });
    }
    return buildProvinceVisualAnchors(provinceMap, preferredByProvince);
  }, [canonicalMarkerPositions, cities, provinceMap]);
  const strategicSceneCache = useRef<{ hash: string; tiles: HanTiles; scene: StrategicMapScene } | null>(null);
  const strategicScene = useMemo(() => {
    if (!strategicTopology || !loadedTiles) return null;
    try {
      const binding = strategicTopology.binding;
      if (mapCode !== 'han-world-v3' || !validStrategicBinding(binding)
        || binding.cols !== loadedTiles._meta.cols || binding.rows !== loadedTiles._meta.rows
        || terrainIdentity?.mapCode !== mapCode || terrainIdentity.hash !== binding.baseTilesSha256) return null;
      const cached = strategicSceneCache.current;
      if (cached?.hash === binding.topologyHash && cached.tiles === loadedTiles) return cached.scene;
      const scene = buildStrategicMapScene(strategicTopology.topology, loadedTiles);
      strategicSceneCache.current = { hash: binding.topologyHash, tiles: loadedTiles, scene };
      return scene;
    } catch { return null; }
  }, [loadedTiles, mapCode, strategicTopology?.binding.topologyHash, strategicTopology?.topology, terrainIdentity]);
  const strategicControls = useMemo(() => {
    try { return strategicTopology ? validatedWaterControls(strategicTopology) : null; }
    catch { return null; }
  }, [strategicTopology]);
  const routeAnchors = useMemo(() => {
    const result = new Map<string, { col: number; row: number }>();
    loadedTiles?.provinceRecords?.forEach((record, index) => {
      const anchor = provinceAnchors?.[index];
      if (anchor) result.set(`land:${record.id}`, anchor);
    });
    for (const shape of strategicScene?.zones ?? []) result.set(`water:${shape.zone.id}`, shape.anchor);
    return result;
  }, [loadedTiles?.provinceRecords, provinceAnchors, strategicScene]);
  const selectedRoutePoints = useMemo(() => selectedServerRoute && strategicTopology && strategicScene
    ? serverRoutePoints(selectedServerRoute, strategicTopology.binding, strategicScene, routeAnchors, currentServerId) : null,
  [selectedServerRoute, strategicTopology?.binding, strategicScene, routeAnchors, currentServerId]);
  const strategicRef = useRef<Parameters<typeof drawScene>[13]>(null);
  strategicRef.current = strategicScene && strategicControls ? {
    scene: strategicScene, controls: strategicControls, visible: showWater, route: selectedRoutePoints,
  } : null;
  const displayCities = useMemo(() => {
    if (administrativeLayer === 'PROVINCE') {
      return loadedTiles?.provinceRecords && loadedTiles.jurisdictionRecords
        ? provinceLayerRuntimeCities(cities)
        : [...cities];
    }
    if (!loadedTiles || !provinceAnchors) return [...cities];
    const overlays = completeJurisdictionOverlays(
      loadedTiles,
      cities,
      provinceAnchors,
      sourceSize,
      administrativeLayer === 'COMMANDERY' ? 'COMMANDERY' : 'COUNTY',
      canonicalMarkerPositions,
      currentCityId,
      provinceMap,
    );
    if (!administrativeOwnership) return overlays;
    const jurisdictionOwners = new Map(
      administrativeOwnership.jurisdictionOwnership.map((owner) => [owner.jurisdictionId, owner]),
    );
    const commanderyOwners = new Map(
      administrativeOwnership.commanderyControl.map((owner) => [owner.commanderyId, owner]),
    );
    return overlays.map((city) => {
      const owner = administrativeLayer === 'COMMANDERY'
        ? (city.commanderyId == null ? undefined : commanderyOwners.get(city.commanderyId))
        : (city.jurisdictionId == null ? undefined : jurisdictionOwners.get(city.jurisdictionId));
      if (!owner) return city;
      return {
        ...city,
        nationId: owner.nationId,
        nationColor: owner.nationColor,
        nationName: owner.nationName,
      };
    });
  }, [administrativeLayer, administrativeOwnership, canonicalMarkerPositions, cities, currentCityId, loadedTiles, provinceAnchors, provinceMap, sourceSize]);
  const markerPositions = useMemo(() => {
    if (!canonicalMarkerPositions || !provinceAnchors) return canonicalMarkerPositions;
    const result = new Map([...canonicalMarkerPositions].map(([cityId, placement]) => {
      const anchor = placement.provinceId === undefined ? undefined : provinceAnchors[placement.provinceId];
      return [cityId, anchor
        ? {
          col: anchor.col,
          row: anchor.row,
          provinceId: placement.provinceId,
          visualClearance: anchor.clearance,
        }
        : placement] as const;
    }));
    for (const city of displayCities) {
      if (result.has(city.id) || city.provinceId === undefined) continue;
      const anchor = provinceAnchors[city.provinceId];
      if (anchor) result.set(city.id, {
        col: anchor.col,
        row: anchor.row,
        provinceId: city.provinceId,
        visualClearance: anchor.clearance,
      });
    }
    return result;
  }, [canonicalMarkerPositions, displayCities, provinceAnchors]);
  const scene = useMemo(
    () => loadedTiles
      ? buildIsoScene(loadedTiles, displayCities, sourceSize, {
        currentCityId,
        selectedCityId,
        markerPositions,
        provinceRecords: loadedTiles.provinceRecords,
        jurisdictionRecords: loadedTiles.jurisdictionRecords,
      })
      : null,
    [currentCityId, displayCities, loadedTiles, markerPositions, selectedCityId, sourceSize],
  );
  const sceneRef = useRef<IsoScene | null>(scene);
  const hideCityNamesRef = useRef(hideCityNames);
  const ownershipCitiesRef = useRef(cities);
  sceneRef.current = scene;
  provinceMapRef.current = provinceMap;
  hideCityNamesRef.current = hideCityNames;
  ownershipCitiesRef.current = cities;
  const completeOwnership = useMemo(() => {
    if (!provinceMap || !countyIndex || gridCols === 0 || gridRows === 0) return null;
    if (administrativeOwnership && loadedTiles?.provinceRecords) {
      return bindAdministrativeOwnership(
        provinceMap,
        countyIndex,
        loadedTiles.provinceRecords,
        administrativeOwnership,
        administrativeLayer,
      );
    }
    return bindCompleteProvinceOwnership(
      provinceMap,
      ownershipCitiesRef.current,
      { cols: gridCols, rows: gridRows },
      { width: sourceWidth, height: sourceHeight },
      countyIndex,
    );
  }, [administrativeLayer, administrativeOwnership, countyIndex, gridCols, gridRows, loadedTiles?.provinceRecords, ownershipKey, provinceMap, sourceHeight, sourceWidth]);
  const cityById = useMemo(() => new Map(cities.map((city) => [city.id, city])), [cities]);
  const administrativeOwnerIndex = useMemo(() => ({
    province: new Map(administrativeOwnership?.provinceOccupancy.map((owner) => [owner.provinceIndex, owner]) ?? []),
    jurisdiction: new Map(administrativeOwnership?.jurisdictionOwnership.map((owner) => [owner.jurisdictionId, owner]) ?? []),
    commandery: new Map(administrativeOwnership?.commanderyControl.map((owner) => [owner.commanderyId, owner]) ?? []),
  }), [administrativeOwnership]);
  const regionByCommanderyId = useMemo(() => {
    const regions = new Map<number, string>();
    if (!loadedTiles || !countyIndex) return regions;
    for (const city of cities) {
      if (!city.commanderyName || !city.regionName) continue;
      const commandery = countyIndex.commanderyByName.get(city.commanderyName);
      if (commandery != null && !regions.has(commandery)) regions.set(commandery, city.regionName);
    }
    return regions;
  }, [cities, countyIndex, loadedTiles?.juns]);

  useEffect(() => {
    terrainRef.current = loadedTiles ? bakeTerrain(loadedTiles) : null;
    viewRef.current = null;
    userModifiedViewRef.current = false;
    initialFocusAppliedRef.current = false;
  }, [loadedTiles, mapCode]);

  useEffect(() => {
    politicalPathsRef.current = provinceMap ? bakePoliticalPaths(provinceMap, countyIndex) : null;
  }, [countyIndex, provinceMap]);

  const render = useCallback(() => {
    const canvas = canvasRef.current;
    const terrain = terrainRef.current;
    const view = viewRef.current;
    const latestScene = sceneRef.current;
    if (!canvas || !terrain || !latestScene || !view) return;
    hitRef.current = drawScene(
      canvas,
      terrain,
      politicalRef.current,
      politicalPathsRef.current,
      provinceMapRef.current,
      latestScene,
      view,
      hideCityNamesRef.current,
      sizeRef.current.dpr,
      markerImagesRef.current,
      flagPhaseRef.current,
      selfLocationPhaseRef.current,
      administrativeLayer,
      strategicRef.current,
    );
  }, [administrativeLayer]);

  useEffect(() => {
    let alive = true;
    const pending = Object.entries(CITY_MARKER_URLS).map(([level, url]) => {
      const image = new Image();
      image.onload = () => {
        if (!alive) return;
        markerImagesRef.current[Number(level)] = image;
        render();
      };
      image.src = url;
      return image;
    });
    return () => {
      alive = false;
      for (const image of pending) image.onload = null;
    };
  }, [render]);

  useEffect(() => {
    if (!provinceMap || !completeOwnership || gridCols === 0 || gridRows === 0) {
      politicalRef.current = null;
      render();
      return;
    }
    politicalRef.current = bakePoliticalFill(provinceMap, completeOwnership);
    render();
  }, [completeOwnership, gridCols, gridRows, provinceMap, render]);

  useEffect(() => {
    render();
  }, [hideCityNames, render, scene, strategicScene, strategicControls, showWater, selectedRoutePoints]);

  useEffect(() => {
    const hasWavingFlag = scene?.cities.some((city) => (
      isOwnedNationVisual(city.nationId, city.nationColor) && city.supply !== false
    )) ?? false;
    const hasCurrentLocation = scene?.cities.some((city) => city.layers.includes('current')) ?? false;
    const reducedMotion = typeof window.matchMedia === 'function'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    flagPhaseRef.current = 0;
    selfLocationPhaseRef.current = 0;
    render();
    if (reducedMotion) return;
    const flagTimer = hasWavingFlag ? window.setInterval(() => {
      flagPhaseRef.current = (flagPhaseRef.current + 1) % 3;
      render();
    }, 240) : undefined;
    const selfLocationTimer = hasCurrentLocation ? window.setInterval(() => {
      selfLocationPhaseRef.current = selfLocationPhaseRef.current === 0 ? 1 : 0;
      render();
    }, 1_200) : undefined;
    return () => {
      if (flagTimer !== undefined) window.clearInterval(flagTimer);
      if (selfLocationTimer !== undefined) window.clearInterval(selfLocationTimer);
    };
  }, [render, scene]);

  useEffect(() => {
    const box = boxRef.current;
    const canvas = canvasRef.current;
    if (!box || !canvas || !loadedTiles || !terrainRef.current) return;
    const fit = () => {
      const cssWidth = box.clientWidth || 700;
      const cssHeight = box.clientHeight || Math.round(cssWidth * 0.53);
      const requestedDpr = effectiveDpr(window.devicePixelRatio);
      const previousSize = sizeRef.current;
      const previousView = viewRef.current;
      canvas.width = Math.round(cssWidth * requestedDpr);
      const dpr = canvas.width / cssWidth;
      canvas.height = Math.round(cssHeight * dpr);
      canvas.style.height = `${cssHeight}px`;
      sizeRef.current = { width: canvas.width, height: canvas.height, dpr };
      const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
      const currentPosition = currentCityId == null ? undefined : markerPositions?.get(currentCityId);
      const sameViewport = previousView
        && previousSize.width === canvas.width
        && previousSize.height === canvas.height
        && previousSize.dpr === dpr;
      const shouldApplyFirstCurrentFocus = !userModifiedViewRef.current
        && !initialFocusAppliedRef.current
        && currentPosition !== undefined;
      let viewChanged = true;
      if (sameViewport && !shouldApplyFirstCurrentFocus) {
        viewRef.current = previousView;
        viewChanged = false;
      } else if (
        userModifiedViewRef.current
        && previousView
        && previousSize.width > 0
        && previousSize.height > 0
      ) {
        const [centerCol, centerRow] = screenToCell(
          previousSize.width / 2,
          previousSize.height / 2,
          previousView,
        );
        const dprRatio = dpr / previousSize.dpr;
        const scale = Math.min(
          maxScaleForDpr(dpr),
          Math.max(fitScale(canvas.width, canvas.height, grid), previousView.scale * dprRatio),
        );
        viewRef.current = clampView(
          viewAt(canvas.width, canvas.height, centerCol, centerRow, scale),
          canvas.width,
          canvas.height,
          grid,
        );
      } else {
        viewRef.current = initialFocusedView(
          canvas.width,
          canvas.height,
          grid,
          loadedTiles,
          dpr,
          currentPosition,
          initialFocus,
        );
        if (currentPosition !== undefined) initialFocusAppliedRef.current = true;
      }
      if (viewChanged) onViewChange?.(viewRef.current);
      render();
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(box);
    window.addEventListener('resize', fit);
    let dprQuery: MediaQueryList | null = null;
    const handleDprChange = () => {
      dprQuery?.removeEventListener('change', handleDprChange);
      fit();
      listenForDprChange();
    };
    const listenForDprChange = () => {
      if (typeof window.matchMedia !== 'function') return;
      dprQuery = window.matchMedia(`(resolution: ${window.devicePixelRatio}dppx)`);
      dprQuery.addEventListener('change', handleDprChange);
    };
    listenForDprChange();
    return () => {
      dprQuery?.removeEventListener('change', handleDprChange);
      observer.disconnect();
      window.removeEventListener('resize', fit);
    };
  }, [currentCityId, initialFocus, loadedTiles, mapCode, markerPositions, onViewChange, render]);

  const updateView = useCallback((next: IsoView) => {
    userModifiedViewRef.current = true;
    viewRef.current = next;
    onViewChange?.(next);
    render();
  }, [onViewChange, render]);

  const zoomBy = useCallback((factor: number, sx?: number, sy?: number) => {
    const view = viewRef.current;
    if (!view || !loadedTiles) return;
    const { width, height, dpr } = sizeRef.current;
    const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
    const next = zoomAt(
      view,
      sx ?? width / 2,
      sy ?? height / 2,
      factor,
      fitScale(width, height, grid),
      maxScaleForDpr(dpr),
    );
    updateView(clampView(next, width, height, grid));
  }, [loadedTiles, updateView]);

  const eventPoint = useCallback((event: { clientX: number; clientY: number }) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const rect = canvas.getBoundingClientRect();
    const scaleX = canvas.width / (rect.width || canvas.width || 1);
    const scaleY = canvas.height / (rect.height || canvas.height || 1);
    return {
      canvasX: (event.clientX - rect.left) * scaleX,
      canvasY: (event.clientY - rect.top) * scaleY,
      cssX: event.clientX - rect.left,
      cssY: event.clientY - rect.top,
    };
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const handleWheel = (event: WheelEvent) => {
      event.preventDefault();
      const point = eventPoint(event);
      zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15, point?.canvasX, point?.canvasY);
    };
    canvas.addEventListener('wheel', handleWheel, { passive: false });
    return () => canvas.removeEventListener('wheel', handleWheel);
  }, [eventPoint, zoomBy]);

  const cityAt = (x: number, y: number) => {
    if (waterAt(x, y)) return null;
    for (let index = hitRef.current.length - 1; index >= 0; index -= 1) {
      const hit = hitRef.current[index];
      if (x < hit.left || x > hit.right || y < hit.top || y > hit.bottom) continue;
      const view = viewRef.current;
      const map = provinceMapRef.current;
      if (hit.provinceId !== undefined && map && view
        && provinceAtScreenPoint(map, view, x, y) !== hit.provinceId) continue;
      return hit.city;
    }
    return null;
  };

  const waterAt = (x: number, y: number) => {
    if (!showWater || !strategicScene || !strategicControls || !viewRef.current || !loadedTiles) return null;
    const [rawCol, rawRow] = screenToCell(x, y, viewRef.current);
    const col = Math.round(rawCol); const row = Math.round(rawRow);
    if (col < 0 || row < 0 || col >= loadedTiles._meta.cols || row >= loadedTiles._meta.rows) return null;
    return strategicScene.byCell.get(row * loadedTiles._meta.cols + col) ?? null;
  };

  const countyAt = (x: number, y: number): IsoCountyHover | null => {
    const view = viewRef.current;
    if (!view || !loadedTiles || !provinceMap || !countyIndex || !completeOwnership) return null;
    const [rawCol, rawRow] = screenToCell(x, y, view);
    const col = Math.round(rawCol);
    const row = Math.round(rawRow);
    if (col < 0 || row < 0 || col >= provinceMap.width || row >= provinceMap.height) return null;
    const index = row * provinceMap.width + col;
    const provinceId = provinceMap.provinces[index];
    const commanderyId = countyIndex.commanderyByProvince[provinceId];
    if (provinceId < 0 || commanderyId < 0) return null;
    const provinceRecord = loadedTiles.provinceRecords?.[provinceId];
    const parentRecord = loadedTiles.parentRegions?.[commanderyId];
    const jurisdictionRecord = provinceRecord?.jurisdictionId == null
      ? undefined
      : loadedTiles.jurisdictionRecords?.find((record) => record.id === provinceRecord.jurisdictionId);
    const linkedCity = provinceRecord?.cityIndex == null
      ? undefined : loadedTiles.cities[provinceRecord.cityIndex];
    const county = linkedCity ?? loadedTiles.cities[provinceId];
    const commanderyName = parentRecord?.displayName ?? loadedTiles.juns[commanderyId]?.name;
    if ((!provinceRecord && !county) || !commanderyName) return null;
    const assigned = completeOwnership.cities?.get(provinceId);
    const city = assigned ? (cityById.get(assigned.id) ?? assigned) : undefined;
    const provinceOwner = administrativeOwnerIndex.province.get(provinceId);
    const jurisdictionOwner = jurisdictionRecord
      ? administrativeOwnerIndex.jurisdiction.get(jurisdictionRecord.id) : undefined;
    const commanderyOwner = parentRecord
      ? administrativeOwnerIndex.commandery.get(parentRecord.id) : undefined;
    const displayedOwner = administrativeLayer === 'PROVINCE'
      ? provinceOwner : administrativeLayer === 'JURISDICTION' ? jurisdictionOwner : commanderyOwner;
    const provinceJurisdictionMismatch = provinceOwner != null && jurisdictionOwner != null
      && provinceOwner.nationId !== jurisdictionOwner.nationId;
    const jurisdictionCommanderyMismatch = jurisdictionOwner != null && commanderyOwner != null
      && jurisdictionOwner.nationId !== commanderyOwner.nationId;
    const ownerName = (owner: { nationId: number; nationName?: string } | undefined) => (
      owner ? (owner.nationName ?? (owner.nationId === 0 ? '미소유' : `세력 ${owner.nationId}`)) : undefined
    );
    return {
      provinceId,
      commanderyId,
      provinceRecordId: provinceRecord?.id,
      jurisdictionId: jurisdictionRecord?.id,
      commanderyRecordId: parentRecord?.id,
      spatialProvinceName: provinceRecord?.displayName,
      jurisdictionNameCh: jurisdictionRecord?.nameCh,
      commanderyNameCh: parentRecord?.nameCh,
      hierarchyPath: provinceRecord && jurisdictionRecord && parentRecord
        ? `${provinceRecord.displayName} → ${jurisdictionRecord.displayName} → ${parentRecord.displayName}`
        : undefined,
      provinceOccupantNationId: provinceOwner?.nationId,
      provinceOccupantNationName: ownerName(provinceOwner),
      jurisdictionOwnerNationId: jurisdictionOwner?.nationId,
      jurisdictionOwnerNationName: ownerName(jurisdictionOwner),
      commanderyControllerNationId: commanderyOwner?.nationId,
      commanderyControllerNationName: ownerName(commanderyOwner),
      displayedOwnerNationName: ownerName(displayedOwner),
      provinceJurisdictionMismatch,
      jurisdictionCommanderyMismatch,
      ownershipMismatch: provinceJurisdictionMismatch || jurisdictionCommanderyMismatch,
      regionName: regionByCommanderyId.get(commanderyId) ?? city?.regionName ?? '',
      commanderyName,
      countyName: jurisdictionRecord?.displayName ?? provinceRecord?.displayName ?? county!.name,
      displayName: administrativeLayer === 'COMMANDERY'
        ? commanderyName
        : provinceRecord
          ? formatProvinceTooltip(
            jurisdictionRecord ? { ...provinceRecord, displayName: jurisdictionRecord.displayName } : provinceRecord,
            parentRecord,
          )
          : `${commanderyName} ${county!.name}`,
      level: completeOwnership.directProvinces?.has(provinceId) && city
        ? city.level : (county?.level ?? city?.level ?? 5),
      nationId: displayedOwner?.nationId ?? city?.nationId ?? 0,
      nationName: displayedOwner ? ownerName(displayedOwner) : city?.nationName,
      nationColor: displayedOwner ? displayedOwner.nationColor : city?.nationColor,
    };
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const point = eventPoint(event);
    if (!point) return;
    const drag = dragRef.current;
    const view = viewRef.current;
    const previousPoint = drag.get(event.pointerId);
    if (previousPoint && view && loadedTiles) {
      const canvas = canvasRef.current!;
      const rect = canvas.getBoundingClientRect();
      const scaleX = canvas.width / (rect.width || canvas.width || 1);
      const scaleY = canvas.height / (rect.height || canvas.height || 1);
      const { width, height } = sizeRef.current;
      const currentPoint = { x: event.clientX, y: event.clientY };
      const previous = [...drag.entries()].map(([pointerId, pointer]) => (
        pointerId === event.pointerId ? previousPoint : pointer
      ));
      drag.set(event.pointerId, currentPoint);
      const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
      if (drag.size === 1) {
        const dx = (currentPoint.x - previousPoint.x) * scaleX;
        const dy = (currentPoint.y - previousPoint.y) * scaleY;
        if (Math.abs(dx) + Math.abs(dy) > 1) dragMovedRef.current = true;
        updateView(clampView(
          { scale: view.scale, ox: view.ox + dx, oy: view.oy + dy },
          width,
          height,
          grid,
        ));
        return;
      }
      if (drag.size === 2) {
        const current = [...drag.values()];
        const gesture = pinchGesture(
          previous as [{ x: number; y: number }, { x: number; y: number }],
          current as [{ x: number; y: number }, { x: number; y: number }],
        );
        const previousAnchor = {
          x: (previous[0].x + previous[1].x) / 2,
          y: (previous[0].y + previous[1].y) / 2,
        };
        const dx = (gesture.anchor.x - previousAnchor.x) * scaleX;
        const dy = (gesture.anchor.y - previousAnchor.y) * scaleY;
        if (Math.abs(dx) + Math.abs(dy) > 1 || gesture.factor !== 1) dragMovedRef.current = true;
        const anchorX = (gesture.anchor.x - rect.left) * scaleX;
        const anchorY = (gesture.anchor.y - rect.top) * scaleY;
        const panned = { scale: view.scale, ox: view.ox + dx, oy: view.oy + dy };
        const next = zoomAt(
          panned,
          anchorX,
          anchorY,
          gesture.factor,
          fitScale(width, height, grid),
          maxScaleForDpr(sizeRef.current.dpr),
        );
        updateView(clampView(next, width, height, grid));
      }
      return;
    }
    const city = cityAt(point.canvasX, point.canvasY);
    activeCityRef.current = city;
    onCityHover?.(city, { x: point.cssX, y: point.cssY });
    onCountyHover?.(countyAt(point.canvasX, point.canvasY), { x: point.cssX, y: point.cssY });
  };

  const endPointer = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current;
    const moved = dragMovedRef.current;
    drag.delete(event.pointerId);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (drag.size > 0) return;
    dragMovedRef.current = false;
    if (moved) return;
    const point = eventPoint(event);
    const water = point ? waterAt(point.canvasX, point.canvasY) : null;
    if (water) { setInspectedWater(water.id); return; }
    const city = point ? cityAt(point.canvasX, point.canvasY) : null;
    if (city && city.interactive !== false) onCityActivate?.(city, { pointerType: pointerTypeRef.current });
  };

  if (missing) return null;
  return (
    <div
      ref={boxRef}
      className={`os-iso-map ${className}`.trim()}
      style={{ position: 'relative', width: '100%', height: '100%', ...style }}
    >
      <canvas
        ref={canvasRef}
        className="os-iso-map__canvas"
        role="img"
        aria-label={ariaLabel ?? (loadedTiles ? `${mapCode} 아이소 타일 지도` : '지도 불러오는 중')}
        tabIndex={0}
        onPointerDown={(event) => {
          pointerTypeRef.current = event.pointerType || 'mouse';
          event.currentTarget.setPointerCapture(event.pointerId);
          if (dragRef.current.size === 0) dragMovedRef.current = false;
          else dragMovedRef.current = true;
          dragRef.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
        }}
        onPointerMove={onPointerMove}
        onPointerUp={endPointer}
        onPointerCancel={(event) => {
          dragRef.current.delete(event.pointerId);
          if (dragRef.current.size === 0) dragMovedRef.current = false;
        }}
        onPointerLeave={() => {
          if (dragRef.current.size === 0) {
            activeCityRef.current = null;
            onCityHover?.(null);
            onCountyHover?.(null);
          }
        }}
        onFocus={() => {
          if (!activeCityRef.current && scene?.cities[0]) {
            activeCityRef.current = scene.cities[0];
            onCityHover?.(scene.cities[0]);
          }
        }}
        onKeyDown={(event) => {
          if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && scene?.cities.length) {
            event.preventDefault();
            const index = scene.cities.findIndex((city) => city.id === activeCityRef.current?.id);
            const delta = event.key === 'ArrowRight' ? 1 : -1;
            const nextIndex = (Math.max(0, index) + delta + scene.cities.length) % scene.cities.length;
            activeCityRef.current = scene.cities[nextIndex];
            onCityHover?.(scene.cities[nextIndex]);
            return;
          }
          if ((event.key === 'Enter' || event.key === ' ')
            && activeCityRef.current
            && activeCityRef.current.interactive !== false) {
            event.preventDefault();
            onCityActivate?.(activeCityRef.current, { pointerType: 'keyboard' });
          }
        }}
        style={{
          width: '100%',
          display: 'block',
          touchAction: 'none',
          cursor: 'grab',
          background: TERRAIN[0],
        }}
      />
      {strategicTopology && (!strategicScene || !strategicControls) && terrainIdentity && (
        <p role="status" style={{ position: 'absolute', right: 8, bottom: 8, background: '#211f1b', color: '#fff' }}>
          수역 데이터가 지도와 일치하지 않아 표시하지 않습니다.
        </p>
      )}
      {strategicScene && strategicControls && strategicTopology && (
        <section aria-label="수역 정보" style={{ position: 'absolute', right: 8, bottom: 8, maxWidth: 'min(260px, 65%)',
          maxHeight: '45%', overflow: 'auto', padding: 6, background: 'rgba(20,24,30,0.92)', color: '#eef5ff', fontSize: 12 }}>
          <button type="button" aria-label="수역 레이어" aria-pressed={showWater} onClick={() => setShowWater(value => !value)}>수역</button>
          <div>통행 가능 여부는 수송 조건을 포함한 서버 경로 판정에 따릅니다.</div>
          <ul style={{ paddingLeft: 16, margin: '4px 0' }}>
            {strategicScene.zones.map(shape => <li key={shape.zone.id}>
              <button type="button" title={shape.zone.id} aria-pressed={inspectedWater === shape.zone.id} onClick={() => {
                setInspectedWater(shape.zone.id);
                const view = viewRef.current;
                if (view) updateView(viewAt(sizeRef.current.width, sizeRef.current.height,
                  shape.anchor.col, shape.anchor.row, Math.max(view.scale, 8 * sizeRef.current.dpr)));
              }}>{strategicZoneLabel(shape.zone)}</button>
              <div>{waterControlLabel(strategicControls.get(shape.zone.id)!, strategicTopology.controlVisibility)}</div>
              {strategicTopology.controlVisibility === 'VISIBLE' && strategicControls.get(shape.zone.id)?.controllingNationId &&
                <div>통제 국가 #{strategicControls.get(shape.zone.id)!.controllingNationId}</div>}
              {strategicTopology.controlVisibility === 'VISIBLE' && Boolean(strategicControls.get(shape.zone.id)?.contestingNationIds.length) &&
                <div>경합 국가 {strategicControls.get(shape.zone.id)!.contestingNationIds.map(id => `#${id}`).join(', ')}</div>}
              <div>{shape.zone.connectionStatus === 'ISOLATED_NO_REVIEWED_CONNECTION' ? '검토된 연결 없음' : '검토된 연결 있음'}</div>
            </li>)}
          </ul>
          {strategicTopology.topology.ports.length === 0 && <div>검토된 항구·상륙 지점 없음</div>}
          {selectedServerRoute && <p role="status">{selectedRoutePoints
            ? `서버 경로: ${selectedServerRoute.modes.map(strategicModeLabel).join(' → ')} · 비용 ${selectedServerRoute.totalCost} · ${strategicCapacityLabel(selectedServerRoute.capacity, selectedServerRoute.modes)}`
            : '서버 경로가 현재 지도와 일치하지 않아 표시하지 않습니다.'}</p>}
        </section>
      )}
      <div className="os-iso-map__controls" style={{ position: 'absolute', left: 8, bottom: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <button type="button" aria-label="지도 확대" onClick={() => zoomBy(1.4)}>+</button>
        <button type="button" aria-label="지도 축소" onClick={() => zoomBy(1 / 1.4)}>−</button>
      </div>
      {loadedTiles?.jurisdictionRecords?.length && loadedTiles.parentRegions?.length ? (
        <div
          className="os-iso-map__administrative-layers"
          role="group"
          aria-label="도시 행정 레이어"
          style={{
            position: 'absolute',
            left: 8,
            top: 8,
            display: 'flex',
            gap: 4,
            padding: 3,
            border: '1px solid rgba(232,222,197,0.72)',
            borderRadius: 4,
            background: 'rgba(20,18,15,0.86)',
          }}
        >
          <button
            type="button"
            aria-label="프로빈스 지역 레이어"
            aria-pressed={administrativeLayer === 'PROVINCE'}
            onClick={() => setAdministrativeLayer('PROVINCE')}
          >
            프로빈스(지역)
          </button>
          <button
            type="button"
            aria-label="현급 도시 레이어"
            aria-pressed={administrativeLayer === 'JURISDICTION'}
            onClick={() => setAdministrativeLayer('JURISDICTION')}
          >
            현
          </button>
          <button
            type="button"
            aria-label="군국급 도시 레이어"
            aria-pressed={administrativeLayer === 'COMMANDERY'}
            onClick={() => setAdministrativeLayer('COMMANDERY')}
          >
            군국
          </button>
        </div>
      ) : null}
    </div>
  );
}

export default HanMapCanvas;
