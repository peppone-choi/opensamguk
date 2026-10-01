import * as matchers from '@testing-library/jest-dom/matchers';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import EnlistScreen from '@/components/enlist/EnlistScreen';
import { EnlistHttpError, readEnlistOptions, sendEnlist } from '@/components/enlist/enlist-api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
vi.mock('@/components/enlist/enlist-api', async importOriginal => ({ ...await importOriginal<typeof import('@/components/enlist/enlist-api')>(), readEnlistOptions: vi.fn(), sendEnlist: vi.fn() }));
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
const refresh = vi.fn();
function open() { return render(<EnlistScreen generalId={7} onRefresh={refresh} onHelp={vi.fn()} />); }
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(readEnlistOptions).mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options });
  vi.mocked(sendEnlist).mockResolvedValue({ status: 'AVAILABLE', requestId: 'r-1' });
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
  it('uses source targetId rather than UI index, and refreshes only after reservation result', async () => {
    open();
    fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    const action = screen.getByRole('button', { name: '출사 예약' });
    expect(action).toHaveAttribute('data-input-id', 'action.enlist');
    fireEvent.click(action);
    expect(await screen.findByText('출사 명령이 예약되었습니다.')).toBeVisible();
    expect(sendEnlist).toHaveBeenCalledWith(7, options[0]);
    expect(refresh).toHaveBeenCalledTimes(1);
  });
  it('preserves distinct nation/general choices even when server target IDs match', async () => {
    open(); await screen.findByRole('option', { name: '조조' });
    fireEvent.click(screen.getByRole('radio', { name: /장수/ }));
    fireEvent.click(await screen.findByRole('option', { name: '유비' }));
    fireEvent.click(screen.getByRole('button', { name: '출사 예약' }));
    await waitFor(() => expect(sendEnlist).toHaveBeenCalledWith(7, options[2]));
  });
  it('searches real candidate labels through the shared picker', async () => {
    open(); await screen.findByRole('option', { name: '조조' });
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'ㅈㅈ' } });
    expect(screen.getByRole('option', { name: '조조' })).toBeVisible();
    expect(screen.queryByRole('option', { name: /원소/ })).toBeNull();
  });
  it.each([401, 403])('HTTP %s shows denied state without candidates or write action', async status => {
    vi.mocked(readEnlistOptions).mockRejectedValue(new EnlistHttpError(status));
    open();
    expect(await screen.findByText(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '이 장수로 출사할 권한이 없습니다.')).toBeVisible();
    expect(screen.queryByRole('option')).toBeNull();
    expect(screen.queryByRole('button', { name: '출사 예약' })).toBeNull();
    expect(sendEnlist).not.toHaveBeenCalled();
  });
  it('empty response offers the wild start without an enabled reservation', async () => {
    vi.mocked(readEnlistOptions).mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [] });
    open();
    expect(await screen.findByText('지금 출사할 주공이 없습니다')).toBeVisible();
    expect(screen.getByRole('link', { name: '재야로 시작' })).toHaveAttribute('href', '/game/pep');
    expect(screen.queryByRole('button', { name: '출사 예약' })).toBeNull();
  });
  it('pending result cannot display success or submit again', async () => {
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async submit => { await submit(); return { status: 'pending', reason: '처리 지연' }; });
    open(); fireEvent.click(await screen.findByRole('option', { name: '조조' }));
    fireEvent.click(screen.getByRole('button', { name: '출사 예약' }));
    expect(await screen.findByText('출사는 접수됐지만 처리 결과를 아직 확인하지 못했습니다.')).toBeVisible();
    expect(screen.queryByText('출사 명령이 예약되었습니다.')).toBeNull();
    expect(screen.queryByText('출사 명령이 실행되었습니다.')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '출사 예약' }));
    expect(sendEnlist).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });
});
