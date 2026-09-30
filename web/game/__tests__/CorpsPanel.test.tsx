// 군단(P-C01) — 서버 값만(군 이름 · 병력 · 구간 · 시야) · 출병/부대 모으기는 명령 흐름으로 · 편성 해제는 후보일 때만 · 세력 작전은 서버 대기.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { CorpsPanel } from '../components/corps/CorpsPanel';
import { deployOrderOf, toCorpsRows } from '../lib/corps/corps-model';
import type { CorpsList, Visibility } from '../lib/campaign-reads';

const corps: CorpsList = {
    status: 'READY',
    corps: [
        { corpsId: 'C-1', ownerGeneralId: 1, commanderGeneralId: 1, commanderName: '[나]', nationId: 3, nationColor: '#aa3333', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL' as never, own: true, troops: 1200, marchPath: ['P-1', 'P-2'], destinationProvinceId: 'P-2' },
        { corpsId: 'C-9', ownerGeneralId: 9, commanderGeneralId: 9, commanderName: '[적장]', nationId: 5, nationColor: '#3333aa', provinceId: 'P-7', commanderyNo: 40, visibility: 'INTEL' as never, own: false, troopsBand: { code: 'B3', label: '3천 안팎' }, ageTurns: 2 },
    ],
};
const vision: Visibility = { status: 'READY', commanderies: [{ no: 12, id: 'c12', name: '영천군', tier: 'FULL' as never }] };
const deploy = { available: true, maxReservedTurns: 12 as const, bugoks: [], destinations: [{ provinceId: 'P-2', name: '진류' }], order: { orderId: 'O-1', destinationProvinceId: 'P-2', stop: 'ENCOUNTER' } };

describe('군단 모델', () => {
    it('내 군단은 병력 · 목적지 이름, 남의 군단은 구간 · 시야 — 모르는 곳은 null', () => {
        const [mine, other] = toCorpsRows(corps, vision, deploy);
        expect(mine).toMatchObject({ own: true, where: '영천군', troops: 1200, band: null, marching: true, destination: '진류' });
        expect(other).toMatchObject({ own: false, where: null, troops: null, band: '3천 안팎', ageTurns: 2 });
        expect(deployOrderOf(deploy)).toEqual({ destination: '진류', stop: '조우 중단' });
        expect(toCorpsRows({ status: 'WRONG_RULE_PROFILE' }, vision, deploy)).toEqual([]);
    });
});

describe('군단 칸', () => {
    const rows = toCorpsRows(corps, vision, deploy);
    const base = {
        load: { state: 'ready' as const, rows }, order: deployOrderOf(deploy),
        releaseOptions: { inputId: 'court.releaseCorps' as const, available: true, choices: [{ label: '[나] 군단', arguments: { targetGeneralId: 1 }, available: true }] },
        onOpenFlow: vi.fn(), onRelease: vi.fn(async () => ({ ok: true })),
    };

    it('출병 · 부대 모으기는 명령 흐름을 연다', () => {
        const onOpenFlow = vi.fn();
        render(<CorpsPanel {...base} onOpenFlow={onOpenFlow} />);
        fireEvent.click(screen.getByRole('button', { name: '출병' }));
        fireEvent.click(screen.getByRole('button', { name: '부대 모으기' }));
        expect(onOpenFlow.mock.calls).toEqual([['action.deploy'], ['action.muster']]);
        expect(screen.getByRole('status')).toHaveTextContent('지금 출병 명령 · 목적지 진류 · 조우 중단');
    });

    it('편성 해제는 확인 뒤 서버가 준 인자로 보내고, 거절되면 그 사유로 막는다', async () => {
        const onRelease = vi.fn(async () => ({ ok: false, code: 'NOT_RULER', reason: '주공만 할 수 있습니다' }));
        render(<CorpsPanel {...base} onRelease={onRelease} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        const card = screen.getByRole('article', { name: '군단 — [나]' });
        expect(within(card).getByText('영천군')).toBeInTheDocument();
        fireEvent.click(within(card).getByRole('button', { name: '편성 해제' }));
        expect(screen.getByText('군단 편성을 풉니다')).toBeInTheDocument();
        await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: '편성 해제' }).at(-1)!); });
        expect(onRelease).toHaveBeenCalledWith(rows[0], { targetGeneralId: 1 });
        const blocked = within(screen.getByRole('article', { name: '군단 — [나]' })).getByRole('button', { name: '편성 해제' });
        expect(blocked).toHaveAttribute('data-input-status', 'BLOCKED');
        expect(screen.getAllByText('주공만 할 수 있습니다').length).toBeGreaterThan(0);
    });

    it('남의 군단은 편성 해제가 없고 시야를 「n순 전 첩보」로 보인다', () => {
        render(<CorpsPanel {...base} />);
        const other = within(screen.getByRole('region', { name: '보이는 다른 군단' })).getByRole('button');
        expect(other).toHaveTextContent('2순 전 첩보');
        fireEvent.click(other);
        expect(within(screen.getByRole('article', { name: '군단 — [적장]' })).queryByRole('button', { name: '편성 해제' })).toBeNull();
    });

    it('출전한 군단이 없음 · 실패 · 세력 작전 서버 대기', () => {
        const retry = vi.fn();
        const { rerender } = render(<CorpsPanel {...base} load={{ state: 'ready', rows: [] }} />);
        expect(screen.getByText('출전한 군단이 없습니다')).toBeInTheDocument();
        rerender(<CorpsPanel {...base} load={{ state: 'error', onRetry: retry }} />);
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(retry).toHaveBeenCalled();
        fireEvent.click(screen.getByRole('tab', { name: '세력 작전' }));
        expect(screen.getByText('세력 작전 준비 중')).toBeInTheDocument();
    });
});
