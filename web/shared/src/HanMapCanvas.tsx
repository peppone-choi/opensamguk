'use client';

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type WheelEvent as ReactWheelEvent,
} from 'react';
import {
  cellToScreen,
  centeredView,
  clampView,
  effectiveDpr,
  fitScale,
  junSpanCells,
  maxScaleForDpr,
  scaleForSpan,
  screenToCell,
  viewAt,
  zoomAt,
  type GridSize,
  type IsoView,
} from './isoMap';
import {
  bindProvinceOwnership,
  composeProvincePixels,
  loadProvinceIdentityMap,
  type ProvinceEdge,
  type ProvinceIdentityMap,
} from './provinceMap';
import { isOwnedNationVisual } from './nationVisual';

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
  seatOwner: [number, number][];
  juns: Jun[];
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
  state?: number;
  supply?: boolean;
  isCapital?: boolean;
}

export interface IsoSceneCity extends IsoCityOverlay {
  col: number;
  row: number;
  color: string;
  territoryColor: string;
  iconColor: string;
  layers: string[];
}

export interface IsoScene {
  terrain: string[];
  roads: { from: [number, number]; to: [number, number] }[];
  cities: IsoSceneCity[];
}

export interface IsoSceneOptions {
  currentCityId?: number | null;
  selectedCityId?: number | null;
}

export interface IsoHoverPoint {
  x: number;
  y: number;
}

export interface IsoActivation {
  pointerType: string;
}

export interface HanMapCanvasProps extends IsoSceneOptions {
  mapCode: string;
  tiles?: HanTiles | null;
  terrainUrl?: string | ((mapCode: string) => string);
  provinceUrl?: string | ((mapCode: string) => string);
  provinceMap?: ProvinceIdentityMap | null;
  cities?: readonly IsoCityOverlay[];
  sourceSize?: IsoSourceSize;
  hideCityNames?: boolean;
  className?: string;
  style?: CSSProperties;
  ariaLabel?: string;
  onCityHover?: (city: IsoCityOverlay | null, point?: IsoHoverPoint) => void;
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
] as const;

const NEUTRAL_COLOR = '#555555';
const CASTLE_FILL = '#8b8172';
const CASTLE_STROKE = '#f3dfb0';
const PROVINCE_BORDER = 'rgba(18,20,22,0.58)';
const COMMANDERY_BORDER_DARK = 'rgba(10,12,14,0.82)';
const COMMANDERY_BORDER_LIGHT = 'rgba(225,210,163,0.76)';
const DEFAULT_SOURCE: IsoSourceSize = { width: 700, height: 610 };
export const TIER2_MARKER_ZOOM: Record<string, number> = { COUNTY: 2.19, MARQUISATE: 2.19 };
export const TIER2_LABEL_ZOOM: Record<string, number> = { COUNTY: 5.5, MARQUISATE: 5.5 };
const INITIAL_SCALE_MARGIN = 0.9;

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

export function initialView(width: number, height: number, grid: GridSize, tiles: HanTiles, dpr = 1): IsoView {
  const center = tiles.juns.find((jun) => jun.name === '河南尹' || jun.name === '하남윤') ?? tiles.juns[0];
  if (!center) return centeredView(width, height, grid);
  const span = 3 * junSpanCells(tiles.juns);
  const markerThreshold = Math.min(...Object.values(TIER2_MARKER_ZOOM)) * fitScale(width, height, grid);
  const scale = Math.min(
    scaleForSpan(width, height, span, maxScaleForDpr(dpr)),
    INITIAL_SCALE_MARGIN * markerThreshold,
  );
  return clampView(viewAt(width, height, center.col, center.row, scale), width, height, grid);
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

export function buildIsoScene(
  tiles: HanTiles,
  cities: readonly IsoCityOverlay[],
  source: IsoSourceSize,
  options: IsoSceneOptions,
): IsoScene {
  const grid = { cols: tiles._meta.cols, rows: tiles._meta.rows };
  return {
    terrain: tiles.terrain,
    roads: [],
    cities: cities.map((city) => {
      const { col, row } = mapCityToTile(city, grid, source);
      const owned = isOwnedNationVisual(city.nationId, city.nationColor);
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
        color: territoryColor,
        territoryColor,
        iconColor: CASTLE_FILL,
        layers,
      };
    }),
  };
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
      const color = TERRAIN[Number(terrainRow[col])] ?? TERRAIN[0];
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

function bakePoliticalPaths(map: ProvinceIdentityMap): PoliticalPaths {
  return {
    province: pathFromEdges(map.provinceEdges),
    commandery: pathFromEdges(map.commanderyEdges),
  };
}

function bakePoliticalFill(
  map: ProvinceIdentityMap,
  cities: readonly IsoCityOverlay[],
  grid: GridSize,
  source: IsoSourceSize,
): HTMLCanvasElement | null {
  const canvas = document.createElement('canvas');
  canvas.width = map.width;
  canvas.height = map.height;
  const context = canvas.getContext('2d');
  if (!context) return null;
  const image = context.createImageData(map.width, map.height);
  image.data.set(composeProvincePixels(map, bindProvinceOwnership(map, cities, grid, source)));
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
    city.x,
    city.y,
    city.nationId,
    city.nationColor ?? null,
  ]));
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

