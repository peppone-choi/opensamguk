// War room → real CommandFlow → real useReservationCancel: front-info global.generation scopes the cancellation intent.
// Only the shell, the map and the session are replaced; HTTP is the B1 fixture server, authentication the verified AuthProvider.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WarRoomPage from '../components/campaign/WarRoomPage';
import { AuthProvider } from '../lib/auth-context';
import { readJournal, type JournalRecord } from '../lib/command-flow/reservation-cancel-journal';
import { farm, installCancelServer, onServerPath, resetTab, rev, type FakeCancelServer } from './fixtures/reservation-cancel';

vi.setConfig({ testTimeout: 20_000 });
vi.mock('next/navigation', () => ({
  usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams('slot=1'),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));
vi.mock('../components/GameShell', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock('../components/campaign/WarRoomMap', () => ({ default: () => <div data-testid="war-map" /> }));
const session = vi.hoisted(() => ({ state: null as null | Record<string, unknown> }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => session.state }));

const USER = { id: 1, username: 'qa', email: null, nickname: 'qa', role: 'USER' };
// serverCnt is present on purpose: a missing generation must never be inferred from it.
const withGeneration = (generation?: number) => {
  const global = { year: 200, month: 3, turnPhase: 2, serverCnt: 7, ...(generation === undefined ? {} : { generation }) };
  session.state = {
    generalId: 1, serverId: undefined, loading: false, error: null, refresh: vi.fn(),
    frontInfo: {
      global,
      general: { hasGeneral: true, generalId: 1, name: '하후돈', nationId: 1, picture: null, imageServer: 0 },
      nation: { id: 1, name: '조조', color: '#4f7fbf' },
      city: { id: 3, name: '양성현', level: 2, nationId: 1, region: 0 },
      recentRecord: {},
    },
  };
};
const Room = () => <AuthProvider initialUser={USER}><WarRoomPage /></AuthProvider>;

let server: FakeCancelServer;
let viewport: ReturnType<typeof installViewport> | null = null;
let uuid = 0;
beforeEach(() => {
  resetTab();
  onServerPath('pep');
  viewport = installViewport(1440);
  server = installCancelServer([farm(0), farm(1)]);
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  uuid = 0;
  vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => rev(800 + ++uuid) as `${string}-${string}-${string}-${string}-${string}`);
});
afterEach(() => {
  viewport?.restore();
  viewport = null;
  vi.unstubAllGlobals();
  resetTab();
});

const UNKNOWN = '취소 결과를 아직 확인하지 못했습니다';
const cancelButton = async () => within(await screen.findByTestId('reservation-band')).findByRole('button', { name: '예약 취소' });
const confirmCancel = async () => {
  fireEvent.click(await cancelButton());
  const open = await screen.findByRole('dialog', { name: '01순 예약을 취소합니다' });
  fireEvent.click(within(open).getByRole('button', { name: '예약 취소' }));
};
const status = () => screen.findByTestId('reservation-cancel-status');
const journal = (): readonly JournalRecord[] => {
  const read = readJournal();
  if (!read.ok) throw new Error('the same-tab journal is unreadable');
  return read.records;
};

test('front-info generation 7→8 hides the gen-7 UNKNOWN on the war room; back to 7 checks the same UUID with no DELETE', async () => {
  withGeneration(7);
  const view = render(<Room />);
  await confirmCancel();
  expect(await status()).toHaveTextContent(UNKNOWN);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), generation: '7', cancelState: 'unknown' })]);
  withGeneration(8);
  view.rerender(<Room />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  expect(screen.queryByRole('button', { name: '같은 취소 다시 보내기' })).toBeNull();
  expect(await cancelButton()).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(server.resultReads).toEqual([]);
  withGeneration(7);
  view.rerender(<Room />);
  expect(await status()).toHaveTextContent(UNKNOWN);
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  expect(server.deletes).toHaveLength(1);
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
});

test('a missing front-info generation is the null scope, not serverCnt: no generation is written and gen 7 hides it', async () => {
  withGeneration(undefined);
  const view = render(<Room />);
  await confirmCancel();
  expect(await status()).toHaveTextContent(UNKNOWN);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), cancelState: 'unknown' })]);
  expect(journal()[0]).not.toHaveProperty('generation');
  withGeneration(7);
  view.rerender(<Room />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  expect(server.resultReads).toEqual([]);
  expect(server.deletes).toHaveLength(1);
});
