// 관직 · 봉신 › 지방 관직(K8-03 미리 짓기, D124 A) — 서버가 경로를 내지 않으면(404) 서버 대기, 200 이 오면 값이 저절로 나온다.
// 값은 계약 고정 응답(docs/development/fixtures/court-local-offices.json)만 쓴다 — 제품 화면에 가짜 값은 없다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';

const mocks = vi.hoisted(() => ({
    fetchGame: vi.fn<(path: string, init?: RequestInit) => Promise<Response>>(),
    mapPreview: vi.fn<(signal?: AbortSignal) => Promise<unknown>>(),
}));
vi.mock('@/lib/api', () => ({ fetchGame: mocks.fetchGame, api: { mapPreview: mocks.mapPreview } }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));

import LocalOfficesTab from '@/components/offices/LocalOfficesTab';

const FIXTURE = resolve(__dirname, '../../../docs/development/fixtures/court-local-offices.json');
const fixture = () => JSON.parse(readFileSync(FIXTURE, 'utf8')) as Record<string, unknown>;
const PREVIEW = {
    cities: [
        { id: 100, name: '장안', displayName: '경조윤 장안현', commanderyName: '경조윤', regionName: '사례', level: 1, nationId: 1, x: 0, y: 0 },
        { id: 200, name: '초', displayName: '패국 초현', commanderyName: '패국', regionName: '예주', level: 1, nationId: 1, x: 0, y: 0 },
    ],
    nations: [],
};

async function open(body: unknown, status = 200) {
    mocks.fetchGame.mockImplementation(() => Promise.resolve(new Response(JSON.stringify(body), { status })));
    const r = render(<LocalOfficesTab />);
    await act(async () => { await Promise.resolve(); });
    await act(async () => { await Promise.resolve(); });
    return r;
}

beforeEach(() => {
    mocks.fetchGame.mockReset();
    mocks.mapPreview.mockReset();
    mocks.mapPreview.mockResolvedValue(PREVIEW);
});

describe('LocalOfficesTab — 서버 대기 → 값', () => {
    it('서버가 아직 경로를 내지 않으면(404) 지금처럼 서버 대기 — 관할 · 고른 관할 K8-03, 받은 임명 제안 K8-02', async () => {
        const { container } = await open({ error: { code: 'NOT_FOUND', message: 'x' } }, 404);
        expect(await screen.findByText('관직 정보가 아직 없습니다')).toBeInTheDocument();
        expectServerWait(container, ['K8-03', 'K8-02']);
        expect(screen.queryByRole('alert')).toBeNull(); // 오류로 그리지 않는다
        expect(mocks.mapPreview).not.toHaveBeenCalled();
    });

    it('200 이 오면 값이 저절로 나온다 — 관할 표 · 고른 관할 · 임명할 수 있는 자리 · 보낸 임명 제안', async () => {
        const { container } = await open(fixture());
        const table = screen.getByRole('list', { name: '지방 관직' });
        const rows = within(table).getAllByRole('button');
        expect(rows.map((b) => b.textContent)).toEqual([
            expect.stringMatching(/경조윤.*치소 장안현.*태수.*장수 예시.*실권 있음.*2곳/),
            expect.stringMatching(/예주.*치소 초현.*자사.*장수 예시 2.*명목.*0곳/),
        ]);
        expect(rows[0]).toHaveAttribute('aria-pressed', 'true');
        // 관할 표의 서버 대기는 사라지고 값이 대기 칸 밖에 보인다.
        const officesPanel = screen.getByRole('region', { name: '관할과 앉은 사람' });
        expectServerWaitGone(officesPanel, ['K8-03'], { value: '장수 예시' });
        // 받은 임명 제안은 K8-02 몫이라 그대로 대기.
        expectServerWait(screen.getByRole('region', { name: '받은 임명 제안' }), ['K8-02']);
        // 임명할 수 있는 자리 — 막힌 이유를 서버 글자 그대로. 입력 단추는 없다(원장 행 없음).
        const options = screen.getByRole('region', { name: '임명할 수 있는 자리' });
        expect(options).toHaveTextContent('경조윤 태수');
        expect(options).toHaveTextContent('이미 해당 관할에 재임자가 있습니다.');
        expect(within(options).queryByRole('button')).toBeNull();
        // 보낸 임명 제안 — 응답에 없는 후보 · 관할 이름은 K8-03 대기(짓지 않는다).
        const offers = screen.getByRole('region', { name: '보낸 임명 제안' });
        expect(offers).toHaveTextContent('기한 196년 1월 하순');
        expectServerWait(offers, ['K8-03']);
        expectServerWait(container, ['K8-02', 'K8-03']);
    });

    it('명목 자리를 고르면 경고와 실효 판정 — 부족한 근거만 부족함', async () => {
        await open(fixture());
        fireEvent.click(screen.getByRole('button', { name: /예주/ }));
        const detail = screen.getByRole('region', { name: '고른 관할' });
        expect(within(detail).getByRole('note')).toHaveTextContent('명목입니다');
        const evidence = within(detail).getByRole('list', { name: '실효 판정' });
        const items = within(evidence).getAllByRole('listitem');
        expect(items).toHaveLength(8);
        expect(items.filter((li) => li.textContent?.includes('부족함')).map((li) => li.textContent)).toEqual(['치소 현을 가졌다 — 부족함']);
        expect(within(detail).queryByRole('button')).toBeNull(); // 파면 입력은 원장 행이 없다
    });

    it('오류(500)는 다시 시도, 셈 못 함(UNAVAILABLE)은 「관직이 없다는 뜻이 아님」', async () => {
        await open({ error: { code: 'X', message: 'x' } }, 500);
        expect(screen.getByRole('alert')).toHaveTextContent('관직 정보를 지금 읽을 수 없습니다');
        mocks.fetchGame.mockReset();
        await open({ status: 'UNAVAILABLE', now: null, localOffices: [], appointmentOptions: [], pendingOffers: [] });
        expect(screen.getByText('관직 정보를 셈하지 못했습니다')).toBeInTheDocument();
        expect(screen.queryByRole('region', { name: '임명할 수 있는 자리' })).toBeNull();
    });
});
