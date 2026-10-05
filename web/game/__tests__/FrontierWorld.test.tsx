// 외교 › 주변 세계 탭 — 읽기(K8-09)를 화면 상태로: 경로 없음은 서버 대기, 원천 없음 · 실패는 「자료 없음」(D29), 확인된 무접촉은 빈 상태.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({
    fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
    session: { generalId: 7 as number | null, loading: false, refresh: vi.fn() },
}));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => mocks.session }));

import { FrontierWorld } from '@/components/frontier/FrontierWorld';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/frontier');
const server = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as unknown;

async function open(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
    const r = render(<FrontierWorld />);
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });
    return r;
}

beforeEach(() => {
    mocks.fetchGame.mockReset();
    mocks.session = { generalId: 7, loading: false, refresh: vi.fn() };
});

describe('FrontierWorld', () => {
    it('경로가 아직 없으면(404) 서버 대기 K8-09', async () => {
        const { container } = await open({ error: { code: 'NOT_FOUND', message: 'x' } }, 404);
        expect(screen.getByText('주변 세계 준비 중')).toBeInTheDocument();
        expectServerWait(container, ['K8-09']);
    });

    it('서버가 원천 없음(NOT_SEEDED)이라 답하면 「자료 없음」 — 빈 상태가 아니고 다시 읽는다', async () => {
        const { container } = await open(server('not-seeded.json'));
        expect(screen.getByText('주변 세계를 읽을 수 없습니다')).toBeInTheDocument();
        expect(screen.queryByText('접경한 주변 세계가 없습니다')).toBeNull();
        expectServerWait(container, []);
        fireEvent.click(screen.getByRole('button', { name: /다시 읽기/ }));
        await act(async () => { await Promise.resolve(); });
        expect(mocks.fetchGame).toHaveBeenCalledTimes(2);
    });

    it('확인된 무접촉(READY [])은 「접경한 주변 세계가 없습니다」', async () => {
        await open({ status: 'READY', reason: null, now: { year: 201, month: 4, phase: 3 }, actors: [] });
        expect(screen.getByText('접경한 주변 세계가 없습니다')).toBeInTheDocument();
    });

    it('장수가 없으면 읽지 않는다 — 세션을 읽는 동안은 뼈대, 다 읽었으면 「자료 없음」(세션 다시 읽기)', async () => {
        mocks.session = { generalId: null, loading: true, refresh: vi.fn() };
        const { container, rerender } = await open({});
        expect(container.querySelector('.os-status--loading')).not.toBeNull();
        mocks.session = { generalId: null, loading: false, refresh: mocks.session.refresh };
        rerender(<FrontierWorld />);
        expect(screen.getByText('주변 세계를 읽을 수 없습니다')).toBeInTheDocument();
        expectServerWait(container, []);
        fireEvent.click(screen.getByRole('button', { name: /다시 읽기/ }));
        expect(mocks.session.refresh).toHaveBeenCalledTimes(1);
        expect(mocks.fetchGame).not.toHaveBeenCalled();
    });

    it('오류(500) · 월드 불명도 「자료 없음」(서버 대기 표지 없음)', async () => {
        for (const [body, status] of [[{ error: { code: 'X', message: 'x' } }, 500], [server('unavailable.json'), 200]] as const) {
            const { container, unmount } = await open(body, status);
            expect(screen.getByText('주변 세계를 읽을 수 없습니다')).toBeInTheDocument();
            expectServerWait(container, []);
            unmount();
        }
    });
});