function drawScene(
  canvas: HTMLCanvasElement,
  terrain: HTMLCanvasElement,
  political: HTMLCanvasElement | null,
  paths: PoliticalPaths | null,
  scene: IsoScene,
  view: IsoView,
  hideCityNames: boolean,
  dpr: number,
): { city: IsoCityOverlay; x: number; y: number; radius: number }[] {
  const context = canvas.getContext('2d');
  if (!context) return [];
  const width = canvas.width;
  const height = canvas.height;
  const scale = view.scale;
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
  if (paths) {
    context.strokeStyle = PROVINCE_BORDER;
    context.lineWidth = dpr / scale;
    context.stroke(paths.province);
    context.strokeStyle = COMMANDERY_BORDER_DARK;
    context.lineWidth = 3 * dpr / scale;
    context.stroke(paths.commandery);
    context.strokeStyle = COMMANDERY_BORDER_LIGHT;
    context.lineWidth = 1.5 * dpr / scale;
    context.stroke(paths.commandery);
  }
  context.restore();

  const hits: { city: IsoCityOverlay; x: number; y: number; radius: number }[] = [];
  for (const city of scene.cities) {
    const [x, y] = cellToScreen(city.col, city.row, view);
    const radius = Math.max(7, Math.min(18, 6 + city.level * 0.9));
    hits.push({ city, x, y, radius: radius + 6 });
    const owned = isOwnedNationVisual(city.nationId, city.nationColor);
    context.save();
    if (owned && city.supply === false) context.globalAlpha = 0.42;

    context.fillStyle = city.iconColor;
    context.strokeStyle = CASTLE_STROKE;
    context.lineWidth = 1.5;
    context.fillRect(x - radius * 0.7, y - radius * 0.45, radius * 1.4, radius * 0.9);
    context.strokeRect(x - radius * 0.7, y - radius * 0.45, radius * 1.4, radius * 0.9);
    context.fillRect(x - radius * 0.5, y - radius * 0.9, radius * 0.3, radius * 0.5);
    context.fillRect(x + radius * 0.2, y - radius * 0.9, radius * 0.3, radius * 0.5);

    if (owned) {
      context.strokeStyle = '#e8dec5';
      context.beginPath();
      context.moveTo(x + radius * 0.55, y - radius * 0.45);
      context.lineTo(x + radius * 0.55, y - radius * 1.65);
      context.stroke();
      context.fillStyle = city.territoryColor;
      context.beginPath();
      context.moveTo(x + radius * 0.55, y - radius * 1.6);
      context.lineTo(x + radius * 1.45, y - radius * 1.25);
      context.lineTo(x + radius * 0.55, y - radius * 0.9);
      context.closePath();
      context.fill();
    }

    if (city.isCapital) {
      starPath(context, x + radius * 1.2, y - radius * 1.8, Math.max(4, radius * 0.42));
      context.fillStyle = '#ffd84f';
      context.fill();
      context.strokeStyle = '#6a4b00';
      context.stroke();
    }

    if ((city.state ?? 0) > 0) {
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

    if (city.layers.includes('current')) {
      context.save();
      context.globalAlpha = Math.floor(Date.now() / 500) % 2 === 0 ? 1 : 0.3;
      context.strokeStyle = '#ffffff';
      context.lineWidth = 3;
      context.beginPath();
      context.arc(x, y, radius * 1.35, 0, Math.PI * 2);
      context.stroke();
      context.restore();
    }
    if (city.layers.includes('selected')) {
      context.strokeStyle = '#ffd84f';
      context.lineWidth = 3;
      context.strokeRect(x - radius, y - radius, radius * 2, radius * 2);
    }

    if (!hideCityNames) {
      context.textAlign = 'left';
      context.textBaseline = 'alphabetic';
      context.font = `bold ${Math.max(10, Math.min(16, scale * 2.2))}px sans-serif`;
      context.lineWidth = 3;
      context.strokeStyle = 'rgba(0,0,0,0.8)';
      context.fillStyle = '#fff';
      context.strokeText(city.name, x + radius * 0.65, y + radius * 1.2);
      context.fillText(city.name, x + radius * 0.65, y + radius * 1.2);
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
  terrainUrl,
  provinceUrl,
  provinceMap: suppliedProvinceMap,
  cities = [],
  sourceSize = DEFAULT_SOURCE,
  currentCityId,
  selectedCityId,
  hideCityNames = false,
  className = '',
  style,
  ariaLabel,
  onCityHover,
  onCityActivate,
  onMissing,
  onViewChange,
}: HanMapCanvasProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const terrainRef = useRef<HTMLCanvasElement | null>(null);
  const politicalRef = useRef<HTMLCanvasElement | null>(null);
  const politicalPathsRef = useRef<PoliticalPaths | null>(null);
  const viewRef = useRef<IsoView | null>(null);
  const sizeRef = useRef({ width: 0, height: 0, dpr: 1 });
  const hitRef = useRef<{ city: IsoCityOverlay; x: number; y: number; radius: number }[]>([]);
  const dragRef = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const activeCityRef = useRef<IsoCityOverlay | null>(null);
  const pointerTypeRef = useRef('mouse');
  const [loadedTiles, setLoadedTiles] = useState<HanTiles | null>(suppliedTiles ?? null);
  const [loadedProvince, setLoadedProvince] = useState<{
    url: string;
    map: ProvinceIdentityMap | null;
  }>({ url: '', map: null });
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    if (suppliedTiles !== undefined) {
      setLoadedTiles(suppliedTiles);
      setMissing(suppliedTiles == null);
      return;
    }
    let alive = true;
    setMissing(false);
    fetch(resolveTerrainUrl(terrainUrl, mapCode))
      .then((response) => {
        if (!response.ok) throw new Error(`terrain fetch failed: ${response.status}`);
        return response.json() as Promise<HanTiles>;
      })
      .then((nextTiles) => {
        if (alive) setLoadedTiles(nextTiles);
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
  }, [mapCode, onMissing, suppliedTiles, terrainUrl]);

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

  const scene = useMemo(
    () => loadedTiles
      ? buildIsoScene(loadedTiles, cities, sourceSize, { currentCityId, selectedCityId })
      : null,
    [cities, currentCityId, loadedTiles, selectedCityId, sourceSize],
  );
  const provinceMap = useMemo(() => {
    if (!loadedTiles) return null;
    const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
    const requestedUrl = resolveProvinceUrl(provinceUrl, mapCode);
    const candidate = suppliedProvinceMap !== undefined
      ? suppliedProvinceMap
      : loadedProvince.url === requestedUrl ? loadedProvince.map : null;
    return matchesGrid(candidate, grid) ? candidate : null;
  }, [loadedProvince, loadedTiles, mapCode, provinceUrl, suppliedProvinceMap]);

  const sceneRef = useRef<IsoScene | null>(scene);
  const hideCityNamesRef = useRef(hideCityNames);
  const ownershipCitiesRef = useRef(cities);
  sceneRef.current = scene;
  hideCityNamesRef.current = hideCityNames;
  ownershipCitiesRef.current = cities;
  const gridCols = loadedTiles?._meta.cols ?? 0;
  const gridRows = loadedTiles?._meta.rows ?? 0;
  const sourceWidth = sourceSize.width;
  const sourceHeight = sourceSize.height;
  const ownershipKey = politicalOwnershipKey(cities);

  useEffect(() => {
    terrainRef.current = loadedTiles ? bakeTerrain(loadedTiles) : null;
    viewRef.current = null;
  }, [loadedTiles]);

  useEffect(() => {
    politicalPathsRef.current = provinceMap ? bakePoliticalPaths(provinceMap) : null;
  }, [provinceMap]);

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
      latestScene,
      view,
      hideCityNamesRef.current,
      sizeRef.current.dpr,
    );
  }, []);

  useEffect(() => {
    if (!provinceMap || gridCols === 0 || gridRows === 0) {
      politicalRef.current = null;
      render();
      return;
    }
    politicalRef.current = bakePoliticalFill(
      provinceMap,
      ownershipCitiesRef.current,
      { cols: gridCols, rows: gridRows },
      { width: sourceWidth, height: sourceHeight },
    );
    render();
  }, [gridCols, gridRows, ownershipKey, provinceMap, render, sourceHeight, sourceWidth]);

  useEffect(() => {
    render();
  }, [hideCityNames, render, scene]);

  useEffect(() => {
    if (currentCityId == null) return;
    const timer = window.setInterval(render, 500);
    return () => window.clearInterval(timer);
  }, [currentCityId, render]);

  useEffect(() => {
    const box = boxRef.current;
    const canvas = canvasRef.current;
    if (!box || !canvas || !loadedTiles || !terrainRef.current) return;
    const fit = () => {
      const cssWidth = box.clientWidth || 700;
      const cssHeight = Math.round(cssWidth * 0.53);
      const requestedDpr = effectiveDpr(window.devicePixelRatio);
      const previousSize = sizeRef.current;
      const previousView = viewRef.current;
      canvas.width = Math.round(cssWidth * requestedDpr);
      const dpr = canvas.width / cssWidth;
      canvas.height = Math.round(cssHeight * dpr);
      canvas.style.height = `${cssHeight}px`;
      sizeRef.current = { width: canvas.width, height: canvas.height, dpr };
      const grid = { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows };
      if (previousView && previousSize.width > 0 && previousSize.height > 0) {
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
        viewRef.current = initialView(canvas.width, canvas.height, grid, loadedTiles, dpr);
      }
      onViewChange?.(viewRef.current);
      render();
    };
    fit();
    const observer = new ResizeObserver(fit);
    observer.observe(box);
    window.addEventListener('resize', fit);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', fit);
    };
  }, [loadedTiles, onViewChange, render]);

  const updateView = useCallback((next: IsoView) => {
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

  const eventPoint = (event: { clientX: number; clientY: number }) => {
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
  };

  const cityAt = (x: number, y: number) => {
    for (let index = hitRef.current.length - 1; index >= 0; index -= 1) {
      const hit = hitRef.current[index];
      if (Math.hypot(hit.x - x, hit.y - y) <= hit.radius) return hit.city;
    }
    return null;
  };

  const onPointerMove = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const point = eventPoint(event);
    if (!point) return;
    const drag = dragRef.current;
    const view = viewRef.current;
    if (drag && view && loadedTiles) {
      const canvas = canvasRef.current!;
      const rect = canvas.getBoundingClientRect();
      const scaleX = canvas.width / (rect.width || canvas.width || 1);
      const scaleY = canvas.height / (rect.height || canvas.height || 1);
      const dx = (event.clientX - drag.x) * scaleX;
      const dy = (event.clientY - drag.y) * scaleY;
      if (Math.abs(dx) + Math.abs(dy) > 1) drag.moved = true;
      const { width, height } = sizeRef.current;
      updateView(clampView(
        { scale: view.scale, ox: view.ox + dx, oy: view.oy + dy },
        width,
        height,
        { cols: loadedTiles._meta.cols, rows: loadedTiles._meta.rows },
      ));
      drag.x = event.clientX;
      drag.y = event.clientY;
      return;
    }
    const city = cityAt(point.canvasX, point.canvasY);
    activeCityRef.current = city;
    onCityHover?.(city, { x: point.cssX, y: point.cssY });
  };

  const endPointer = (event: ReactPointerEvent<HTMLCanvasElement>) => {
    const drag = dragRef.current;
    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (drag?.moved) return;
    const point = eventPoint(event);
    const city = point ? cityAt(point.canvasX, point.canvasY) : null;
    if (city) onCityActivate?.(city, { pointerType: pointerTypeRef.current });
  };

  if (missing) return null;
  return (
    <div
      ref={boxRef}
      className={`os-iso-map ${className}`.trim()}
      style={{ position: 'relative', width: '100%', ...style }}
    >
      <canvas
        ref={canvasRef}
        className="os-iso-map__canvas"
        role="img"
        aria-label={ariaLabel ?? (loadedTiles ? `${mapCode} 아이소 타일 지도` : '지도 불러오는 중')}
        tabIndex={0}
        onWheel={(event: ReactWheelEvent<HTMLCanvasElement>) => {
          event.preventDefault();
          const point = eventPoint(event);
          zoomBy(event.deltaY < 0 ? 1.15 : 1 / 1.15, point?.canvasX, point?.canvasY);
        }}
        onPointerDown={(event) => {
          pointerTypeRef.current = event.pointerType || 'mouse';
          event.currentTarget.setPointerCapture(event.pointerId);
          dragRef.current = { x: event.clientX, y: event.clientY, moved: false };
        }}
        onPointerMove={onPointerMove}
        onPointerUp={endPointer}
        onPointerCancel={() => {
          dragRef.current = null;
        }}
        onPointerLeave={() => {
          if (!dragRef.current) {
            activeCityRef.current = null;
            onCityHover?.(null);
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
          if ((event.key === 'Enter' || event.key === ' ') && activeCityRef.current) {
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
      <div className="os-iso-map__controls" style={{ position: 'absolute', right: 8, bottom: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
        <button type="button" aria-label="지도 확대" onClick={() => zoomBy(1.4)}>+</button>
        <button type="button" aria-label="지도 축소" onClick={() => zoomBy(1 / 1.4)}>−</button>
      </div>
    </div>
  );
}

export default HanMapCanvas;
