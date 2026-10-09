// Cancellation recovery: unknown outcomes, remounts, scope changes, late responses and current readback through fixture HTTP.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow, { type CommandFlowProps } from '../components/command-flow/CommandFlow';
import { AuthProvider } from '../lib/auth-context';
import { readJournal, type JournalRecord } from '../lib/command-flow/reservation-cancel-journal';
import { commitDelete, deferred, farm, installCancelServer, json, onServerPath, resetTab, rev, type FakeCancelServer } from './fixtures/reservation-cancel';

vi.setConfig({ testTimeout: 20_000 });
vi.mock('next/navigation', () => ({
  usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

const USER = { id: 1, username: 'qa', email: null, nickname: 'qa', role: 'USER' };
function Flow({ userId = 1, ...rest }: Partial<CommandFlowProps> & { userId?: number }) {
  return (
    <AuthProvider key={userId} initialUser={{ ...USER, id: userId }}>
      <CommandFlow generalId={1} initialSlot={0} onClose={vi.fn()} {...rest} />
    </AuthProvider>
  );
}

let server: FakeCancelServer;
let uuid = 0;
beforeEach(() => {
  resetTab();
  onServerPath('pep');
  server = installCancelServer([farm(0), farm(1)]);
  uuid = 0;
  vi.spyOn(globalThis.crypto, 'randomUUID').mockImplementation(() => rev(800 + ++uuid) as `${string}-${string}-${string}-${string}-${string}`);
});
afterEach(() => {
  vi.unstubAllGlobals();
  resetTab();
});

// The band exists only once the 12-slot read is READY, so the first lookup waits for that real DOM.
const band = () => screen.getByTestId('reservation-band');
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
const DONE = '이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.';

test('lost commit response: UNKNOWN keeps the UUID, offers no new cancel, and the result check recovers', async () => {
  server.onDelete = (call) => { commitDelete(server, call); throw new TypeError('connection reset'); };
  render(<Flow />);
  await confirmCancel();
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  expect(within(band()).queryByRole('button', { name: '예약 취소' })).toBeNull();
  expect(screen.getByRole('button', { name: '닫기' })).not.toHaveAttribute('aria-disabled');
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), cancelState: 'unknown' })]);
  server.onDelete = null;
  fireEvent.click(within(await status()).getByRole('button', { name: '취소 결과 확인' }));
  expect(await screen.findByText(DONE)).toBeInTheDocument();
  expect(server.resultReads).toEqual([rev(801)]);
  expect(server.deletes).toHaveLength(1);
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
  expect(journal()).toEqual([]);
});

test('leaving during the send and coming back (unmount → remount) recovers by result GET with no new DELETE', async () => {
  const held = deferred<void>();
  server.onDelete = async (call) => { await held.promise; return commitDelete(server, call); };
  const first = render(<Flow />);
  await confirmCancel();
  await waitFor(() => expect(server.deletes).toHaveLength(1));
  first.unmount();
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), cancelState: 'sending' })]);
  render(<Flow />);
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  // The automatic check has answered PENDING (its busy flag is clear) before the old send lands.
  await waitFor(async () => expect(within(await status()).getByRole('button', { name: '취소 결과 확인' })).not.toHaveAttribute('aria-disabled'));
  // The old send lands now: it records its own receipt and wakes this screen's own result GET.
  await act(async () => held.resolve());
  expect(await screen.findByText(DONE)).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
});

test('actor A→B→A gets a new epoch; the late DELETE of the first A never changes B or the new A directly', async () => {
  const held = deferred<void>();
  server.onDelete = async (call) => { await held.promise; return commitDelete(server, call); };
  const view = render(<Flow generalId={1} />);
  await confirmCancel();
  await waitFor(() => expect(server.deletes).toHaveLength(1));
  view.rerender(<Flow generalId={2} />);
  await screen.findByTestId('slot-reserved');
  expect(screen.queryByTestId('reservation-cancel-status')).toBeNull();
  await act(async () => held.resolve());
  expect(screen.queryByTestId('reservation-cancel-status')).toBeNull();
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), cancelState: 'confirmed' })]);
  view.rerender(<Flow generalId={1} />);
  // Restored from its own record: a fresh list read, no DELETE.
  expect(await screen.findByText(DONE)).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
});

