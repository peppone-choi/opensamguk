// Actual tick-zero producer contract; independent of the C2 draft RUNNING codec.
import { isCell, isInt, isLongString, type Cell } from './wire';

export interface JoiningSourceKey {
  readonly kind: 'RETINUE';
  readonly sourceId: string;
}
export interface JoiningOwnUnit {
  readonly sourceKey: JoiningSourceKey;
  readonly cell: Cell;
  readonly troops: number;
}
export interface JoiningSnapshot {
  readonly schemaVersion: 2;
  readonly t: 'SNAPSHOT';
  readonly phase: 'JOINING';
  readonly worldId: number;
  readonly battleId: string;
  readonly sessionEpoch: string;
  readonly tick: 0;
  readonly eventSeq: '0';
  readonly joinDeadlineAt: string;
  readonly authorityRevision: string;
  readonly field: { readonly boardId: number; readonly kind: 'FIELD'; readonly terrainInputSha256: string };
  readonly ownUnits: readonly JoiningOwnUnit[];
  readonly deployment: {
    readonly revision: string;
    readonly allowedCells: readonly Cell[];
    readonly ownPositions: readonly { readonly sourceKey: JoiningSourceKey; readonly cell: Cell }[];
  };
}

function record(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value);
}
function sourceKey(value: unknown): value is JoiningSourceKey {
  return record(value) && value.kind === 'RETINUE' && value.cityId == null &&
    typeof value.sourceId === 'string' && /^[1-9][0-9]*$/.test(value.sourceId) &&
    value.sourceId.length <= 10 && BigInt(value.sourceId) <= BigInt(2147483647);
}
function instant(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false;
  const millis = Date.parse(value);
  return Number.isFinite(millis) && new Date(millis).toISOString().slice(0, 19) === value.slice(0, 19);
}
const cellId = (cell: Cell) => `${cell.row}:${cell.col}`;
export const joiningUnitId = (key: JoiningSourceKey): string => `RETINUE:${key.sourceId}`;

/** No defaults, legacy aliases or private owner/morale enrichment. */
export function decodeJoiningSnapshot(value: unknown): JoiningSnapshot | null {
  if (!record(value) || value.schemaVersion !== 2 || value.t !== 'SNAPSHOT' || value.phase !== 'JOINING' ||
    !isInt(value.worldId, 1, 2147483647) || typeof value.battleId !== 'string' || !value.battleId.trim() ||
    !isLongString(value.sessionEpoch, true) || value.tick !== 0 || value.eventSeq !== '0' ||
    !instant(value.joinDeadlineAt) || !isLongString(value.authorityRevision)) return null;
  const field = value.field;
  if (!record(field) || !isInt(field.boardId, 0, 213) || field.kind !== 'FIELD' ||
    typeof field.terrainInputSha256 !== 'string' || !/^[0-9a-f]{64}$/.test(field.terrainInputSha256)) return null;
  const deployment = value.deployment;
  if (!record(deployment) || !isLongString(deployment.revision) || !Array.isArray(deployment.allowedCells) ||
    deployment.allowedCells.length > 4096 || !deployment.allowedCells.every(isCell) ||
    !Array.isArray(deployment.ownPositions) || !Array.isArray(value.ownUnits)) return null;
  const allowedCells = deployment.allowedCells;
  const allowed = new Set(allowedCells.map(cellId));
  if (allowed.size !== allowedCells.length) return null;
  const ownUnits: JoiningOwnUnit[] = [];
  const keys = new Set<string>();
  const cells = new Set<string>();
  for (const unit of value.ownUnits) {
    if (!record(unit) || !sourceKey(unit.sourceKey) || !isCell(unit.cell) || !isInt(unit.troops, 1, 2147483647)) return null;
    const id = joiningUnitId(unit.sourceKey);
    const cell = cellId(unit.cell);
    if (keys.has(id) || cells.has(cell) || !allowed.has(cell)) return null;
    keys.add(id); cells.add(cell);
    ownUnits.push({ sourceKey: { kind: 'RETINUE', sourceId: unit.sourceKey.sourceId }, cell: { row: unit.cell.row, col: unit.cell.col }, troops: unit.troops });
  }
  if (deployment.ownPositions.length !== ownUnits.length) return null;
  const positions = new Map<string, Cell>();
  for (const position of deployment.ownPositions) {
    if (!record(position) || !sourceKey(position.sourceKey) || !isCell(position.cell)) return null;
    const id = joiningUnitId(position.sourceKey);
    if (positions.has(id) || !keys.has(id)) return null;
    positions.set(id, position.cell);
  }
  if (ownUnits.some(unit => cellId(positions.get(joiningUnitId(unit.sourceKey))!) !== cellId(unit.cell))) return null;
  return {
    schemaVersion: 2, t: 'SNAPSHOT', phase: 'JOINING', worldId: value.worldId, battleId: value.battleId,
    sessionEpoch: value.sessionEpoch, tick: 0, eventSeq: '0', joinDeadlineAt: value.joinDeadlineAt,
    authorityRevision: value.authorityRevision,
    field: { boardId: field.boardId, kind: 'FIELD', terrainInputSha256: field.terrainInputSha256 }, ownUnits,
    deployment: { revision: deployment.revision, allowedCells: allowedCells.map(cell => ({ row: cell.row, col: cell.col })),
      ownPositions: ownUnits.map(unit => ({ sourceKey: unit.sourceKey, cell: unit.cell })) },
  };
}
