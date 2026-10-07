// 12순 공용 부품(작전실 열 · 명령 흐름 띠) — 한 모델 · 한 읽기 · 두 모드. 서버가 안 준 날짜 · 시각 · 대상은 그리지 않는다.
import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TurnSlots } from '../components/turn-slots/TurnSlots';
import { api } from '../lib/api';
import { announceTurnSlotsChanged, filledCount, firstEmpty, fromReservedCommands, slotLabel, useTurnSlots } from '../lib/turn-slots';

vi.mock('../lib/api', () => ({ api: { reservedCommands: vi.fn(), mapPreview: vi.fn(), gameConst: vi.fn() } }));

const ring = (filled: number[], extra: { turnIdx: number; action: string; brief: string }[] = []) => ({
    result: true, generalId: 1,
    slots: [...filled.map((turnIdx) => ({ turnIdx, action: 'action.farm', brief: '', arg: {} })), ...extra.map((e) => ({ ...e, arg: {} }))],
});

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.mapPreview).mockResolvedValue({ cities: [{ id: 9, name: '진류', displayName: '진류현' }] } as never);
    vi.mocked(api.gameConst).mockResolvedValue({ gameUnitConst: [{ id: 1100, name: '창병' }] } as never);
});

describe('12순 모델', () => {
    it('링을 12칸으로 — 표 이름 · 모르는 코드는 서버 요약, 링에 없는 날짜 · 시각 · 대상 · 표식은 비운다', () => {
        const slots = fromReservedCommands(ring([0], [{ turnIdx: 3, action: 'oldCode', brief: '옛 명령' }, { turnIdx: 20, action: 'action.farm', brief: '' }]));
        expect(slots).toHaveLength(12);
        expect(slots[0]).toEqual({ turnIdx: 0, state: 'reserved', inputId: 'action.farm', name: '농지개간', arg: {}, summary: null, when: null, at: null, blockedCode: null, markers: [] });
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
    it('기존 예약/새로 마운트/새로고침에서 저장 인자와 서버 이름을 풀고 열·띠 모두 같은 문장을 읽는다', async () => {
        const saved = { result: true, generalId: 1, slots: [
            { turnIdx: 0, action: 'che_징병', brief: '징병', arg: { crewType: 1100, amount: 1500 } },
            { turnIdx: 1, action: 'che_이동', brief: '이동', arg: { destCityID: 9 } },
        ] };
        vi.mocked(api.reservedCommands).mockResolvedValue(saved);
        function SavedRing() {
            const { load } = useTurnSlots(1);
            return <><TurnSlots mode="column" load={load} onSelect={vi.fn()} onRetry={vi.fn()} />
                <TurnSlots mode="strip" load={load} onSelect={vi.fn()} onRetry={vi.fn()} /></>;
        }
        const view = render(<SavedRing />);
        await waitFor(() => expect(screen.getAllByRole('button', { name: '01순 — 창병 1,500명 징병' })).toHaveLength(2));
        const destinations = screen.getAllByRole('button', { name: '02순 — 진류현으로 이동' });
        expect(destinations).toHaveLength(2);
        expect(destinations.every(row => row.textContent?.includes('진류현으로 이동'))).toBe(true);
        await act(async () => announceTurnSlotsChanged());
        await waitFor(() => expect(api.reservedCommands).toHaveBeenCalledTimes(2));
        view.unmount();
        render(<SavedRing />);
        await waitFor(() => expect(screen.getAllByRole('button', { name: '01순 — 창병 1,500명 징병' })).toHaveLength(2));
    });

    it('이름 읽기 실패는 예약된 명령과 실제 인원을 보존하며 병종 숫자 대신 미확인을 표시한다', async () => {
        vi.mocked(api.gameConst).mockRejectedValue(new Error('503'));
        vi.mocked(api.mapPreview).mockRejectedValue(new Error('503'));
        vi.mocked(api.reservedCommands).mockResolvedValue({ result: true, generalId: 1, slots: [
            { turnIdx: 0, action: 'che_징병', brief: '징병', arg: { crewType: 9999, amount: 500 } },
        ] });
        const { result } = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(result.current.load.state).toBe('ready'));
        if (result.current.load.state === 'ready') {
            expect(result.current.load.slots[0]).toMatchObject({ name: '병종 이름 확인 불가 500명 징병', arg: { crewType: 9999, amount: 500 } });
        }
    });

    it('장수가 바뀌면 이전 12순을 숨기고 늦게 온 이전 응답도 버린다', async () => {
        let resolvePrevious!: (value: ReturnType<typeof ring>) => void;
        let resolveCurrent!: (value: ReturnType<typeof ring>) => void;
        vi.mocked(api.reservedCommands)
            .mockResolvedValueOnce(ring([0]) as never)
            .mockImplementationOnce(() => new Promise((resolve) => { resolvePrevious = resolve; }) as never)
            .mockImplementationOnce(() => new Promise((resolve) => { resolveCurrent = resolve; }) as never);
        const { result, rerender } = renderHook(({ generalId, refreshKey }) => useTurnSlots(generalId, refreshKey), {
            initialProps: { generalId: 1 as number | null, refreshKey: 0 },
        });
        await waitFor(() => expect(result.current.load.state).toBe('ready'));
        rerender({ generalId: 1, refreshKey: 1 });
        expect(result.current.load.state).toBe('ready');
        rerender({ generalId: 2, refreshKey: 1 });
        expect(result.current.load).toEqual({ state: 'loading' });
        await act(async () => { resolvePrevious(ring([0, 1])); });
        expect(result.current.load).toEqual({ state: 'loading' });
        await act(async () => { resolveCurrent({ ...ring([3, 4, 5]), generalId: 2 }); });
        expect(result.current.load.state).toBe('ready');
        if (result.current.load.state === 'ready') expect(filledCount(result.current.load.slots)).toBe(3);
        rerender({ generalId: null, refreshKey: 1 });
        expect(result.current.load).toEqual({ state: 'loading' });
        expect(api.reservedCommands).toHaveBeenCalledTimes(3);
    });

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
