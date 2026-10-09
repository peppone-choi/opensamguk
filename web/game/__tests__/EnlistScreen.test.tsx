import * as matchers from '@testing-library/jest-dom/matchers';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import EnlistScreen from '@/components/enlist/EnlistScreen';
import { EnlistHttpError, readEnlistOptions, readEnlistSlots, sendEnlist, type EnlistSlotsRead } from '@/lib/api/enlist-slots';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { resetEnlistReceipts } from '@/hooks/useEnlist';
vi.mock('@/lib/api/enlist-slots', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/api/enlist-slots')>(), readEnlistOptions: vi.fn(), readEnlistSlots: vi.fn(), sendEnlist: vi.fn(),
}));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));
vi.mock('@/hooks/useHelp', () => ({ useReasonHelp: () => ({ helpTopic: { id: 'input:action.enlist', title: '출사' } }) }));
vi.mock('@/components/help/HelpStrip', () => ({ InputHelpStrip: () => <div>출사 도움말</div> }));
vi.mock('@/components/campaign/CampaignLink', () => ({ default: ({ children }: { children: React.ReactNode }) => <a href="/game/pep">{children}</a> }));
expect.extend(matchers);
const options = [
  { mode: 'NATION' as const, targetId: 2, label: '조조', availability: { status: 'AVAILABLE' as const } },
  { mode: 'NATION' as const, targetId: 3, label: '원소', availability: { status: 'BLOCKED' as const, code: 'CAPACITY', reason: '해당 주공의 명망 수용량이 부족합니다.' } },
  { mode: 'GENERAL' as const, targetId: 2, label: '유비', availability: { status: 'AVAILABLE' as const } },
  { mode: 'RANDOM' as const, label: '무작위 출사', availability: { status: 'AVAILABLE' as const } },
];
const calendar = { year: 190, month: 3, turnPhase: 3 as const, turnTime: '2026-10-09 22:40:00', turnTerm: 60 };
const training = { turnIdx: 0, action: 'action.train', brief: '훈련', arg: {}, revision: '00000000-0000-4000-8000-000000000080' };
let stored: EnlistSlotsRead['slots'] = [];
const refresh = vi.fn();
function open() { return render(<EnlistScreen generalId={7} onRefresh={refresh} onHelp={vi.fn()} />); }
const slot = (no: string) => screen.getByRole('button', { name: new RegExp(`^${no}순 — `) });
const reserve = () => fireEvent.click(screen.getByRole('button', { name: '출사 예약' }));
beforeEach(() => {
  vi.clearAllMocks();
  resetEnlistReceipts();
  stored = [];
  vi.mocked(readEnlistOptions).mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options });
  vi.mocked(readEnlistSlots).mockImplementation(async () => ({ generalId: 7, slots: stored, calendar }));
  // The fresh read-back sees exactly what the write stored.
  vi.mocked(sendEnlist).mockImplementation(async (_g, option, turnIdx, guards) => {
    guards.onPost?.();
    stored = [...stored, { turnIdx, action: 'action.enlist', brief: '출사', arg: option.mode === 'RANDOM' ? { mode: 'RANDOM' } : { mode: option.mode, targetId: option.targetId },
      revision: `00000000-0000-4000-8000-${String(81 + turnIdx).padStart(12, '0')}` }];
    return { status: 'AVAILABLE', requestId: 'r-1' };
  });
  vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit => { await submit(); return { status: 'reserved', reason: '예약' }; });
});
describe('E04 approved candidate controls', () => {
  it('shows server metadata waiting, keeps blocked reason tappable, and never sends that option', async () => {
    open();
    expect(await screen.findByRole('option', { name: /원소/ })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(screen.getByRole('option', { name: /원소/ }));
    expect(screen.getAllByText('해당 주공의 명망 수용량이 부족합니다.').length).toBeGreaterThan(0);
    expect(screen.getByText('주공 위치는 서버 대기')).toBeVisible();
    expect(sendEnlist).not.toHaveBeenCalled();
  });
  it('uses source targetId rather than UI index, and refreshes only after a proven reservation', async () => {
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    const action = screen.getByRole('button', { name: '출사 예약' });
    expect(action).toHaveAttribute('data-input-id', 'action.enlist');
    fireEvent.click(action);
    expect(await screen.findByText('출사 명령이 01순에 예약되었습니다.')).toBeVisible();
    expect(vi.mocked(sendEnlist).mock.calls[0].slice(0, 3)).toEqual([7, options[0], 0]);
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it('preserves distinct nation/general choices even when server target IDs match', async () => {
    open(); await screen.findByRole('option', { name: '조조' });
    fireEvent.click(screen.getByRole('radio', { name: /장수/ }));
    fireEvent.click(await screen.findByRole('option', { name: '유비' }));
    reserve();
    await waitFor(() => expect(vi.mocked(sendEnlist).mock.calls[0].slice(0, 2)).toEqual([7, options[2]]));
  });
  it('searches real candidate labels through the shared picker', async () => {
    open(); await screen.findByRole('option', { name: '조조' });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'ㅈㅈ' } });
    expect(screen.getByRole('option', { name: '조조' })).toBeVisible();
    expect(screen.queryByRole('option', { name: /원소/ })).toBeNull();
  });
  it.each([401, 403])('HTTP %s on options or slots shows denied state without candidates or write action', async status => {
    for (const failing of ['options', 'slots'] as const) {
      resetEnlistReceipts();
      vi.mocked(readEnlistOptions).mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options });
      vi.mocked(readEnlistSlots).mockImplementation(async () => ({ generalId: 7, slots: [], calendar }));
      if (failing === 'options') vi.mocked(readEnlistOptions).mockRejectedValue(new EnlistHttpError(status));
      else vi.mocked(readEnlistSlots).mockRejectedValue(new EnlistHttpError(status));
      const view = open();
      expect(await screen.findByText(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '이 장수로 출사할 권한이 없습니다.')).toBeVisible();
      expect(screen.queryByRole('option')).toBeNull();
      expect(screen.queryByRole('button', { name: '출사 예약' })).toBeNull();
      view.unmount();
    }
    expect(sendEnlist).not.toHaveBeenCalled();
  });
  it('empty response offers the wild start without an enabled reservation', async () => {
    vi.mocked(readEnlistOptions).mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [] });
    open();
    expect(await screen.findByText('지금 출사할 주공이 없습니다')).toBeVisible();
    expect(screen.getByRole('link', { name: '재야로 시작' })).toHaveAttribute('href', '/game/pep');
    expect(screen.queryByRole('button', { name: '출사 예약' })).toBeNull();
  });
  it('pending result cannot display success or submit again, even after the parent re-renders', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit => { await submit(); return { status: 'pending', reason: '처리 지연' }; });
    const view = open(); fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    reserve();
    expect(await screen.findByText('출사는 접수됐지만 처리 결과를 아직 확인하지 못했습니다.')).toBeVisible();
    expect(screen.queryByText(/예약되었습니다/)).toBeNull();
    expect(screen.queryByText('출사 명령이 실행되었습니다.')).toBeNull();
    view.rerender(<EnlistScreen generalId={7} onRefresh={vi.fn()} onHelp={vi.fn()} />);
    reserve();
    expect(sendEnlist).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });
});

