import type { HanTiles } from './HanMapCanvas';

export interface StrategicTopologyBinding {
  worldId: number;
  mapCode: 'han-world-v3';
  topologyRevision: string;
  topologyHash: string;
  baseTilesSha256: string;
  cols: number;
  rows: number;
}

export interface StrategicGeometry {
  id: string;
  terrainCode: number;
  cellCount: number;
  cellRuns: readonly { row: number; startCol: number; endCol: number }[];
}

export interface StrategicWaterZone {
  id: string;
  kind: 'RIVER_REACH' | 'LAKE_BASIN' | 'COASTAL_SEA';
  geometryRef: string;
  connectionStatus: string;
  confidence: string;
  seasonalAvailability: string;
}

export interface StrategicTraversalEdge {
  id: string;
  from: string;
  to: string;
  mode: string;
  movementCost: number;
  capacity: number;
  seasonalAvailability: string;
  supplyAllowed: boolean;
  initiallyOpen?: boolean;
  routeWeightPermille?: number;
}

export interface StrategicRoadGate {
  edgeId: string;
  fromRow: number;
  fromCol: number;
  toRow: number;
  toCol: number;
  terrainCost: number;
  initiallyBuilt: boolean;
  buildable?: boolean;
  overviewTrunk?: boolean;
  historicalRouteIds: readonly string[];
  fortCells?: readonly { provinceId: string; row: number; col: number }[];
  fromTrail?: readonly (readonly [number, number])[];
  toTrail?: readonly (readonly [number, number])[];
}

export interface StrategicMapTopology {
  landProvinceIds: readonly string[];
  geometries: readonly StrategicGeometry[];
  waterZones: readonly StrategicWaterZone[];
  traversalEdges: readonly StrategicTraversalEdge[];
  riverBarriers: readonly { id: string; firstLandProvinceId: string; secondLandProvinceId: string }[];
  ports: readonly { edgeId: string; landProvinceId: string; waterZoneId: string }[];
  activationBlockerCodes: readonly string[];
  roadGates?: readonly StrategicRoadGate[];
}

export interface StrategicWaterControl {
  waterZoneId: string;
  status: 'UNKNOWN' | 'OPEN' | 'CONTESTED' | 'BLOCKED';
  controllingNationId: string | null;
  contestingNationIds: readonly string[];
  revision: string | null;
}

export interface StrategicMapResponse {
  binding: StrategicTopologyBinding;
  /** Null only when the request's knownTopologyHash matched the immutable server snapshot. */
  topology: StrategicMapTopology | null;
  controlVisibility: 'REDACTED' | 'VISIBLE';
  controls: readonly StrategicWaterControl[];
  roadOpenEdgeIds?: readonly string[] | null;
}

export interface StrategicMapSnapshot extends StrategicMapResponse {
  topology: StrategicMapTopology;
}

export interface StrategicMapRoute {
  worldId: number;
  serverId: string;
  nodeKeys: readonly string[];
  edgeIds: readonly string[];
  modes: readonly string[];
  totalCost: number;
  capacity: number;
  topologyRevision: string;
  topologyHash: string;
  pathHash: string;
}

const SHA = /^[a-f0-9]{64}$/;
const MODES = new Set(['LAND', 'FORD', 'BRIDGE', 'FERRY', 'EMBARK', 'DISEMBARK', 'RIVER_UP', 'RIVER_DOWN', 'LAKE', 'COASTAL']);

export function strategicBindingKey(binding: StrategicTopologyBinding): string {
  return [binding.worldId, binding.mapCode, binding.topologyRevision, binding.topologyHash,
    binding.baseTilesSha256, binding.cols, binding.rows].join('|');
}

export function sameStrategicBinding(a: StrategicTopologyBinding | null | undefined, b: StrategicTopologyBinding | null | undefined): boolean {
  return Boolean(a && b && validStrategicBinding(a) && validStrategicBinding(b)
    && strategicBindingKey(a) === strategicBindingKey(b));
}

export function validStrategicBinding(binding: StrategicTopologyBinding): boolean {
  return binding.mapCode === 'han-world-v3' && Number.isSafeInteger(binding.worldId) && binding.worldId > 0
    && typeof binding.topologyRevision === 'string' && binding.topologyRevision.trim().length > 0
    && SHA.test(binding.topologyHash) && SHA.test(binding.baseTilesSha256)
    && Number.isInteger(binding.cols) && binding.cols > 0 && binding.cols <= 4096
    && Number.isInteger(binding.rows) && binding.rows > 0 && binding.rows <= 4096;
}

