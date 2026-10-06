// 외교(P-K02) — 관계 코드는 확인된 넷만 이름 · 제의는 관계에 맞는 것만 「준비 중」 · 받은 제의는 서버 대기(수락 · 거절 단추 없음) ·
// 재야 · 천하 관계(다른 세력끼리 · 세력 × 세력 표) · 군주가 아니면 「보기만」.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

// 주변 세계 탭(FrontierWorld, K3)은 세션의 장수로 GET /api/frontier(K8-09)를 읽는다 — 여기서는 배포 전처럼 404(서버 대기).
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7 }) }));
vi.mock('@/lib/api', () => ({
    fetchGame: () => Promise.resolve(new Response(JSON.stringify({ error: { code: 'NOT_FOUND', message: 'x' } }), { status: 404 })),
}));
import { DiplomacyPanel } from '../components/diplomacy/DiplomacyPanel';
import { matrixCellText, proposalsFor, relationOf, toRelations } from '../lib/diplomacy/relations';
import type { DiplomacyConflictResponse } from '../types/game';

const nation = (id: number, name: string, cities: string[] = []) => ({ nation: id, name, color: '#123456', type: '', level: 1, capital: 0, gennum: 1, cities, power: 0 });
const res: DiplomacyConflictResponse = {
    result: true, conflict: [], myNationID: 1,
    nations: [nation(1, '[우리]', ['허현']), nation(2, '[갑]', ['진류', '양적']), nation(3, '[을]'), nation(4, '[병]')],
    diplomacyList: { 1: { 2: 0, 3: 7, 4: 5 }, 2: { 3: 1 }, 3: { 4: 2 } },
};

describe('관계 모델', () => {
    it('확인된 코드만 이름 · 나머지는 알 수 없음 · 다른 세력끼리는 교전 · 선포만', () => {
        expect(relationOf(2)).toBe('none');
        expect(relationOf(5)).toBe('unknown');
        const v = toRelations(res);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.rows.map((r) => [r.name, r.relation])).toEqual([['[갑]', 'war'], ['[을]', 'nonAggression'], ['[병]', 'unknown']]);
        expect(v.othersAtWar).toEqual([{ a: { id: 2, name: '[갑]' }, b: { id: 3, name: '[을]' }, relation: 'declared' }]);
        expect(toRelations({ ...res, myNationID: 0 })).toEqual({ state: 'stateless' });
    });

    it('세력 × 세력 표 — 우리가 낀 칸은 관계 이름, 다른 두 세력 사이는 교전 · 선포만, 같은 세력 · 없는 칸은 비움', () => {
        const v = toRelations(res);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.matrix.nations.map((n) => n.id)).toEqual([1, 2, 3, 4]);
        expect(v.matrix.cells[1][1]).toBe('self');
        expect(v.matrix.cells[1][3]).toBe('nonAggression');
        expect(v.matrix.cells[4][1]).toBeNull(); // 서버가 안 준 칸
        expect(matrixCellText(v.matrix.cells[1][2], true)).toBe('교전');
        expect(matrixCellText(v.matrix.cells[1][4], true)).toBe('모름');
        expect(matrixCellText(v.matrix.cells[2][3], false)).toBe('선포');
        expect(matrixCellText(v.matrix.cells[3][4], false)).toBe(''); // 다른 세력끼리 「관계 없음」은 보이지 않는다
        expect(matrixCellText('self', true)).toBe('');
    });

    it('제의는 관계에 맞는 것만 — 모르는 관계엔 아무것도 두지 않는다', () => {
        expect(proposalsFor('war').map((p) => p.label)).toEqual(['원조', '종전 제의']);
        expect(proposalsFor('nonAggression').map((p) => p.label)).toEqual(['원조', '불가침 파기']);
        expect(proposalsFor('none').map((p) => p.label)).toEqual(['원조', '불가침 제의', '선전포고']);
        expect(proposalsFor('unknown')).toEqual([]);
    });
});