test('another account never sees or resumes the previous account\'s intent; the previous record is kept', async () => {
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  const view = render(<Flow userId={1} />);
  await confirmCancel();
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  view.rerender(<Flow userId={2} />);
  expect(await cancelButton()).toBeInTheDocument();
  expect(screen.queryByTestId('reservation-cancel-status')).toBeNull();
  expect(journal()).toEqual([expect.objectContaining({ accountId: 1, requestId: rev(801) })]);
  expect(server.resultReads).toEqual([]);
  view.rerender(<Flow userId={1} />);
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  expect(server.deletes).toHaveLength(1);
});

test('server A→B→A: the other world never shows this intent; coming back restores it from the original server', async () => {
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  const view = render(<Flow />);
  await confirmCancel();
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  onServerPath('che');
  view.rerender(<Flow />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  expect(server.resultReads).toEqual([]);
  onServerPath('pep');
  view.rerender(<Flow />);
  expect(await status()).toHaveTextContent('취소 결과를 아직 확인하지 못했습니다');
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  const resultCall = server.calls.find(call => call.url.pathname.endsWith(`/command/result/${rev(801)}`));
  expect(resultCall?.url.searchParams.get('server')).toBe('pep');
  expect(server.deletes).toHaveLength(1);
});

test('a refresh key change while the dialog is open closes it and writes nothing', async () => {
  const view = render(<Flow refreshKey={0} />);
  fireEvent.click(await cancelButton());
  await screen.findByRole('dialog', { name: '01순 예약을 취소합니다' });
  view.rerender(<Flow refreshKey={1} />);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(server.deletes).toEqual([]);
});

test('fresh-list failure keeps the receipt and retries the GET only; a replacement found later stays', async () => {
  let reads = 0;
  server.onRing = () => {
    reads += 1;
    // initial strip, preflight, then the post-receipt read fails once
    if (reads === 3) return json({}, 503);
    return json({ result: true, generalId: 1, slots: server.slots });
  };
  render(<Flow />);
  await confirmCancel();
  expect(await screen.findByText('이 예약의 취소를 확인했습니다 — 현재 목록을 확인하지 못했습니다')).toBeInTheDocument();
  expect(journal()).toEqual([expect.objectContaining({ cancelState: 'confirmed' })]);
  // Someone reserved the slot again meanwhile — it is shown, never cancelled.
  server.slots = [farm(0, rev(70)), farm(1)];
  fireEvent.click(within(await status()).getByRole('button', { name: '현재 목록 다시 읽기' }));
  expect(await screen.findByText(/01순에는 그 뒤 들어온 다른 예약이 있습니다/)).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(server.slots).toEqual([farm(0, rev(70)), farm(1)]);
  expect(journal()).toEqual([]);
  expect(await cancelButton()).toBeInTheDocument();
});

test('a corrupt same-tab journal blocks a new cancellation with a reason and is left as it is', async () => {
  window.sessionStorage.setItem('opensamguk.reservationCancel', '{broken');
  render(<Flow />);
  await screen.findByTestId('slot-reserved');
  await waitFor(() => expect(within(band()).getByRole('button', { name: '예약 취소' })).toHaveAttribute('aria-disabled', 'true'));
  expect(screen.getByText(/취소 기록을 남길 수 없어/)).toBeInTheDocument();
  fireEvent.click(within(band()).getByRole('button', { name: '예약 취소' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(window.sessionStorage.getItem('opensamguk.reservationCancel')).toBe('{broken');
  expect(server.deletes).toEqual([]);
});

const UNKNOWN = '취소 결과를 아직 확인하지 못했습니다';

test('generation 7→8 with the same account, server, actor and slot hides the gen-7 UNKNOWN; returning to 7 checks its UUID only', async () => {
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  const view = render(<Flow generation={7} />);
  await confirmCancel();
  expect(await status()).toHaveTextContent(UNKNOWN);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), generation: '7', cancelState: 'unknown' })]);
  // The identical row under generation 8 is a fresh scope: no old status, no manual resend, no new DELETE.
  view.rerender(<Flow generation={8} />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  expect(screen.queryByRole('button', { name: '같은 취소 다시 보내기' })).toBeNull();
  expect(await cancelButton()).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(server.resultReads).toEqual([]);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), generation: '7', cancelState: 'unknown' })]);
  view.rerender(<Flow generation={7} />);
  expect(await status()).toHaveTextContent(UNKNOWN);
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  expect(server.deletes).toHaveLength(1);
  expect(server.deletes[0].key).toBe(rev(801));
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
});

