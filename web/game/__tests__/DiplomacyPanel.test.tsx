// 외교(P-K02) — 관계 코드는 확인된 넷만 이름 · 제의는 관계에 맞는 것만 「준비 중」 · 받은 제의는 서버 대기 · 재야 · 다른 세력끼리.
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DiplomacyPanel } from '../components/diplomacy/DiplomacyPanel';
import { proposalsFor, relationOf, toRelations } from '../lib/diplomacy/relations';
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

    it('제의는 관계에 맞는 것만 — 모르는 관계엔 아무것도 두지 않는다', () => {
        expect(proposalsFor('war').map((p) => p.label)).toEqual(['원조', '종전 제의']);
        expect(proposalsFor('nonAggression').map((p) => p.label)).toEqual(['원조', '불가침 파기']);
        expect(proposalsFor('none').map((p) => p.label)).toEqual(['원조', '불가침 제의', '선전포고']);
        expect(proposalsFor('unknown')).toEqual([]);
    });
});

describe('외교 칸', () => {
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
        expect(within(screen.getByRole('region', { name: '다른 세력끼리' })).getByText(/\[갑\] · \[을\]/)).toBeInTheDocument();
    });

    it('재야 · 실패 · 주변 세계', () => {
        const retry = vi.fn();
        const { rerender } = render(<DiplomacyPanel load={{ state: 'ready', view: { state: 'stateless' } }} />);
        expect(screen.getByText('세력이 없어 외교를 할 수 없습니다')).toBeInTheDocument();
        rerender(<DiplomacyPanel load={{ state: 'error', onRetry: retry }} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(retry).toHaveBeenCalled();
        fireEvent.click(screen.getByRole('tab', { name: '주변 세계' }));
        expect(screen.getByText('주변 세계 준비 중')).toBeInTheDocument();
    });
});