describe('외교 칸', () => {
    it('세력 색은 #rrggbb 만 그대로 — 그 밖은 기본색(원장 D90 · safeNationColor)', () => {
        const odd = { ...res, nations: res.nations.map((n) => (n.nation === 2 ? { ...n, color: 'url(x)' } : n)) };
        render(<DiplomacyPanel load={{ state: 'ready', view: toRelations(odd) }} />);
        const rows = within(screen.getByRole('list', { name: '세력별 관계' })).getAllByRole('listitem');
        const swatch = (row: HTMLElement) => row.querySelector<HTMLElement>('i[aria-hidden="true"]')!;
        expect(swatch(rows[0]).style.background).toBe('rgb(142, 136, 121)'); // [갑] — 기본색 #8e8879
        expect(swatch(rows[1]).style.background).toBe('rgb(18, 52, 86)'); // [을] — #123456 그대로
    });

    it('세력 행 · 제의 단추는 「준비 중」 · 현 목록은 누르면 펼침 · 받은 제의는 서버 대기', () => {
        render(<DiplomacyPanel load={{ state: 'ready', view: toRelations(res) }} />);
        const rows = within(screen.getByRole('list', { name: '세력별 관계' })).getAllByRole('listitem');
        expect(rows[0]).toHaveTextContent('교전');
        const peace = within(rows[0]).getByRole('button', { name: /종전 제의/ });
        expect(peace).toHaveAttribute('data-input-id', 'court.offerPeace');
        expect(peace).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
        fireEvent.click(within(rows[0]).getByRole('button', { name: '현 2' }));
        expect(within(rows[0]).getByText('진류 · 양적')).toBeInTheDocument();
        expect(screen.getByText('받은 제의 준비 중')).toBeInTheDocument();
        // 받은 제의 응답은 서버 대기 — 수락 · 거절 단추를 그리지 않는다(계약판 K6-05 · A7).
        expect(within(screen.getByRole('region', { name: '받은 제의' })).queryByRole('button')).toBeNull();
        const world = screen.getByRole('region', { name: '천하 관계' });
        expect(within(world).getByRole('list', { name: '다른 세력끼리' })).toHaveTextContent('[갑] · [을]');
        expect(screen.queryByRole('note')).toBeNull(); // 군주인지 모르면 안내 없음
    });

    it('「세력 × 세력 표로 보기」는 누르면 펼치고 다시 누르면 닫는다', () => {
        render(<DiplomacyPanel load={{ state: 'ready', view: toRelations(res) }} />);
        const world = screen.getByRole('region', { name: '천하 관계' });
        const toggle = within(world).getByRole('button', { name: '세력 × 세력 표로 보기' });
        expect(toggle).toHaveAttribute('aria-expanded', 'false');
        fireEvent.click(toggle);
        const table = within(world).getByRole('region', { name: '세력 × 세력 표' });
        const rows = within(table).getAllByRole('row');
        expect(rows).toHaveLength(5); // 머리 + 네 세력
        expect(within(rows[1]).getAllByRole('cell').map((c) => c.textContent)).toEqual(['＼', '교전', '불가침', '모름']);
        expect(within(rows[2]).getAllByRole('cell').map((c) => c.textContent)).toEqual(['', '＼', '선포', '']);
        fireEvent.click(within(world).getByRole('button', { name: '세력 × 세력 표 닫기' }));
        expect(within(world).queryByRole('region', { name: '세력 × 세력 표' })).toBeNull();
    });

    it('군주가 아니면 「외교는 군주가 합니다 — 보기만」 안내, 군주면 없음', () => {
        const { rerender } = render(<DiplomacyPanel load={{ state: 'ready', view: toRelations(res) }} viewerIsRuler={false} />);
        expect(screen.getByRole('note')).toHaveTextContent('외교는 군주가 합니다');
        rerender(<DiplomacyPanel load={{ state: 'ready', view: toRelations(res) }} viewerIsRuler />);
        expect(screen.queryByRole('note')).toBeNull();
    });

    it('재야 · 실패 · 주변 세계', async () => {
        const retry = vi.fn();
        const { rerender } = render(<DiplomacyPanel load={{ state: 'ready', view: { state: 'stateless' } }} />);
        expect(screen.getByText('세력이 없어 외교를 할 수 없습니다')).toBeInTheDocument();
        rerender(<DiplomacyPanel load={{ state: 'error', onRetry: retry }} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(retry).toHaveBeenCalled();
        fireEvent.click(screen.getByRole('tab', { name: '주변 세계' }));
        expect(await screen.findByText('주변 세계 준비 중')).toBeInTheDocument();
    });
});