export interface StrategicZoneShape {
  zone: StrategicWaterZone;
  cells: ReadonlySet<number>;
  fill: Path2D;
  outline: Path2D;
  hatch: Path2D;
  anchor: { col: number; row: number };
}

export interface StrategicMapScene {
  zones: readonly StrategicZoneShape[];
  byCell: ReadonlyMap<number, StrategicWaterZone>;
  edgesById: ReadonlyMap<string, StrategicTraversalEdge>;
  roadGates: readonly StrategicRoadGate[];
  roadPaths?: { ordinary: Path2D; trunk: Path2D; historical: Path2D; junctions: Path2D };
}

/** Decode only reviewed cells. This is a renderer, never a route search or coastline flood-fill. */
export function buildStrategicMapScene(topology: StrategicMapTopology, tiles: HanTiles,
  roadOpenEdgeIds?: readonly string[] | null): StrategicMapScene {
  const { cols, rows } = tiles._meta;
  const unique = <T>(values: readonly T[], key: (value: T) => string) => {
    const result = new Map(values.map(value => [key(value), value]));
    if (result.size !== values.length || [...result.keys()].some(id => typeof id !== 'string' || !id.trim())) throw new Error('Duplicate strategic identity');
    return result;
  };
  const geometries = unique(topology.geometries, geometry => geometry.id);
  const zoneRecords = unique(topology.waterZones, zone => zone.id);
  const lands = unique(topology.landProvinceIds, id => id);
  const edgesById = unique(topology.traversalEdges, edge => edge.id);
  if (geometries.size !== zoneRecords.size || new Set(topology.waterZones.map(zone => zone.geometryRef)).size !== geometries.size) throw new Error('Water geometry inventory mismatch');
  const nodeExists = (node: string) => node.startsWith('land:') ? lands.has(node.slice(5))
    : node.startsWith('water:') && zoneRecords.has(node.slice(6));
  for (const edge of edgesById.values()) {
    if (!nodeExists(edge.from) || !nodeExists(edge.to) || edge.from === edge.to || !MODES.has(edge.mode)) throw new Error('Invalid strategic edge');
  }
  const roadGates = topology.roadGates ?? [];
  const seenGates = new Set<string>();
  for (const gate of roadGates) {
    const edge = edgesById.get(gate.edgeId);
    if (!edge || edge.mode !== 'LAND' || seenGates.has(gate.edgeId) ||
      ![gate.fromRow, gate.fromCol, gate.toRow, gate.toCol, gate.terrainCost].every(Number.isInteger) ||
      gate.fromRow < 0 || gate.fromRow >= rows || gate.toRow < 0 || gate.toRow >= rows ||
      gate.fromCol < 0 || gate.fromCol >= cols || gate.toCol < 0 || gate.toCol >= cols ||
      Math.abs(gate.fromRow - gate.toRow) + Math.abs(gate.fromCol - gate.toCol) !== 1 ||
      gate.terrainCost <= 0 || edge.initiallyOpen !== gate.initiallyBuilt ||
      (gate.buildable != null && typeof gate.buildable !== 'boolean') ||
      (gate.overviewTrunk != null && typeof gate.overviewTrunk !== 'boolean') ||
      !Array.isArray(gate.historicalRouteIds) || !Array.isArray(gate.fortCells) || !gate.fortCells.length)
      throw new Error('Invalid road gate');
    seenGates.add(gate.edgeId);
  }
  const openRoads = roadOpenEdgeIds == null
    ? new Set(roadGates.filter(gate => gate.initiallyBuilt).map(gate => gate.edgeId))
    : new Set(roadOpenEdgeIds);
  if ([...openRoads].some(id => !seenGates.has(id))) throw new Error('Unknown open road edge');
  const roadPaths = { ordinary: new Path2D(), trunk: new Path2D(), historical: new Path2D(), junctions: new Path2D() };
  const roadJunctions = new Set<string>();
  function addCurvedTrail(path: Path2D, cells: readonly (readonly [number, number])[]) {
    if (cells.length < 2) return;
    const radius = 18;
    const prefixRows = [0], prefixCols = [0];
    for (const [row, col] of cells) {
      prefixRows.push(prefixRows.at(-1)! + row);
      prefixCols.push(prefixCols.at(-1)! + col);
    }
    const points = cells.map((cell, i): readonly [number, number] => {
      if (i === 0 || i === cells.length - 1 || cells.length < 7) return cell;
      const first = Math.max(0, i - radius), end = Math.min(cells.length, i + radius + 1);
      return [(prefixRows[end] - prefixRows[first]) / (end - first),
        (prefixCols[end] - prefixCols[first]) / (end - first)];
    });
    path.moveTo(points[0][1], points[0][0]);
    for (let i = 1; i < points.length - 1; i += 1) {
      const here = points[i], next = points[i + 1];
      path.quadraticCurveTo(here[1], here[0], (here[1] + next[1]) / 2, (here[0] + next[0]) / 2);
    }
    path.lineTo(points.at(-1)![1], points.at(-1)![0]);
  }
  for (const gate of roadGates) {
    if (!openRoads.has(gate.edgeId)) continue;
    const historical = gate.historicalRouteIds.length > 0;
    const path = historical ? roadPaths.historical : roadPaths.ordinary;
    const from = gate.fromTrail ?? [[gate.fromRow, gate.fromCol] as const];
    const to = gate.toTrail ?? [[gate.toRow, gate.toCol] as const];
    for (const trail of [from, to]) {
      if (!trail.length) throw new Error('Empty road trail');
      for (let i = 0; i < trail.length; i += 1) {
        const [row, col] = trail[i];
        if (!Number.isInteger(row) || !Number.isInteger(col) || row < 0 || row >= rows || col < 0 || col >= cols ||
          (i > 0 && (Math.max(Math.abs(row - trail[i - 1][0]), Math.abs(col - trail[i - 1][1])) !== 1)))
          throw new Error('Invalid road trail');
      }
    }
    if (from.at(-1)?.[0] !== gate.fromRow || from.at(-1)?.[1] !== gate.fromCol ||
      to.at(-1)?.[0] !== gate.toRow || to.at(-1)?.[1] !== gate.toCol) throw new Error('Road trail misses gate');
    for (const [row, col] of [from[0], to[0]]) {
      const key = `${row}:${col}`;
      if (roadJunctions.has(key)) continue;
      roadJunctions.add(key);
      roadPaths.junctions.moveTo(col + 0.75, row);
      roadPaths.junctions.arc(col, row, 0.75, 0, Math.PI * 2);
    }
    addCurvedTrail(path, [...from, [gate.toRow, gate.toCol], ...to.slice().reverse()]);
    if (gate.overviewTrunk && !historical)
      addCurvedTrail(roadPaths.trunk, [...from, [gate.toRow, gate.toCol], ...to.slice().reverse()]);
  }
  const byCell = new Map<number, StrategicWaterZone>();
  const zones = topology.waterZones.map(zone => {
    const geometry = geometries.get(zone.geometryRef);
    const expectedTerrain = { COASTAL_SEA: 'SEA', LAKE_BASIN: 'LAKE', RIVER_REACH: 'RIVER' }[zone.kind];
    if (!geometry || !expectedTerrain || tiles._meta.terrainLegend[String(geometry.terrainCode)] !== expectedTerrain) throw new Error('Water terrain mismatch');
    const cells = new Set<number>();
    for (const run of geometry.cellRuns) {
      if (![run.row, run.startCol, run.endCol].every(Number.isInteger)
        || run.row < 0 || run.row >= rows || run.startCol < 0 || run.endCol >= cols || run.startCol > run.endCol) throw new Error('Invalid water cell range');
      for (let col = run.startCol; col <= run.endCol; col += 1) {
        const cell = run.row * cols + col;
        if (byCell.has(cell) || tiles.terrain[run.row]?.[col] !== String(geometry.terrainCode)) throw new Error('Water cell conflict');
        cells.add(cell);
        byCell.set(cell, zone);
      }
    }
    if (!cells.size || cells.size !== geometry.cellCount) throw new Error('Water cell count mismatch');
    const fill = new Path2D();
    const outline = new Path2D();
    const hatch = new Path2D();
    for (const cell of cells) {
      const col = cell % cols;
      const row = Math.floor(cell / cols);
      fill.rect(col - 0.5, row - 0.5, 1, 1);
      hatch.moveTo(col - 0.4, row + 0.4);
      hatch.lineTo(col + 0.4, row - 0.4);
      for (const [dx, dy, x1, y1, x2, y2] of [
        [-1, 0, -0.5, -0.5, -0.5, 0.5], [1, 0, 0.5, -0.5, 0.5, 0.5],
        [0, -1, -0.5, -0.5, 0.5, -0.5], [0, 1, -0.5, 0.5, 0.5, 0.5],
      ]) {
        if (col + dx >= 0 && col + dx < cols && cells.has((row + dy) * cols + col + dx)) continue;
        outline.moveTo(col + x1, row + y1);
        outline.lineTo(col + x2, row + y2);
      }
    }
    const middle = [...cells][Math.floor(cells.size / 2)];
    return { zone, cells, fill, outline, hatch, anchor: { col: middle % cols, row: Math.floor(middle / cols) } };
  });
  return { zones, byCell, edgesById, roadGates, roadPaths };
}