test('a generation change while the confirmation is open closes it and sends nothing', async () => {
  const view = render(<Flow generation={7} />);
  fireEvent.click(await cancelButton());
  await screen.findByRole('dialog', { name: '01순 예약을 취소합니다' });
  view.rerender(<Flow generation={8} />);
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(server.deletes).toEqual([]);
  expect(journal()).toEqual([]);
});

test('a late gen-7 DELETE while gen 8 is mounted records its own receipt but never changes the gen-8 screen', async () => {
  const held = deferred<void>();
  server.onDelete = async (call) => { await held.promise; return commitDelete(server, call); };
  const view = render(<Flow generation={7} />);
  await confirmCancel();
  await waitFor(() => expect(server.deletes).toHaveLength(1));
  view.rerender(<Flow generation={8} />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  await act(async () => held.resolve());
  expect(screen.queryByTestId('reservation-cancel-status')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), generation: '7', cancelState: 'confirmed' })]);
  expect(server.deletes).toHaveLength(1);
  view.rerender(<Flow generation={7} />);
  // Restored from its own gen-7 record, no DELETE.
  expect(await screen.findByText(DONE)).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
});

test('generation 7 → absent or malformed is the null scope: the gen-7 record is neither shown nor rewritten', async () => {
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  const view = render(<Flow generation={7} />);
  await confirmCancel();
  expect(await status()).toHaveTextContent(UNKNOWN);
  for (const generation of [null, undefined, Number.NaN, Number.POSITIVE_INFINITY, 7.5, -7, Number.MAX_SAFE_INTEGER + 1]) {
    view.rerender(<Flow generation={generation} />);
    await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  }
  expect(server.resultReads).toEqual([]);
  expect(server.deletes).toHaveLength(1);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), generation: '7', cancelState: 'unknown' })]);
  view.rerender(<Flow generation={7} />);
  expect(await status()).toHaveTextContent(UNKNOWN);
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  expect(server.deletes).toHaveLength(1);
});

test('an intent made without a generation stays without one and is hidden under generation 7', async () => {
  server.onDelete = () => new Response('Gateway Timeout', { status: 504 });
  const view = render(<Flow />);
  await confirmCancel();
  expect(await status()).toHaveTextContent(UNKNOWN);
  expect(journal()).toEqual([expect.objectContaining({ requestId: rev(801), cancelState: 'unknown' })]);
  expect(journal()[0]).not.toHaveProperty('generation');
  view.rerender(<Flow generation={7} />);
  await waitFor(() => expect(screen.queryByTestId('reservation-cancel-status')).toBeNull());
  expect(server.resultReads).toEqual([]);
  view.rerender(<Flow />);
  expect(await status()).toHaveTextContent(UNKNOWN);
  await waitFor(() => expect(server.resultReads).toEqual([rev(801)]));
  expect(journal()[0]).not.toHaveProperty('generation');
  expect(server.deletes).toHaveLength(1);
});
