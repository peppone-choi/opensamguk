import { useState } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ArgFieldView from '../components/command-flow/ArgFields';
import { fromTravel, buildArgs } from '../lib/command-flow/options';
import type { ArgValue, Draft } from '../lib/command-flow/flow-state';
import type { DestinationOption } from '../lib/types';

const rows: DestinationOption[] = [
    { provinceId: '70623', name: '장안현', available: true, distanceMm: 12_345_678, costMm: 20_000_000,
        estimatedTurns: 2, reachability: 'MULTI_TURN', arrivesThisTurn: false },
    { provinceId: '70634', name: '두', available: true, distanceMm: 1_000_000, estimatedTurns: 1,
        reachability: 'THIS_TURN', arrivesThisTurn: true },
    { provinceId: '70523', name: '부평현', available: false, reason: '통행할 수 없습니다' },
];

function setup(destinations = rows, initial: Draft = {}) {
    const onArg = vi.fn();
    const field = fromTravel({ inputId: 'action.move', available: true, destinations }).fields[0];
    function Harness() {
        const [draft, setDraft] = useState(initial);
        const change = (key: string, value: ArgValue) => { onArg(key, value); setDraft(was => ({ ...was, [key]: value })); };
        return <ArgFieldView field={field} draft={draft} missing={false} onChange={change} inputId="action.move" />;
    }
    const view = render(<Harness />);
    return { ...view, onArg };
}

