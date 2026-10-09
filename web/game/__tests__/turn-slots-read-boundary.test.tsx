// Display boundary of the 12-slot read. Malformed or foreign responses are read errors, never twelve empty slots.
// A pre-B1 row without a revision is display-only (revision null); cancellation still requires the strict B1 read.
import { renderHook, waitFor } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import { parseReservedCommands, ReservedReadError } from '../lib/api/reservation-cancel-contract';
import { IDLE } from '../lib/command-flow/reservation-cancel-state';
import { cancelEntry } from '../lib/command-flow/reservation-cancel-view';
import { useTurnSlots } from '../lib/turn-slots';
import { farm, rev } from './fixtures/reservation-cancel';

vi.mock('../lib/api', () => ({ api: { reservedCommands: vi.fn(), mapPreview: vi.fn(), gameConst: vi.fn(), travelOptions: vi.fn(), deployOptions: vi.fn(), legacyDirectOptions: vi.fn() } }));
vi.mock('../lib/serverGameUrl', () => ({ readServerCookie: vi.fn(() => undefined) }));

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api.mapPreview).mockResolvedValue({ cities: [] } as never);
  vi.mocked(api.gameConst).mockResolvedValue({ gameUnitConst: [] } as never);
});

it('propagates each stored revision; empty slots carry none', async () => {
  vi.mocked(api.reservedCommands).mockResolvedValue({ result: true, generalId: 1, slots: [farm(0), farm(4)], turnTime: '2026-10-09 22:40:00', turnTerm: 60 });
  const { result } = renderHook(() => useTurnSlots(1));
  await waitFor(() => expect(result.current.load.state).toBe('ready'));
  if (result.current.load.state !== 'ready') return;
  expect(result.current.load.slots.map(slot => slot.revision)).toEqual([rev(0), null, null, null, rev(4), null, null, null, null, null, null, null]);
  expect(result.current.load.slots[0]).toMatchObject({ state: 'reserved', name: '농지개간' });
});

it('a pre-B1 row without a revision is shown read-only and cannot start a cancellation', async () => {
  vi.mocked(api.reservedCommands).mockResolvedValue({ result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {} }, farm(1)] } as never);
  const { result } = renderHook(() => useTurnSlots(1));
  await waitFor(() => expect(result.current.load.state).toBe('ready'));
  if (result.current.load.state !== 'ready') return;
  const [legacy, current] = result.current.load.slots;
  expect(legacy).toMatchObject({ state: 'reserved', name: '농지개간', revision: null });
  expect(current.revision).toBe(rev(1));
  expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row: legacy, phase: IDLE }).kind).toBe('blocked');
  expect(cancelEntry({ verified: true, server: 'pep', journalOk: true, row: current, phase: IDLE }).kind).toBe('available');
  // The strict cancellation read of the same body still refuses it — no preflight can match a revision-less row.
  expect(() => parseReservedCommands({ result: true, generalId: 1, slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {} }] }, 1)).toThrow(ReservedReadError);
});

it.each([
  ['non-UUID revision', { result: true, generalId: 1, slots: [{ ...farm(0), revision: '7' }] }],
  ['null revision', { result: true, generalId: 1, slots: [{ ...farm(0), revision: null }] }],
  ['foreign actor', { result: true, generalId: 2, slots: [] }],
  ['duplicate slot', { result: true, generalId: 1, slots: [farm(2), farm(2, rev(22))] }],
  ['result false', { result: false, generalId: 1, slots: [] }],
  ['slot out of range', { result: true, generalId: 1, slots: [farm(12)] }],
])('%s is a read error, never twelve empty slots', async (_, body) => {
  vi.mocked(api.reservedCommands).mockResolvedValue(body as never);
  const { result } = renderHook(() => useTurnSlots(1));
  await waitFor(() => expect(result.current.load.state).toBe('error'));
});
