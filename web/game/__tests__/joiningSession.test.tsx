import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { fetchGame } from '../lib/api';
import { useBattleSession } from '../lib/battle/use-battle-session';

vi.mock('../lib/api', () => ({ fetchGame: vi.fn() }));
const golden = readFileSync(resolve(__dirname, '../../../app/game-api/src/test/resources/battle/v2-joining-snapshot.json'), 'utf8');

class Socket {
  static readonly OPEN = 1;
  static instances: Socket[] = [];
  readyState = 1;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  constructor() { Socket.instances.push(this); }
  send(data: string) { this.sent.push(data); }
  close() { this.readyState = 3; }
  receive(data: string) { act(() => this.onmessage?.({ data })); }
}

beforeEach(() => {
  Socket.instances = [];
  vi.stubGlobal('WebSocket', Socket);
  vi.mocked(fetchGame).mockResolvedValue(new Response(JSON.stringify({ joinTicket: 'test-only' })));
});
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });

async function connect() {
  const hook = renderHook(() => useBattleSession('pep', 1, 'battle-v2'));
  await waitFor(() => expect(Socket.instances).toHaveLength(1));
  return { hook, socket: Socket.instances[0] };
}

test('actual producer golden opens only the JOINING read view; both input paths send zero', async () => {
  const { hook, socket } = await connect();
  socket.receive(golden);
  expect(hook.result.current.session).toMatchObject({ state: 'joining', snapshot: JSON.parse(golden) });
  act(() => {
    hook.result.current.move('RETINUE:701', { row: 0, col: 1 });
    hook.result.current.command({ allMine: true }, 1, 'CHARGE', 'HOME');
  });
  expect(socket.sent).toEqual([]);
  expect(socket.readyState).toBe(1);
});

test('JOINING then malformed data preserves protocol error through close and late frames', async () => {
  const { hook, socket } = await connect();
  socket.receive(golden);
  expect(hook.result.current.session.state).toBe('joining');
  socket.receive('{invalid');
  expect(hook.result.current.session.state).toBe('protocol-error');
  act(() => socket.onclose?.());
  socket.receive(golden);
  expect(hook.result.current.session.state).toBe('protocol-error');
  expect(socket.sent).toEqual([]);
});

test('retry gets a fresh ticket, rejects stale callbacks, and can read actual JOINING again', async () => {
  const { hook, socket } = await connect();
  socket.receive('{invalid');
  vi.mocked(fetchGame).mockResolvedValueOnce(new Response(JSON.stringify({ joinTicket: 'fresh-test-only' })));
  act(() => hook.result.current.retry());
  await waitFor(() => expect(Socket.instances).toHaveLength(2));
  socket.receive(golden);
  act(() => socket.onclose?.());
  expect(hook.result.current.session.state).toBe('connecting');
  Socket.instances[1].receive(golden);
  expect(hook.result.current.session.state).toBe('joining');
  expect(fetchGame).toHaveBeenCalledTimes(2);
  expect(Socket.instances.flatMap(s => s.sent)).toEqual([]);
});

test.each([
  ['world', { worldId: 2 }], ['battle', { battleId: 'other' }], ['phase', { phase: 'RUNNING' }],
  ['type', { t: 'DAMAGED_SNAPSHOT_TYPE' }],
  ['event', { eventSeq: '1' }], ['tick', { tick: 1 }], ['missing own', { ownUnits: null }],
])('rejects producer frame with invalid %s', async (_name, changes) => {
  const { hook, socket } = await connect();
  socket.receive(JSON.stringify({ ...JSON.parse(golden), ...changes }));
  expect(hook.result.current.session.state).toBe('protocol-error');
  expect(socket.sent).toEqual([]);
});

test('malformed JOINING with draft units cannot fall back; an established JOINING never opens draft inputs', async () => {
  const { hook, socket } = await connect();
  const draft = {
    schemaVersion: 2, t: 'SNAPSHOT', worldId: 1, battleId: 'battle-v2', sessionEpoch: '3', tick: 0,
    eventSeq: '0', authorityRevision: '11', field: { boardId: 64, kind: 'FIELD' },
    units: [{ sourceKey: { kind: 'RETINUE', sourceId: 701 }, ownerGeneralId: 7, cell: { row: 0, col: 0 }, troops: 100, morale: 100 }],
  };
  socket.receive(JSON.stringify({ ...draft, phase: 'JOINING' }));
  expect(hook.result.current.session.state).toBe('protocol-error');
  vi.mocked(fetchGame).mockResolvedValueOnce(new Response(JSON.stringify({ joinTicket: 'test-only' })));
  act(() => hook.result.current.retry());
  await waitFor(() => expect(Socket.instances).toHaveLength(2));
  const fresh = Socket.instances[1]; fresh.receive(golden);
  expect(hook.result.current.session.state).toBe('joining');
  fresh.receive(JSON.stringify(draft));
  expect(hook.result.current.session.state).toBe('protocol-error');
  act(() => hook.result.current.command({ allMine: true }, 1, 'CHARGE', 'HOME'));
  expect(Socket.instances.flatMap(s => s.sent)).toEqual([]);
});

test('empty actual projection is still JOINING and close has its existing disconnected meaning', async () => {
  const { hook, socket } = await connect();
  const frame = JSON.parse(golden); frame.ownUnits = []; frame.deployment.ownPositions = [];
  socket.receive(JSON.stringify(frame));
  expect(hook.result.current.session).toMatchObject({ state: 'joining', snapshot: { ownUnits: [] } });
  act(() => socket.onclose?.());
  expect(hook.result.current.session.state).toBe('closed');
  expect(socket.sent).toEqual([]);
});