export function validatedWaterControls(snapshot: StrategicMapSnapshot): ReadonlyMap<string, StrategicWaterControl> {
  if (!['REDACTED', 'VISIBLE'].includes(snapshot.controlVisibility)) throw new Error('Unknown control visibility');
  const controls = new Map(snapshot.controls.map(control => [control.waterZoneId, control]));
  if (controls.size !== snapshot.controls.length || controls.size !== snapshot.topology.waterZones.length) throw new Error('Control inventory mismatch');
  for (const zone of snapshot.topology.waterZones) {
    const control = controls.get(zone.id);
    if (!control || !['UNKNOWN', 'OPEN', 'CONTESTED', 'BLOCKED'].includes(control.status)) throw new Error('Invalid water control');
    if ((snapshot.controlVisibility === 'REDACTED' || control.status === 'UNKNOWN')
      && (control.status !== 'UNKNOWN' || control.controllingNationId !== null || control.contestingNationIds.length || control.revision !== null)) throw new Error('Nonpublic control data');
  }
  return controls;
}

export function waterControlLabel(control: StrategicWaterControl, visibility: StrategicMapResponse['controlVisibility']): string {
  if (visibility === 'REDACTED') return '통제 정보 비공개';
  return { UNKNOWN: '확인되지 않음', OPEN: '봉쇄 없음', CONTESTED: '통제 경합', BLOCKED: '봉쇄' }[control.status];
}

