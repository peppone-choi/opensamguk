// 참모 제안(P-K05) — 제안 읽기(K8-06)를 화면 상태로: 경로 없음 · producer 없음(NOT_SEEDED)은 서버 대기(K8-06), 셈 못 함 · 실패는 「읽을 수 없음」,
// 확인된 「제안 없음」은 빈 상태. 안내 세 줄(D59 다시 오지 않음), 채택 · 거부 단추 없음(원장 행 없음).
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({
    fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
    session: { generalId: 7 as number | null, loading: false, refresh: vi.fn() },
}));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => mocks.session }));

import ProposalsScreen, { ProposalsBody } from '@/components/proposals/ProposalsScreen';

const DIR = resolve(__dirname, '../../../app/game-api/src/test/resources/retinue/proposals');
const server = (name: string) => JSON.parse(readFileSync(resolve(DIR, name), 'utf8')) as unknown;
const NOT_FOUND = { error: { code: 'NOT_FOUND', message: 'x' } };

async function open(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
    const r = render(<ProposalsScreen />);
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });
    return r;
}

beforeEach(() => {
    mocks.fetchGame.mockReset();
    mocks.session = { generalId: 7, loading: false, refresh: vi.fn() };
});

describe('ProposalsBody', () => {
    it('서버 대기 — 이번 순 제안 · 고른 제안 모두 K8-06, 지어낸 제안 · 확신 숫자 없음', () => {
        const { container } = render(<ProposalsBody />);
        expectServerWait(container, ['K8-06', 'K8-06']);
        for (const name of ['이번 순 제안', '고른 제안']) {
            expect(screen.getByRole('region', { name }).querySelector('[data-server-wait="K8-06"] .os-status--waiting')).not.toBeNull();
        }
        expect(container.textContent).not.toMatch(/순욱|이전|허저|확신 \d|회의/);
    });

    it('알아 둘 것 — 보드 안내 세 줄, 거부 · 만료된 제안은 다시 오지 않는다(D59)', () => {
        render(<ProposalsBody />);
        const guide = screen.getByRole('region', { name: '알아 둘 것' });
        expect(within(guide).getAllByRole('listitem').map((li) => li.firstElementChild?.textContent)).toEqual([
            '채택하면 이 명령이 예약 순에 들어갑니다.',
            '고쳐서 채택은 명령 흐름에서 인자를 바꿉니다.',
            '거부 · 만료된 제안은 다시 오지 않습니다.',
        ]);
        expect(guide).toHaveTextContent('상황이 바뀌면 새 제안으로 옵니다');
    });

    it('채택 · 고쳐서 채택 · 거부 단추는 그리지 않는다 — 입력 원장 행이 없다', () => {
        for (const load of [{ state: 'waiting' }, { state: 'empty' }, { state: 'loading' }] as const) {
            const { container, unmount } = render(<ProposalsBody load={load} />);
            expect(container.querySelectorAll('button, a, [data-input-id]'), load.state).toHaveLength(0);
            unmount();
        }
    });
});

describe('ProposalsScreen — 제안 읽기(K8-06)', () => {
    it('경로가 아직 없으면(404) 서버 대기 K8-06 두 칸', async () => {
        const { container } = await open(NOT_FOUND, 404);
        expect(screen.getByText('이번 순 제안을 서버가 아직 주지 않습니다')).toBeInTheDocument();
        expectServerWait(container, ['K8-06', 'K8-06']);
        expect(mocks.fetchGame).toHaveBeenCalledWith('/api/retinue/proposals?generalId=7', expect.anything());
    });

    it('서버가 producer 없음(NOT_SEEDED)이라 답해도 서버 대기 — 「제안 없음」이 아니다', async () => {
        const { container } = await open(server('not-seeded.json'));
        expect(screen.getByText('이번 순 제안을 서버가 아직 주지 않습니다')).toBeInTheDocument();
        expect(screen.queryByText('이번 순 제안이 없습니다')).toBeNull();
        expectServerWait(container, ['K8-06', 'K8-06']);
    });

    it('셈 못 함(UNAVAILABLE) · 오류(500)는 「읽을 수 없음」 · 다시 읽기, 고른 제안은 표지 없는 안내', async () => {
        for (const [body, status] of [[server('unavailable.json'), 200], [{ error: { code: 'X', message: 'x' } }, 500]] as const) {
            const { container, unmount } = await open(body, status);
            expect(screen.getByText('이번 순 제안을 읽을 수 없습니다')).toBeInTheDocument();
            expect(within(screen.getByRole('region', { name: '고른 제안' })).getByText('제안을 읽은 뒤 여기서 고를 수 있습니다.')).toBeInTheDocument();
            expectServerWait(container, []);
            fireEvent.click(screen.getByRole('button', { name: /다시 읽기/ }));
            await act(async () => { await Promise.resolve(); });
            expect(mocks.fetchGame).toHaveBeenCalledTimes(2);
            mocks.fetchGame.mockClear();
            unmount();
        }
    });

    it('확인된 제안 없음(READY [])은 빈 상태 두 칸, 서버 대기 표지 없음', async () => {
        const { container } = await open({ status: 'READY', reason: null, now: { year: 201, month: 4, phase: 3 }, proposals: [] });
        expect(screen.getByText('이번 순 제안이 없습니다')).toBeInTheDocument();
        expect(screen.getByText('고를 제안이 없습니다')).toBeInTheDocument();
        expectServerWait(container, []);
    });

    it('장수가 없으면 읽지 않는다 — 세션을 읽는 동안은 뼈대, 다 읽었으면 「읽을 수 없음」(세션 다시 읽기)', async () => {
        mocks.session = { generalId: null, loading: true, refresh: vi.fn() };
        const { container, rerender } = await open({});
        expect(container.querySelector('.os-status--loading')).not.toBeNull();
        expectServerWait(container, []);
        mocks.session = { generalId: null, loading: false, refresh: mocks.session.refresh };
        rerender(<ProposalsScreen />);
        expect(screen.getByText('이번 순 제안을 읽을 수 없습니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /다시 읽기/ }));
        expect(mocks.session.refresh).toHaveBeenCalledTimes(1);
        expect(mocks.fetchGame).not.toHaveBeenCalled();
    });
});
