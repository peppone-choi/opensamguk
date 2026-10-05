import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: () => undefined }));

import { useTurnLoop } from '../hooks/useTurnLoop';

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('useTurnLoop — 읽은 값은 그 서버에 묶인다', () => {
    it('서버가 사라지면 앞 서버의 점검 · 띠를 그대로 보이지 않는다(#1325 리뷰)', async () => {
        vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
            game: { status: 'CLOSED', isUnited: 0, turnLoop: { state: 'STALLED', staleSeconds: 600 }, serverTime: '2026-10-01T12:00:00Z' },
        })))));
        const { result, rerender } = renderHook(({ id }: { id: string | undefined }) => useTurnLoop(id), { initialProps: { id: 'pep' as string | undefined } });
        await waitFor(() => expect(result.current.maintenance).toBe(true));
        expect(result.current.view?.state).toBe('STALLED');
        await act(async () => { rerender({ id: undefined }); });
        expect(result.current.maintenance).toBe(false);
        expect(result.current.view).toBeNull();
    });

    it('다른 서버로 바뀌면 새 서버를 읽기 전까지 앞 서버의 점검을 보이지 않는다', async () => {
        const answers: Array<(res: Response) => void> = [];
        vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => { answers.push(resolve); })));
        const { result, rerender } = renderHook(({ id }: { id: string }) => useTurnLoop(id), { initialProps: { id: 'pep' } });
        await act(async () => { answers[0](new Response(JSON.stringify({ game: { status: 'CLOSED', isUnited: 0 } }))); });
        await waitFor(() => expect(result.current.maintenance).toBe(true));
        await act(async () => { rerender({ id: 'che' }); });
        expect(result.current.maintenance).toBe(false);
        expect(vi.mocked(fetch)).toHaveBeenLastCalledWith('/api/server-basic-info/che', expect.anything());
    });
});
