import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, test } from 'vitest';
import { decodeJoiningSnapshot } from '../lib/battle/joining-snapshot';
import { decodeServerFrame } from '../lib/battle/protocol';

const original = readFileSync(resolve(__dirname, '../../../app/game-api/src/test/resources/battle/v2-joining-snapshot.json'), 'utf8');
const frame = () => JSON.parse(original);
test('unchanged producer golden is strictly accepted without legacy alias or enriched facts', () => {
  expect(decodeJoiningSnapshot(frame())).toEqual(frame());
  expect(decodeServerFrame(original).ok).toBe(false);
  const decoded = decodeJoiningSnapshot(frame())!;
  expect(decoded.ownUnits[0].sourceKey.sourceId).toBe('701');
  expect(decoded.ownUnits[0]).not.toHaveProperty('ownerGeneralId');
  expect(decoded.ownUnits[0]).not.toHaveProperty('morale');
  expect(decoded).not.toHaveProperty('units');
});

test.each([
  ['version', (f: any) => { f.schemaVersion = 1; }],
  ['type', (f: any) => { f.t = 'DAMAGED_SNAPSHOT_TYPE'; }],
  ['phase', (f: any) => { f.phase = 'RUNNING'; }],
  ['missing phase', (f: any) => { delete f.phase; }],
  ['world', (f: any) => { f.worldId = 0; }],
  ['empty battle', (f: any) => { f.battleId = ''; }],
  ['epoch number', (f: any) => { f.sessionEpoch = 3; }],
  ['epoch zero', (f: any) => { f.sessionEpoch = '0'; }],
  ['epoch overflow', (f: any) => { f.sessionEpoch = '9223372036854775808'; }],
  ['tick', (f: any) => { f.tick = 1; }],
  ['event', (f: any) => { f.eventSeq = '1'; }],
  ['event number', (f: any) => { f.eventSeq = 0; }],
  ['authority missing', (f: any) => { delete f.authorityRevision; }],
  ['deadline missing', (f: any) => { delete f.joinDeadlineAt; }],
  ['deadline invalid', (f: any) => { f.joinDeadlineAt = '2026-02-30T01:00:00Z'; }],
  ['field missing', (f: any) => { delete f.field; }],
  ['board overflow', (f: any) => { f.field.boardId = 214; }],
  ['terrain missing', (f: any) => { delete f.field.terrainInputSha256; }],
  ['terrain invalid', (f: any) => { f.field.terrainInputSha256 = 'unknown'; }],
  ['kind invalid', (f: any) => { f.field.kind = 'UNKNOWN'; }],
  ['units missing', (f: any) => { delete f.ownUnits; }],
  ['unit key missing', (f: any) => { delete f.ownUnits[0].sourceKey; }],
  ['number source', (f: any) => { f.ownUnits[0].sourceKey.sourceId = 701; }],
  ['noncanonical source', (f: any) => { f.ownUnits[0].sourceKey.sourceId = '0701'; }],
  ['zero source', (f: any) => { f.ownUnits[0].sourceKey.sourceId = '0'; }],
  ['Int overflow source', (f: any) => { f.ownUnits[0].sourceKey.sourceId = '2147483648'; }],
  ['other own kind', (f: any) => { f.ownUnits[0].sourceKey.kind = 'CITY_GARRISON_BUGOK'; }],
  ['city on retinue', (f: any) => { f.ownUnits[0].sourceKey.cityId = 1; }],
  ['cell missing', (f: any) => { delete f.ownUnits[0].cell; }],
  ['cell invalid', (f: any) => { f.ownUnits[0].cell.row = 64; }],
  ['troops missing', (f: any) => { delete f.ownUnits[0].troops; }],
  ['troops zero', (f: any) => { f.ownUnits[0].troops = 0; }],
  ['troops overflow', (f: any) => { f.ownUnits[0].troops = 2147483648; }],
  ['deployment missing', (f: any) => { delete f.deployment; }],
  ['revision number', (f: any) => { f.deployment.revision = 4; }],
  ['revision invalid', (f: any) => { f.deployment.revision = '-1'; }],
  ['allowed missing', (f: any) => { delete f.deployment.allowedCells; }],
  ['allowed duplicates', (f: any) => { f.deployment.allowedCells.push(f.deployment.allowedCells[0]); }],
  ['cell outside allowed', (f: any) => { f.deployment.allowedCells = []; }],
  ['positions missing', (f: any) => { delete f.deployment.ownPositions; }],
  ['positions absent', (f: any) => { f.deployment.ownPositions = []; }],
  ['positions extra', (f: any) => { f.deployment.ownPositions.push(f.deployment.ownPositions[0]); }],
  ['positions key mismatch', (f: any) => { f.deployment.ownPositions[0].sourceKey.sourceId = '702'; }],
  ['positions cell mismatch', (f: any) => { f.deployment.ownPositions[0].cell.col = 1; }],
  ['duplicate unit', (f: any) => { f.ownUnits.push(f.ownUnits[0]); }],
  ['distinct units share cell', (f: any) => { f.ownUnits.push({ ...f.ownUnits[0], sourceKey: { kind: 'RETINUE', sourceId: '702' } }); }],
  ['allowed invalid cell', (f: any) => { f.deployment.allowedCells[1].col = 64; }],
  ['positions duplicate key', (f: any) => {
    f.ownUnits.push({ sourceKey: { kind: 'RETINUE', sourceId: '702' }, cell: { row: 0, col: 1 }, troops: 100 });
    f.deployment.ownPositions.push(f.deployment.ownPositions[0]);
  }],
])('rejects mandatory/invariant failure: %s', (_name, mutate) => {
  const value = frame(); mutate(value);
  expect(decodeJoiningSnapshot(value)).toBeNull();
});

test('real own projection can be empty; no synthetic units or slot cap', () => {
  const value = frame(); value.ownUnits = []; value.deployment.ownPositions = [];
  expect(decodeJoiningSnapshot(value)?.ownUnits).toEqual([]);
  for (let i = 0; i < 14; i++) {
    value.deployment.allowedCells.push({ row: 1, col: i });
    const sourceKey = { kind: 'RETINUE', sourceId: String(701 + i) };
    const cell = { row: 1, col: i };
    value.ownUnits.push({ sourceKey, cell, troops: 100 });
    value.deployment.ownPositions.push({ sourceKey, cell });
  }
  expect(decodeJoiningSnapshot(value)?.ownUnits).toHaveLength(14);
});

test('accepts canonical source upper bound and nanosecond Instant without numeric conversion', () => {
  const value = frame();
  value.ownUnits[0].sourceKey.sourceId = '2147483647';
  value.deployment.ownPositions[0].sourceKey.sourceId = '2147483647';
  value.joinDeadlineAt = '2026-10-06T01:00:00.123456789Z';
  expect(decodeJoiningSnapshot(value)).toEqual(value);
});