describe('LIVE-05 이동 목적지 목록', () => {
    it('새 조회에서 선택지가 불가하거나 없어지면 이유를 보여 주고 기존 ID로 예약하지 않는다', () => {
        const onArg = vi.fn();
        const draft = { destinationProvinceId: '70623' };
        const options = (destinations: DestinationOption[]) => fromTravel({ inputId: 'action.forcedMarch', available: true, destinations });
        const view = (destinations: DestinationOption[]) => <ArgFieldView field={options(destinations).fields[0]} draft={draft} missing={false} onChange={onArg} inputId="action.forcedMarch" />;
        const { rerender } = render(view(rows));
        const blocked = [{ ...rows[0], available: false, reason: '강행의 피로 비용을 지불할 수 없습니다' }];
        rerender(view(blocked));
        expect(screen.getByRole('alert')).toHaveTextContent('강행의 피로 비용을 지불할 수 없습니다');
        expect(screen.getByRole('alert').closest('details')).toBeNull();
        expect(buildArgs(options(blocked), draft)).toEqual({ ok: false, missing: ['destinationProvinceId'] });
        rerender(view([]));
        expect(screen.getByRole('alert')).toHaveTextContent('고른 구역 70623의 현재 목적지 정보를 확인할 수 없습니다');
        expect(buildArgs(options([]), draft)).toEqual({ ok: false, missing: ['destinationProvinceId'] });
        expect(screen.queryByText(/장안현/)).not.toBeInTheDocument();
        expect(onArg).not.toHaveBeenCalled();
    });
    it('목록 정렬·검색·분류는 예약이나 목적지 변경을 제출하지 않는다', () => {
        const { onArg } = setup();
        const list = screen.getByRole('listbox', { name: '어디로' });
        expect(within(list).getAllByRole('option').map(r => r.getAttribute('data-target-id'))).toEqual(['70634', '70623']);
        expect(screen.getByLabelText('목적지 범위')).toHaveValue('nearby');
        expect(screen.getByRole('checkbox', { name: '가능만' })).toBeChecked();
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '장안' } });
        expect(within(list).getAllByRole('option')).toHaveLength(1);
        fireEvent.change(screen.getByLabelText('목적지 정렬'), { target: { value: 'name' } });
        fireEvent.change(screen.getByLabelText('목적지 범위'), { target: { value: 'this-turn' } });
        expect(within(list).queryAllByRole('option')).toHaveLength(0);
        expect(onArg).not.toHaveBeenCalled();
    });

    it('합법 다턴을 선택하면 원 ID를 유지하고 검색에서 숨겨져도 선택을 비우지 않는다', async () => {
        const { onArg } = setup();
        fireEvent.click(screen.getByRole('option', { name: /장안현/ }));
        await waitFor(() => expect(onArg).toHaveBeenCalledWith('destinationProvinceId', '70623'));
        expect(screen.getByText('고른 목적지: 장안현 — 도착 예상·비용·거리 보기')).toBeInTheDocument();
        expect(screen.getByText(/거리 12.345678km/)).toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '두' } });
        expect(screen.getByText('고른 목적지: 장안현 — 도착 예상·비용·거리 보기')).toBeInTheDocument();
        expect(onArg).toHaveBeenCalledTimes(1);
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '' } });
        expect(screen.getByRole('option', { name: /장안현/ })).toHaveAttribute('aria-selected', 'true');
    });

    it('불가 후보는 검색해도 사유를 유지하고 고르거나 제출하지 않는다', () => {
        const { onArg } = setup();
        fireEvent.click(screen.getByRole('checkbox', { name: '가능만' }));
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '70523' } });
        const blocked = screen.getByRole('option', { name: /부평현/ });
        expect(blocked).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(blocked);
        expect(screen.getAllByText('통행할 수 없습니다').length).toBeGreaterThan(0);
        expect(onArg).not.toHaveBeenCalled();
    });

    it('1608개 전역 후보 계약을 유지하되 기본은 가까운 가능 20곳이고 먼 곳은 전체 검색한다', () => {
        const many: DestinationOption[] = Array.from({ length: 1608 }, (_, i) => ({ provinceId: String(i + 1), name: `목적지 ${i + 1}`, available: true, distanceMm: (i + 1) * 1_000_000 }));
        const { container, onArg } = setup(many);
        expect(screen.getByRole('status')).toHaveTextContent('20 / 1,608곳');
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(20);
        expect(screen.queryByRole('button', { name: /목적지 더 보기/ })).not.toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('목적지 범위'), { target: { value: 'all' } });
        expect(screen.getByRole('status')).toHaveTextContent('1,608 / 1,608곳');
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(50);
        fireEvent.click(screen.getByRole('button', { name: /목적지 더 보기/ }));
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(100);
        fireEvent.change(screen.getByLabelText('목적지 범위'), { target: { value: 'nearby' } });
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(20);
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '1608' } });
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(1);
        expect(screen.getByRole('option', { name: /목적지 1608/ })).toHaveAttribute('data-target-id', '1608');
        expect(screen.getByText(/검색은 전체 목적지에서 찾습니다/)).toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '' } });
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(20);
        expect(many).toHaveLength(1608);
        expect(onArg).not.toHaveBeenCalled();
    });

    it('기본 가능 필터는 가까운20곳을 자르기 전에 적용해 뒤쪽의 합법 다턴도 보인다', () => {
        const many: DestinationOption[] = Array.from({ length: 51 }, (_, i) => ({ provinceId: String(i + 1), name: `차단 ${i + 1}`, available: false, reason: '통행 불가', distanceMm: (i + 1) * 1_000_000 }));
        many.push({ provinceId: '99', name: '갈 수 있는 곳', available: true, distanceMm: 99_000_000,
            estimatedTurns: 4, reachability: 'MULTI_TURN', arrivesThisTurn: false });
        const { onArg } = setup(many);
        const list = screen.getByRole('listbox', { name: '어디로' });
        expect(within(list).getAllByRole('option')).toHaveLength(1);
        expect(within(list).getByRole('option', { name: /갈 수 있는 곳/ })).toHaveAttribute('data-target-id', '99');
        expect(within(list).getByRole('option', { name: /갈 수 있는 곳/ })).toHaveTextContent('예상 4순');
        fireEvent.change(screen.getByLabelText('목적지 범위'), { target: { value: 'all' } });
        fireEvent.click(screen.getByRole('checkbox', { name: '가능만' }));
        fireEvent.click(screen.getByRole('button', { name: /목적지 더 보기/ }));
        expect(within(list).getAllByRole('option')).toHaveLength(52);
        expect(list.querySelector('[data-target-id="1"]')).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(screen.getByRole('checkbox', { name: '가능만' }));
        expect(within(list).getAllByRole('option')).toHaveLength(1);
        expect(onArg).not.toHaveBeenCalled();
    });

    it('거리 정보가 없는 가능 후보는 임의 근접 판정 없이 별도 안내로 최대20곳 표시한다', () => {
        const unknown: DestinationOption[] = Array.from({ length: 30 }, (_, i) => ({ provinceId: String(i + 1), name: `거리 미상 ${i + 1}`, available: true }));
        const { container, onArg } = setup(unknown);
        expect(container.querySelectorAll('button[role="option"]')).toHaveLength(20);
        expect(screen.getByText(/목적지 거리를 확인하지 못해/)).toBeInTheDocument();
        expect(screen.queryByText(/서버 경로 거리 기준 가까운/)).not.toBeInTheDocument();
        fireEvent.change(screen.getByLabelText('목적지 검색'), { target: { value: '30' } });
        expect(screen.getByRole('option', { name: /거리 미상 30/ })).toHaveTextContent('도달 정보 미확인');
        expect(onArg).not.toHaveBeenCalled();
    });

    it('검색 중 Escape는 검색어부터 비우고 IME 조합과 선택을 보존한다', () => {
        const { onArg } = setup(rows, { destinationProvinceId: '70623' });
        const search = screen.getByLabelText('목적지 검색');
        fireEvent.change(search, { target: { value: '장안' } });
        const composing = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true, isComposing: true });
        fireEvent(search, composing);
        expect(search).toHaveValue('장안');
        expect(composing.defaultPrevented).toBe(false);
        const clear = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        fireEvent(search, clear);
        expect(clear.defaultPrevented).toBe(true);
        expect(search).toHaveValue('');
        expect(screen.getByRole('option', { name: /장안현/ })).toHaveAttribute('aria-selected', 'true');
        expect(onArg).not.toHaveBeenCalled();
        const close = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        fireEvent(search, close);
        expect(close.defaultPrevented).toBe(false);
    });
});
