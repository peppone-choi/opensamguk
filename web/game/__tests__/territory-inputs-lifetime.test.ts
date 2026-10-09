// 영지 입력 수명 훅의 경계 — 문맥이 바뀌면(A→B→A 포함) 탭 · 시트 · 알림은 새 수명으로, 옛 수명의 늦은 접수는 읽기만 다시.
// 시험 대역(api 흉내)만 쓴다.
import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { useTerritoryInputs } from '../hooks/useTerritoryInputs';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, frontInfo: null }) }));
vi.mock('../lib/api', () => ({
    api: { campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const domestic = vi.mocked(api.campaignDomestic);
const mount = (key: string) => renderHook(({ k }) => useTerritoryInputs(k, 'policy'), { initialProps: { k: key } });

beforeEach(() => { vi.clearAllMocks(); });

test('문맥이 A→B→A 로 돌아와도 옛 시트 · 알림 · 탭은 되살아나지 않는다', () => {
    const view = mount('A');
    act(() => {
        view.result.current.setTab('work');
        view.result.current.setSheet({ kind: 'work', countyId: 3 });
        view.result.current.setNotice({ tone: 'ok', text: '접수' });
    });
    expect(view.result.current.tab).toBe('work');
    expect(view.result.current.sheet).toEqual({ kind: 'work', countyId: 3 });
    view.rerender({ k: 'B' });
    view.rerender({ k: 'A' });
    expect(view.result.current.sheet).toBeNull();
    expect(view.result.current.notice).toBeNull();
    expect(view.result.current.tab).toBe('policy');
});

test('같은 문맥의 접수는 알림을 쓰고 시트를 닫고 읽기를 다시 한다', async () => {
    domestic.mockResolvedValue({ status: 'AVAILABLE' } as never);
    const view = mount('A');
    act(() => view.result.current.setSheet({ kind: 'reduce', countyId: 3 }));
    await act(async () => { await view.result.current.submit('reduce', { countyId: 3 }); });
    expect(domestic).toHaveBeenCalledWith(7, 'reduce', { countyId: 3 });
    expect(view.result.current.notice).toMatchObject({ tone: 'ok', text: '성방 감축을 접수했습니다 — 다음 순 경계부터 적용합니다.' });
    expect(view.result.current.sheet).toBeNull();
    expect(view.result.current.reload).toBe(1);
    expect(view.result.current.busy).toBe(false);
});

test('옛 문맥에서 보낸 제출이 늦게 접수되면 새 문맥의 알림 · 시트에는 쓰지 않고 읽기만 다시 한다', async () => {
    let resolve!: (out: unknown) => void;
    domestic.mockReturnValue(new Promise((r) => { resolve = r; }) as never);
    const view = mount('A');
    let sent!: Promise<void>;
    act(() => { sent = view.result.current.submit('work', { countyId: 3 }); });
    expect(view.result.current.busy).toBe(true);
    view.rerender({ k: 'B' });
    act(() => view.result.current.setSheet({ kind: 'work', countyId: 4 }));
    const before = view.result.current.reload;
    await act(async () => { resolve({ status: 'AVAILABLE' }); await sent; });
    expect(view.result.current.reload).toBe(before + 1);
    expect(view.result.current.notice).toBeNull();
    expect(view.result.current.sheet).toEqual({ kind: 'work', countyId: 4 });
    expect(view.result.current.busy).toBe(false);
});

test('옛 문맥에서 보낸 제출이 실패해도 새 문맥에 오류 알림을 쓰지 않는다', async () => {
    let reject!: (e: unknown) => void;
    domestic.mockReturnValue(new Promise((_, r) => { reject = r; }) as never);
    const view = mount('A');
    let sent!: Promise<void>;
    act(() => { sent = view.result.current.submit('policy', { orderId: 'x' }); });
    view.rerender({ k: 'B' });
    await act(async () => { reject(new Error('net')); await sent; });
    expect(view.result.current.notice).toBeNull();
    expect(view.result.current.reload).toBe(0);
});