export const strategicModeLabel = (mode: string): string => ({
  LAND: '육로', FORD: '여울', BRIDGE: '다리', FERRY: '나루', EMBARK: '승선', DISEMBARK: '상륙',
  RIVER_UP: '강 상행', RIVER_DOWN: '강 하행', LAKE: '호수', COASTAL: '연안',
}[mode] ?? mode);

/** Only the canonical dry-LAND projection uses Int.MAX_VALUE as an unrestricted edge sentinel. */
export function strategicCapacityLabel(capacity: number, modes: readonly string[]): string {
  return capacity === 2147483647 && modes.length > 0 && modes.every(mode => mode === 'LAND')
    ? '경로 처리 한도 없음' : `수송대 처리 한도 ${capacity}`;
}

/** Names for the two reviewed artifacts only; an unknown identity is never silently renamed. */
export function strategicZoneLabel(zone: StrategicWaterZone): string {
  const reviewedNames: Record<string, string> = {
    'water-zone:lake-pengli-poyang': '팽려·파양호',
    'water-zone:coastal-qiongzhou-strait': '瓊州海峽 연안',
  };
  return reviewedNames[zone.id] ?? `${zone.kind === 'COASTAL_SEA' ? '연안' : zone.kind === 'LAKE_BASIN' ? '호수' : '강'} · ${zone.id}`;
}

/** Validate and project the server's ordered path only; never synthesize missing nodes or edges. */
export function serverRoutePoints(route: StrategicMapRoute, binding: StrategicTopologyBinding,
  scene: StrategicMapScene, anchors: ReadonlyMap<string, { col: number; row: number }>,
  currentServerId: string | undefined): readonly { col: number; row: number }[] | null {
  if (!currentServerId || route.serverId !== currentServerId || route.worldId !== binding.worldId
    || route.topologyRevision !== binding.topologyRevision || route.topologyHash !== binding.topologyHash
    || !SHA.test(route.pathHash) || route.nodeKeys.length < 2 || route.edgeIds.length !== route.nodeKeys.length - 1
    || route.modes.length !== route.edgeIds.length || !Number.isSafeInteger(route.totalCost) || route.totalCost <= 0
    || !Number.isSafeInteger(route.capacity) || route.capacity <= 0) return null;
  for (let index = 0; index < route.edgeIds.length; index += 1) {
    const edge = scene.edgesById.get(route.edgeIds[index]);
    if (!edge || edge.mode !== route.modes[index]) return null;
    const a = route.nodeKeys[index]; const b = route.nodeKeys[index + 1];
    const directional = ['RIVER_UP', 'RIVER_DOWN', 'EMBARK', 'DISEMBARK'].includes(edge.mode);
    if (!(edge.from === a && edge.to === b) && (directional || !(edge.from === b && edge.to === a))) return null;
  }
  const points = route.nodeKeys.map(node => anchors.get(node));
  return points.every((point): point is { col: number; row: number } => Boolean(point)) ? points : null;
}
