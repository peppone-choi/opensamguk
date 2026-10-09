// Single reservation cancellation through the real CommandFlow, hook, transport and journal; fixture HTTP only, no real JWT or database.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow, { type CommandFlowProps } from '../components/command-flow/CommandFlow';
import { AuthProvider } from '../lib/auth-context';
import { announceTurnSlotsChanged } from '../lib/turn-slots';
import { blocked, commitDelete, deferred, farm, installCancelServer, onServerPath, resetTab, rev, type FakeCancelServer } from './fixtures/reservation-cancel';

vi.setConfig({ testTimeout: 20_000 });
vi.mock('next/navigation', () => ({
  usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

const USER = { id: 1, username: 'qa', email: null, nickname: 'qa', role: 'USER' };
const JOURNAL_KEY = 'opensamguk.reservationCancel';
function Flow(props: Partial<CommandFlowProps> & { userId?: number }) {
  const { userId = 1, ...rest } = props;
  return (
    <AuthProvider initialUser={{ ...USER, id: userId }}>
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

// The band and the strip exist only once the 12-slot read is READY (the loading skeleton has neither), so the first
// lookup waits for that real DOM; later synchronous lookups run after it is known to be there.
const band = () => screen.getByTestId('reservation-band');
const cancelButton = async () => within(await screen.findByTestId('reservation-band')).findByRole('button', { name: '예약 취소' });
const dialog = () => screen.findByRole('dialog', { name: '01순 예약을 취소합니다' });
const strip = () => within(screen.getByTestId('turn-slots-strip'));
const readyStrip = async () => within(await screen.findByTestId('turn-slots-strip'));
const status = () => screen.findByTestId('reservation-cancel-status');

test('a filled slot offers 예약 취소 with no command chosen; the dialog freezes the stored sentence; keep and Esc write nothing', async () => {
  const onClose = vi.fn();
  render(<Flow onClose={onClose} />);
  expect(await screen.findByTestId('slot-reserved')).toHaveTextContent('01순 지금 예약: 농지개간');
  fireEvent.click(await cancelButton());
  const open = await dialog();
  expect(open).toHaveTextContent('01순의 「농지개간」 예약을 지웁니다.');
  expect(within(open).getByRole('button', { name: '예약 취소' })).toBeInTheDocument();
  fireEvent.click(within(open).getByRole('button', { name: '그대로 두기' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  fireEvent.click(await cancelButton());
  await dialog();
  fireEvent.keyDown(window, { key: 'Escape' });
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(onClose).not.toHaveBeenCalled();
  expect(server.deletes).toEqual([]);
  expect(window.sessionStorage.getItem(JOURNAL_KEY)).toBeNull();
});

test('an empty slot has no cancellation entry', async () => {
  server.slots = [farm(1)];
  render(<Flow />);
  await (await readyStrip()).findByRole('button', { name: '02순 — 농지개간' });
  expect(screen.queryByTestId('reservation-band')).toBeNull();
  expect(screen.queryByRole('button', { name: '예약 취소' })).toBeNull();
});

test('a double press sends ONE DELETE; success keeps the slot, command and draft and leaves other slots alone', async () => {
  const onLocationChange = vi.fn();
  render(<Flow initialInputId="action.farm" onLocationChange={onLocationChange} />);
  fireEvent.click(await cancelButton());
  const confirm = within(await dialog()).getByRole('button', { name: '예약 취소' });
  act(() => { fireEvent.click(confirm); fireEvent.click(confirm); });
  expect(await screen.findByText('이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.')).toBeInTheDocument();
  expect(server.deletes).toHaveLength(1);
  expect(server.deletes[0]).toMatchObject({ key: rev(801), body: undefined, contentType: null });
  expect(Object.fromEntries(server.deletes[0].url.searchParams)).toEqual({ generalId: '1', turnIdx: '0', revision: rev(0), server: 'pep' });
  await strip().findByRole('button', { name: '01순 — 빈 순' });
  expect(strip().getByRole('button', { name: '01순 — 빈 순' })).toHaveAttribute('aria-pressed', 'true');
  expect(strip().getByRole('button', { name: '02순 — 농지개간' })).toBeInTheDocument();
  expect(server.slots).toEqual([farm(1)]);
  expect(screen.queryByTestId('slot-reserved')).toBeNull();
  expect(onLocationChange).toHaveBeenLastCalledWith({ inputId: 'action.farm', slot: 0 });
  expect(screen.getByTestId('command-flow').querySelector('[data-input-status]')).toHaveTextContent('01순에 예약');
  expect(window.sessionStorage.getItem(JOURNAL_KEY)).toBeNull();
});

test('the first send re-reads the list; a changed revision sends no DELETE and needs a new choice', async () => {
  render(<Flow />);
  fireEvent.click(await cancelButton());
  const confirm = within(await dialog()).getByRole('button', { name: '예약 취소' });
  server.slots = [farm(0, rev(50)), farm(1)];
  fireEvent.click(confirm);
  expect(await screen.findByText(/예약이 그새 바뀌어 취소를 보내지 않았습니다/)).toBeInTheDocument();
  expect(server.deletes).toEqual([]);
  expect(globalThis.crypto.randomUUID).not.toHaveBeenCalled();
  // The refreshed row is a new target: only a new press and confirmation can cancel it.
  fireEvent.click(await cancelButton());
  fireEvent.click(within(await dialog()).getByRole('button', { name: '예약 취소' }));
  await screen.findByText('이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.');
  expect(server.deletes.map(d => d.url.searchParams.get('revision'))).toEqual([rev(50)]);
});

test('a strip refresh that brings another revision closes the open confirmation', async () => {
  render(<Flow />);
  fireEvent.click(await cancelButton());
  await dialog();
  server.slots = [farm(0, rev(51)), farm(1)];
  await act(async () => announceTurnSlotsChanged());
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(server.deletes).toEqual([]);
});

test('CAS mismatch at the server reloads and never cancels the replacement by itself', async () => {
  server.onDelete = (call) => { server.slots = [farm(0, rev(52)), farm(1)]; return commitDelete(server, call); };
  render(<Flow />);
  fireEvent.click(await cancelButton());
  fireEvent.click(within(await dialog()).getByRole('button', { name: '예약 취소' }));
  expect(await screen.findByText(/예약이 그새 바뀌어 취소하지 않았습니다/)).toBeInTheDocument();
  await waitFor(() => expect(server.ringReads).toBeGreaterThanOrEqual(3));
  expect(server.deletes).toHaveLength(1);
  expect(server.slots).toEqual([farm(0, rev(52)), farm(1)]);
});

test('WORLD_EXECUTING: manual retry of the same intent only; other slots and close stay available', async () => {
  server.onDelete = () => blocked('WORLD_EXECUTING', 409, true);
  const onClose = vi.fn();
  render(<Flow onClose={onClose} />);
  fireEvent.click(await cancelButton());
  fireEvent.click(within(await dialog()).getByRole('button', { name: '예약 취소' }));
  expect(await status()).toHaveTextContent('턴을 처리하는 중이라 취소를 받지 않았습니다');
  expect(within(band()).queryByRole('button', { name: '예약 취소' })).toBeNull();
  expect(screen.getByRole('button', { name: '닫기' })).not.toHaveAttribute('aria-disabled');
  await new Promise(r => setTimeout(r, 50));
  expect(server.deletes).toHaveLength(1);
  // Another slot, then back: the stored intent comes back without a DELETE.
  fireEvent.click(strip().getByRole('button', { name: '02순 — 농지개간' }));
  expect(await cancelButton()).toBeInTheDocument();
  fireEvent.click(strip().getByRole('button', { name: '01순 — 농지개간' }));
  expect(await status()).toHaveTextContent('턴을 처리하는 중이라');
  expect(server.deletes).toHaveLength(1);
  server.onDelete = null;
  // The automatic result check on return finishes first (PENDING keeps the intent).
  const retry = await waitFor(async () => {
    const button = within(await status()).getByRole('button', { name: '같은 취소 다시 보내기' });
    expect(button).not.toHaveAttribute('aria-disabled');
    return button;
  });
  fireEvent.click(retry);
  await screen.findByText('이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.');
  expect(server.deletes.map(d => d.key)).toEqual([rev(801), rev(801)]);
  expect(globalThis.crypto.randomUUID).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole('button', { name: '닫기' }));
  expect(onClose).toHaveBeenCalled();
});

test('a normal reservation and a cancellation of the same slot never leave together', async () => {
  const held = deferred<void>();
  server.onDelete = async (call) => { await held.promise; return commitDelete(server, call); };
  render(<Flow initialInputId="action.farm" />);
  fireEvent.click(await cancelButton());
  fireEvent.click(within(await dialog()).getByRole('button', { name: '예약 취소' }));
  await waitFor(() => expect(server.deletes).toHaveLength(1));
  expect(await status()).toHaveTextContent('예약 취소를 보내는 중입니다');
  fireEvent.click(screen.getByTestId('command-flow').querySelector('[data-input-status]') as HTMLElement);
  fireEvent.click(await screen.findByRole('button', { name: '바꾸기' }));
  expect(await screen.findByText('01순 예약 취소를 보내는 중입니다 — 끝난 뒤 다시 눌러 주세요.')).toBeInTheDocument();
  expect(server.posts).toEqual([]);
  await act(async () => held.resolve());
  await screen.findByText('이 예약의 취소를 확인했습니다 — 01순은 지금 비어 있습니다.');
  expect(server.posts).toEqual([]);
});

test('a normal reservation in flight blocks a cancellation of that slot (no DELETE)', async () => {
  const held = deferred<Response>();
  server.onPost = () => held.promise;
  render(<Flow initialInputId="action.farm" />);
  await cancelButton();
  fireEvent.click(screen.getByTestId('command-flow').querySelector('[data-input-status]') as HTMLElement);
  fireEvent.click(await screen.findByRole('button', { name: '바꾸기' }));
  await waitFor(() => expect(server.posts).toHaveLength(1));
  fireEvent.click(await cancelButton());
  fireEvent.click(within(await dialog()).getByRole('button', { name: '예약 취소' }));
  expect(await screen.findByText('이 순에 다른 요청을 보내는 중입니다 — 끝난 뒤 다시 눌러 주세요.')).toBeInTheDocument();
  expect(server.deletes).toEqual([]);
  await act(async () => held.resolve(new Response(JSON.stringify({ status: 'AVAILABLE', requestId: rev(9001), turnIdx: 0 }), { status: 202 })));
  // The reservation finishes and releases the slot before the next test.
  expect(await screen.findByText('「농지개간」 — 01순에 예약했습니다.', {}, { timeout: 9000 })).toBeInTheDocument();
});