describe('K4-02 slot selection on the enlist screen', () => {
  it('shows all 12 slots with adjusted calendar labels; an occupied slot stays visible and selection starts at the first empty', async () => {
    stored = [training];
    open();
    const ring = await screen.findByTestId('turn-slots-column');
    expect(within(ring).getAllByRole('button')).toHaveLength(12);
    expect(slot('01')).toHaveAttribute('data-state', 'reserved');
    expect(slot('02')).toHaveAttribute('aria-pressed', 'true');
    expect(within(ring).getByText('190년 3월 하순 · 22:40')).toBeVisible();
    expect(within(ring).getByText('190년 4월 상순 · 23:40')).toBeVisible();
    expect(within(ring).getByText('190년 7월 상순 · 08:40')).toBeVisible();
    expect(screen.getByText('몇 번째 순에: 02순')).toBeVisible();
  });
  it.each([['01', 0], ['06', 5], ['12', 11]] as const)('slot %s posts exactly turnIdx %s', async (no, turnIdx) => {
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    fireEvent.click(slot(no));
    reserve();
    expect(await screen.findByText(`출사 명령이 ${no}순에 예약되었습니다.`)).toBeVisible();
    expect(vi.mocked(sendEnlist).mock.calls[0][2]).toBe(turnIdx);
  });
  it('an occupied slot is never overwritten from the screen', async () => {
    stored = [training];
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    fireEvent.click(slot('01'));
    expect(screen.getAllByText(/01순에는 이미 「.+」이 예약돼 있습니다\. 덮어쓰지 않습니다\./).length).toBeGreaterThan(0);
    reserve();
    expect(sendEnlist).not.toHaveBeenCalled();
  });
  it('a malformed ring is an error, not twelve empty slots, and writes nothing', async () => {
    vi.mocked(readEnlistSlots).mockRejectedValue(new Error('순 정보를 확인하지 못해 예약하지 않았습니다.'));
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    expect(await screen.findByText('12순을 불러오지 못했습니다')).toBeVisible();
    expect(screen.queryByTestId('turn-slots-column')).toBeNull();
    reserve();
    expect(sendEnlist).not.toHaveBeenCalled();
  });
  it('a reservation the read-back cannot prove is shown as unknown, never success', async () => {
    vi.mocked(sendEnlist).mockResolvedValue({ status: 'AVAILABLE', requestId: 'r-1' });
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    reserve();
    expect(await screen.findByText('01순 출사 예약 결과를 확인하지 못했습니다. 작전실 12순에서 확인해 주세요.')).toBeVisible();
    expect(screen.queryByText(/예약되었습니다/)).toBeNull();
    expect(refresh).not.toHaveBeenCalled();
  });
  it.each([401, 403])('a read-back HTTP %s after the POST shows the denial beside the unknown receipt and offers no second send', async status => {
    let readbackDenied = false;
    vi.mocked(readEnlistSlots).mockImplementation(async () => {
      if (stored.length && !readbackDenied) { readbackDenied = true; throw new EnlistHttpError(status); }
      return { generalId: 7, slots: stored, calendar };
    });
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    reserve();
    expect(await screen.findByText(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '이 장수로 출사할 권한이 없습니다.')).toBeVisible();
    expect(screen.getByText('01순 출사 예약 결과를 확인하지 못했습니다. 작전실 12순에서 확인해 주세요.')).toBeVisible();
    expect(screen.queryByRole('button', { name: '출사 예약' })).toBeNull();
    expect(sendEnlist).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });
  it('a late-occupied slot is rejected with the reason and needs explicit acknowledgement', async () => {
    vi.mocked(sendEnlist).mockResolvedValue({ status: 'BLOCKED', reason: '01순에는 이미 명령이 예약돼 있습니다. 덮어쓰지 않습니다.' });
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit => { const r = await submit(); return { status: 'rejected', reason: 'reason' in r ? r.reason : '' }; });
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    reserve();
    expect(await screen.findByText('01순에는 이미 명령이 예약돼 있습니다. 덮어쓰지 않습니다.', { selector: '[data-command-outcome="rejected"]' })).toBeVisible();
    reserve();
    expect(sendEnlist).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole('button', { name: '후보 다시 불러오기' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: '후보 다시 불러오기' })).toBeNull());
    expect(sendEnlist).toHaveBeenCalledTimes(1);
  });
});
