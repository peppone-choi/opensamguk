// 세력(P-K10) 화면 — 세력 요약 · 현 목록은 서버 값 그대로(null 은 「—」), 정체성 · 제도 · 편제 전통은 서버 대기 A.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CountyDirectory, NationSummary } from '@/lib/directory-reads';

const mocks = vi.hoisted(() => ({
    summary: vi.fn(),
    counties: vi.fn(),
}));
vi.mock('@/lib/api', () => ({ api: { nationSummary: mocks.summary, counties: mocks.counties } }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: () => undefined }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/${slug}`} {...rest}>{children}</a>,
}));

import RealmScreen from '@/components/realm/RealmScreen';

const SUMMARY: NationSummary = {
    status: 'READY',
    nation: { id: 1, name: '조조', color: '#4f7fbf' },
    lord: { generalId: 1, name: '조조', portrait: { picture: null, imageServer: 0 } },
    capitalCityId: 11,
    countyCount: 3,
    retinueCount: 12,
    stockTotal: { money: 12000, grain: 34000, iron: 10, timber: 20, horses: 30 },
    population: 123456,
    troops: { city: 5000, bugok: 1200 },
};
const COUNTIES: CountyDirectory = {
    status: 'READY', scope: 'NATION', commandery: null, period: 'GAME_MONTH', basis: 'CURRENT_STATE_FORECAST', stamp: null,
    counties: [
        { cityId: 12, name: '양적현', commanderyId: 'c1', visibility: 'FULL', income: { money: 300, grain: 900 } },
        { cityId: 11, name: '허현', commanderyId: 'c1', visibility: 'FULL', income: { money: 500, grain: 1500 } },
        { cityId: 13, name: '언릉현', commanderyId: 'c1', visibility: 'FOG', income: null },
    ],
};

async function settle() {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

beforeEach(() => {
    mocks.summary.mockReset();
    mocks.counties.mockReset();
    mocks.summary.mockResolvedValue(SUMMARY);
    mocks.counties.mockResolvedValue(COUNTIES);
});

describe('RealmScreen', () => {
    it('세력 요약 — 이름 · 군주 · 수도(현 목록에서) · 다섯 값, 창고는 「창고 합」, 창고망으로 잇는다', async () => {
        render(<RealmScreen />);
        await settle();
        const band = screen.getByRole('region', { name: '세력 요약' });
        expect(within(band).getByRole('heading', { name: '조조' })).toBeInTheDocument();
        expect(within(band).getByText('군주 조조 · 수도 허현')).toBeInTheDocument();
        for (const [k, v] of [['다스리는 현', '3'], ['소속 인물', '12'], ['호구', '123,456'], ['병력', '성 5,000 · 부곡 1,200'], ['창고 합', '금 12,000 · 쌀 34,000']]) {
            expect(within(band).getByText(k).nextElementSibling).toHaveTextContent(v);
        }
        expect(within(band).queryByText(/수도 창고/)).toBeNull();
        expect(within(band).getByRole('link', { name: '창고망 보기' })).toHaveAttribute('href', '/game/territory/supply');
        expect(mocks.summary).toHaveBeenCalledWith(7, expect.anything());
        expect(mocks.counties).toHaveBeenCalledWith(7, 'NATION', null, expect.anything());
    });

    it('현 목록 — 수도가 맨 위 · 수도 표시, 볼 수 없는 세입은 「—」(0 으로 바꾸지 않는다)', async () => {
        render(<RealmScreen />);
        await settle();
        const rows = within(screen.getByRole('list', { name: '다스리는 현' })).getAllByRole('listitem');
        expect(rows.map((r) => r.textContent)).toEqual(['허현수도금 500 · 쌀 1,500', '양적현금 300 · 쌀 900', '언릉현—']);
    });

    it('null 값은 「—」, 수도가 없으면 「없음」', async () => {
        mocks.summary.mockResolvedValue({ ...SUMMARY, capitalCityId: null, population: null, troops: null, stockTotal: null, retinueCount: null });
        render(<RealmScreen />);
        await settle();
        const band = screen.getByRole('region', { name: '세력 요약' });
        expect(within(band).getByText('군주 조조 · 수도 없음')).toBeInTheDocument();
        for (const k of ['소속 인물', '호구', '병력', '창고 합']) expect(within(band).getByText(k).nextElementSibling).toHaveTextContent('—');
    });

    it('일부만 읽힘(PARTIAL) — 받은 값은 그대로, 알림 한 줄', async () => {
        mocks.summary.mockResolvedValue({ ...SUMMARY, status: 'PARTIAL', stockTotal: null });
        render(<RealmScreen />);
        await settle();
        expect(screen.getByRole('note')).toHaveTextContent('일부 값을 읽지 못했습니다');
        expect(within(screen.getByRole('region', { name: '세력 요약' })).getByText('다스리는 현').nextElementSibling).toHaveTextContent('3');
    });

    it('재야(NO_NATION) — 빈 상태, 탭 없음', async () => {
        mocks.summary.mockResolvedValue({ status: 'NO_NATION', nation: null, lord: null, capitalCityId: null, countyCount: null, retinueCount: null, stockTotal: null, population: null, troops: null });
        render(<RealmScreen />);
        await settle();
        expect(screen.getByText('소속 세력이 없습니다')).toBeInTheDocument();
        expect(screen.queryByRole('tablist')).toBeNull();
    });

    it('요약 읽기 실패 — 오류 모양, 다시 시도하면 다시 읽는다', async () => {
        mocks.summary.mockRejectedValueOnce(new Error('503'));
        render(<RealmScreen />);
        await settle();
        expect(screen.getByText('세력 정보를 지금 읽을 수 없습니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        await settle();
        expect(screen.getByRole('region', { name: '세력 요약' })).toBeInTheDocument();
        expect(mocks.summary).toHaveBeenCalledTimes(2);
    });

    it('서버가 읽을 수 없다고 하면(UNAVAILABLE) 빈 것이 아니라 오류', async () => {
        mocks.summary.mockResolvedValue({ ...SUMMARY, status: 'UNAVAILABLE', nation: null });
        const { container } = render(<RealmScreen />);
        await settle();
        expect(container.querySelector('.os-status--error')).not.toBeNull();
        expect(container.textContent).toContain('저장된 값을 읽을 수 없습니다');
    });

    it('현 목록만 실패 — 요약은 그대로, 수도는 「확인 중」, 목록 칸은 오류', async () => {
        mocks.counties.mockRejectedValue(new Error('503'));
        render(<RealmScreen />);
        await settle();
        expect(screen.getByText('군주 조조 · 수도 확인 중')).toBeInTheDocument();
        expect(screen.getByText('현 목록을 지금 읽을 수 없습니다')).toBeInTheDocument();
    });

    it('정체성 · 제도 · 편제 전통 — 서버 대기 A(원장 목록을 지어 그리지 않는다)', async () => {
        const { container } = render(<RealmScreen />);
        await settle();
        for (const [tab, title] of [['정체성', '정체성은 아직 없습니다'], ['제도', '제도 확산이 아직 없습니다'], ['편제 전통', '편제 전통은 아직 없습니다']]) {
            fireEvent.click(screen.getByRole('tab', { name: tab }));
            expect(screen.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
            const panel = screen.getByRole('tabpanel', { name: tab });
            expect(within(panel).getByText(title)).toBeInTheDocument();
            expect(panel.querySelector('.os-status--waiting')).not.toBeNull();
            expect(panel).toHaveAttribute('data-server-wait', 'K8-12'); // 기다리는 계약판 행
        }
        fireEvent.click(screen.getByRole('tab', { name: '현 목록' }));
        expect(screen.getByRole('tabpanel', { name: '현 목록' })).not.toHaveAttribute('data-server-wait'); // 현 목록은 서버 값이 있다
        expect(container.textContent).not.toMatch(/유가|태평도|백마의종|청주병/);
    });
});
