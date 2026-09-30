// 12순 공용 부품(작전실 열 · 명령 흐름 띠) — 한 모델 · 한 읽기 · 두 모드. 서버가 안 준 날짜 · 시각 · 대상은 그리지 않는다.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TurnSlots } from '../components/turn-slots/TurnSlots';
import { api } from '../lib/api';
import { announceTurnSlotsChanged, filledCount, firstEmpty, fromReservedCommands, slotLabel, useTurnSlots } from '../lib/turn-slots';

vi.mock('../lib/api', () => ({ api: { reservedCommands: vi.fn() } }));

const ring = (filled: number[], extra: { turnIdx: number; action: string; brief: string }[] = []) => ({
    result: true, generalId: 1,
    slots: [...filled.map((turnIdx) => ({ turnIdx, action: 'action.farm', brief: '', arg: {} })), ...extra.map((e) => ({ ...e, arg: {} }))],
});

beforeEach(() => vi.clearAllMocks());

describe('12순 모델', () => {
    it('링을 12칸으로 — 표 이름 · 모르는 코드는 서버 요약, 링에 없는 날짜 · 시각 · 대상 · 표식은 비운다', () => {
        const slots = fromReservedCommands(ring([0], [{ turnIdx: 3, action: 'oldCode', brief: '옛 명령' }, { turnIdx: 20, action: 'action.farm', brief: '' }]));
        expect(slots).toHaveLength(12);
        expect(slots[0]).toEqual({ turnIdx: 0, state: 'reserved', inputId: 'action.farm', name: '농지개간', summary: null, when: null, at: null, blockedCode: null, markers: [] });
        expect(slots[3]).toMatchObject({ state: 'reserved', inputId: null, name: '옛 명령' });
        expect(slotLabel(slots[1])).toBe('02순 — 빈 순');
        expect(firstEmpty(slots)).toBe(1);
        expect(filledCount(slots)).toBe(2);
        expect(firstEmpty(fromReservedCommands(ring(Array.from({ length: 12 }, (_, i) => i))))).toBeNull();
        expect(fromReservedCommands(null).every((s) => s.state === 'empty')).toBe(true);
    });
});

describe('12순 부품', () => {
    const slots = fromReservedCommands(ring([0, 2]));

    it('열: 12 × 52, 01순은 다음 순 띠, 빈 순 「+ 예약」 · 채운 순 「예약」 — 어느 행이든 누르면 그 순', () => {
        const onSelect = vi.fn();
        render(<TurnSlots mode="column" load={{ state: 'ready', slots }} onSelect={onSelect} onRetry={vi.fn()} />);
        const group = screen.getByRole('group', { name: '명령 목록 12순' });
        const rows = within(group).getAllByRole('button');
        expect(rows).toHaveLength(12);
        expect(rows[0]).toHaveAttribute('data-next', 'true');
        expect(rows[0]).toHaveTextContent('예약');
        expect(rows[1]).toHaveTextContent('+ 예약');
        fireEvent.click(rows[2]);
        fireEvent.click(rows[5]);
        expect(onSelect.mock.calls.map((c) => c[0])).toEqual([2, 5]);
    });

    it('막힌 순은 「막힘」 칩으로 보이고 누르면 같은 onSelect(흐름에서 바꾸기)', () => {
        const blocked = slots.map((s) => (s.turnIdx === 2 ? { ...s, state: 'blocked' as const, blockedCode: 'OUTSIDE_CITY' } : s));
        const onSelect = vi.fn();
        render(<TurnSlots mode="column" load={{ state: 'ready', slots: blocked }} onSelect={onSelect} onRetry={vi.fn()} />);
        const row = screen.getByRole('button', { name: '03순 — 농지개간' });
        expect(row).toHaveTextContent('막힘');
        expect(row).not.toHaveTextContent('OUTSIDE_CITY');
        fireEvent.click(row);
        expect(onSelect).toHaveBeenCalledWith(2, expect.objectContaining({ state: 'blocked' }));
    });

    it('띠: 채우는 순은 눌림, 빈 순 글자 — 날짜 · 표식 칩은 띠에 없다', () => {
        render(<TurnSlots mode="strip" load={{ state: 'ready', slots }} current={1} onSelect={vi.fn()} onRetry={vi.fn()} />);
        expect(screen.getByRole('button', { name: '02순 — 빈 순' })).toHaveAttribute('aria-pressed', 'true');
        expect(screen.queryByText('+ 예약')).toBeNull();
    });

    it('로딩은 행 모양 12개, 실패는 빈 순과 다른 모양(다시 시도)', () => {
        const onRetry = vi.fn();
        const { container, rerender } = render(<TurnSlots mode="column" load={{ state: 'loading' }} onSelect={vi.fn()} onRetry={onRetry} />);
        expect(screen.getByRole('status', { name: '12순을 불러오는 중' })).toBeInTheDocument();
        expect(container.querySelectorAll('[aria-hidden="true"]')).toHaveLength(12);
        rerender(<TurnSlots mode="column" load={{ state: 'error', message: '500' }} onSelect={vi.fn()} onRetry={onRetry} />);
        expect(screen.queryByText('빈 순')).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(onRetry).toHaveBeenCalled();
    });
});

describe('한 읽기', () => {
    function Probe({ id }: { id: string }) {
        const { load } = useTurnSlots(1);
        return <div data-testid={id}>{load.state === 'ready' ? filledCount(load.slots) : load.state}</div>;
    }

    it('한 곳에서 예약을 알리면 마운트된 사용처(열 · 띠)가 모두 다시 읽는다', async () => {
        vi.mocked(api.reservedCommands).mockResolvedValue(ring([0]) as never);
        render(<><Probe id="column" /><Probe id="strip" /></>);
        await waitFor(() => expect(screen.getByTestId('column')).toHaveTextContent('1'));
        vi.mocked(api.reservedCommands).mockResolvedValue(ring([0, 1]) as never);
        await act(async () => { announceTurnSlotsChanged(); });
        await waitFor(() => expect(screen.getByTestId('column')).toHaveTextContent('2'));
        expect(screen.getByTestId('strip')).toHaveTextContent('2');
        expect(api.reservedCommands).toHaveBeenCalledTimes(4);
    });
});
