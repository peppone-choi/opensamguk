import equipmentCatalog from '../../../data/curated/han/equipment-v1.json';
// 12순 공용 부품(작전실 열 · 명령 흐름 띠) — 한 모델 · 한 읽기 · 두 모드. 서버가 안 준 날짜 · 시각 · 대상은 그리지 않는다.
import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TurnSlots } from '../components/turn-slots/TurnSlots';
import { api } from '../lib/api';
import { readServerCookie } from '../lib/serverGameUrl';
import { announceTurnSlotsChanged, filledCount, firstEmpty, fromReservedCommands, slotLabel, useTurnSlots } from '../lib/turn-slots';

vi.mock('../lib/api', () => ({ api: { reservedCommands: vi.fn(), mapPreview: vi.fn(), gameConst: vi.fn(), travelOptions: vi.fn(), deployOptions: vi.fn(), legacyDirectOptions: vi.fn() } }));

vi.mock('../lib/serverGameUrl', () => ({ readServerCookie: vi.fn(() => undefined) }));

// B1 reservation GET rows carry their UUID revision (read in the same SELECT as the row).
const rev = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const ring = (filled: number[], extra: { turnIdx: number; action: string; brief: string }[] = []) => ({
    result: true, generalId: 1,
    slots: [...filled.map((turnIdx) => ({ turnIdx, action: 'action.farm', brief: '', arg: {}, revision: rev(turnIdx) })),
        ...extra.map((e) => ({ ...e, arg: {}, revision: rev(100 + e.turnIdx) }))],
});

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(readServerCookie).mockReturnValue(undefined);
    vi.mocked(api.mapPreview).mockResolvedValue({ cities: [{ id: 9, name: '진류', displayName: '진류현' }] } as never);
    vi.mocked(api.gameConst).mockResolvedValue({ gameUnitConst: [{ id: 1100, name: '창병' }] } as never);
});

describe('12순 모델', () => {
    it('링을 12칸으로 — 표 이름 · 모르는 코드는 서버 요약, 링에 없는 날짜 · 시각 · 대상 · 표식은 비운다', () => {
        const slots = fromReservedCommands(ring([0], [{ turnIdx: 3, action: 'oldCode', brief: '옛 명령' }, { turnIdx: 20, action: 'action.farm', brief: '' }]));
        expect(slots).toHaveLength(12);
        expect(slots[0]).toEqual({ turnIdx: 0, state: 'reserved', inputId: 'action.farm', name: '농지개간', arg: {}, summary: null, when: null, at: null, blockedCode: null, markers: [], revision: rev(0) });
        expect(slots[1].revision).toBeNull();
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
            { turnIdx: 0, action: 'saved.recruit', brief: '징병', arg: { crewType: 1100, amount: 1500 }, revision: rev(0) },
            { turnIdx: 1, action: 'saved.move', brief: '이동', arg: { destCityID: 9 }, revision: rev(1) },
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
            { turnIdx: 0, action: 'saved.recruit', brief: '징병', arg: { crewType: 9999, amount: 500 }, revision: rev(0) },
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

    const provinceRing = (generalId = 1) => ({ result: true, generalId, slots: [
        { turnIdx: 0, action: 'action.move', brief: '', arg: { destinationProvinceId: 'P-1' }, revision: rev(0) },
        { turnIdx: 1, action: 'action.move', brief: '', arg: { destinationProvinceId: 'P-1' }, revision: rev(1) },
    ] });
    it('저장 이동 유형만 한 번 조회하며 새로고침에도 정확한 구역 이름을 읽는다', async () => {
        const saved = provinceRing();
        vi.mocked(api.reservedCommands).mockResolvedValue(saved);
        vi.mocked(api.travelOptions).mockResolvedValue({ inputId: 'action.move', available: true, destinations: [{ provinceId: 'P-1', name: '영천', available: true }] });
        const { result } = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(result.current.names.provinces?.['P-1']).toBe('영천'));
        expect(api.travelOptions).toHaveBeenCalledExactlyOnceWith('action.move', 1);
        expect(api.deployOptions).not.toHaveBeenCalled();
        if (result.current.load.state === 'ready') expect(result.current.load.slots.slice(0,2).map(slot => slot.name)).toEqual(['영천으로 이동', '영천으로 이동']);
        await act(async () => result.current.reload());
        await waitFor(() => expect(api.travelOptions).toHaveBeenCalledTimes(2));
        await waitFor(() => expect(result.current.names.provinces?.['P-1']).toBe('영천'));
        expect(saved).toEqual(provinceRing());
    });
    it('같은 world/장수/generation의 동시 옵션 읽기는 공유하며 구독 해제 후 늦은 응답을 버린다', async () => {
        let resolve!: (value: Awaited<ReturnType<typeof api.travelOptions>>) => void;
        vi.mocked(api.reservedCommands).mockResolvedValue(provinceRing());
        vi.mocked(api.travelOptions).mockImplementation(() => new Promise(done => { resolve = done; }));
        const view = renderHook(() => ({ one: useTurnSlots(1), two: useTurnSlots(1) }));
        await waitFor(() => expect(api.travelOptions).toHaveBeenCalledTimes(1));
        view.unmount();
        vi.mocked(api.travelOptions).mockResolvedValue({ inputId: 'action.move', available: true, destinations: [{ provinceId: 'P-1', name: '영천', available: true }] });
        const next = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(next.result.current.names.provinces?.['P-1']).toBe('영천'));
        expect(api.travelOptions).toHaveBeenCalledTimes(2);
        await act(async () => resolve({ inputId: 'action.move', available: true, destinations: [{ provinceId: 'P-1', name: '옛 이름', available: true }] }));
        expect(next.result.current.names.provinces?.['P-1']).toBe('영천');
    });
    it.each([false, true])('출병/강행 옵션 이름을 소비하고 충돌=%s이면 합성하지 않는다', async conflict => {
        vi.mocked(api.reservedCommands).mockResolvedValue({ result: true, generalId: 1, slots: [
            { turnIdx: 0, action: 'action.forcedMarch', brief: '', arg: { destinationProvinceId: 'P-1' }, revision: rev(0) },
            { turnIdx: 1, action: 'action.deploy', brief: '', arg: { destinationProvinceId: 'P-1', bugokIds: [3] }, revision: rev(1) },
        ] });
        vi.mocked(api.travelOptions).mockResolvedValue({ inputId: 'action.forcedMarch', available: true, destinations: [{ provinceId: 'P-1', name: '영천', available: true }] });
        vi.mocked(api.deployOptions).mockResolvedValue({ available: true, maxReservedTurns: 12, bugoks: [], destinations: [{ provinceId: 'P-1', name: conflict ? '양적' : '영천', available: true }] });
        const { result } = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(api.deployOptions).toHaveBeenCalledExactlyOnceWith(1));
        await act(async () => { await Promise.resolve(); });
        expect(api.travelOptions).toHaveBeenCalledExactlyOnceWith('action.forcedMarch', 1);
        expect(result.current.names.provinces).toEqual(conflict ? {} : { 'P-1': '영천' });
        if (result.current.load.state === 'ready') {
            expect(result.current.load.slots[0].name).toBe(conflict ? '강행 (목적지 현 이름 확인 불가)' : '영천으로 강행');
            expect(result.current.load.slots[1].name).toBe(conflict ? '부곡 #3 — 출병 (목적지 현 이름 확인 불가)' : '부곡 #3 — 영천으로 출병');
        }
    });
    it.each(['failure', 'mismatch', 'missing'] as const)('옵션 %s도 원 예약/인자를 유지한다', async mode => {
        vi.mocked(api.reservedCommands).mockResolvedValue(provinceRing());
        if (mode === 'failure') vi.mocked(api.travelOptions).mockRejectedValue(new Error('503'));
        else vi.mocked(api.travelOptions).mockResolvedValue({ inputId: mode === 'mismatch' ? 'action.return' : 'action.move', available: true,
            destinations: [{ provinceId: mode === 'missing' ? 'P-2' : 'P-1', name: '다른 조회', available: true }] });
        const { result } = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(api.travelOptions).toHaveBeenCalledTimes(1));
        await act(async () => { await Promise.resolve(); });
        if (result.current.load.state === 'ready') {
            expect(filledCount(result.current.load.slots)).toBe(2);
            expect(result.current.load.slots[0]).toMatchObject({ name: '이동 (목적지 현 이름 확인 불가)', arg: { destinationProvinceId: 'P-1' } });
        }
    });
    it('generation·장수·world 변경 뒤 늦은 이름 응답을 버린다', async () => {
        let resolveOld!: (value: Awaited<ReturnType<typeof api.travelOptions>>) => void;
        vi.mocked(readServerCookie).mockReturnValue('alpha');
        vi.mocked(api.reservedCommands).mockImplementation(async generalId => provinceRing(generalId));
        vi.mocked(api.travelOptions).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
            .mockImplementation(async () => ({ inputId: 'action.move', available: true, destinations: [{ provinceId: 'P-1', name: readServerCookie() === 'beta' ? '진류현' : '영천', available: true }] }));
        const view = renderHook(({ id, refresh }) => useTurnSlots(id, refresh), { initialProps: { id: 1, refresh: 0 } });
        await waitFor(() => expect(api.travelOptions).toHaveBeenCalledTimes(1));
        view.rerender({ id: 1, refresh: 1 });
        await waitFor(() => expect(view.result.current.names.provinces?.['P-1']).toBe('영천'));
        vi.mocked(readServerCookie).mockReturnValue('beta');
        view.rerender({ id: 1, refresh: 1 });
        expect(view.result.current.load.state).toBe('loading');
        await waitFor(() => expect(view.result.current.names.provinces?.['P-1']).toBe('진류현'));
        view.rerender({ id: 2, refresh: 1 });
        expect(view.result.current.load.state).toBe('loading');
        await waitFor(() => expect(api.travelOptions).toHaveBeenCalledWith('action.move', 2));
        await waitFor(() => expect(view.result.current.names.provinces?.['P-1']).toBe('진류현'));
        await act(async () => resolveOld({ inputId: 'action.move', available: true, destinations: [{ provinceId: 'P-1', name: '옛 세계 이름', available: true }] }));
        expect(view.result.current.names.provinces?.['P-1']).toBe('진류현');
        view.unmount();
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


describe('저장 장비 예약 이름 조회', () => {
    const equipmentId = equipmentCatalog.equipment[0].id;
    const equipmentRing = (generalId: number) => ({ result: true, generalId, slots: [
        { turnIdx: 0, action: 'action.tradeEquipment', brief: '장비매매', arg: { equipmentId, side: 'BUY' }, revision: rev(0) },
    ] });
    const options = (name: string) => ({ inputId: 'action.tradeEquipment' as const, available: false,
        choices: [], equipmentNames: { [equipmentId]: name } });
    it('장비 예약이 없으면 추가 조회 없이 기존 이름 경로를 유지한다', async () => {
        vi.mocked(api.reservedCommands).mockResolvedValue(ring([0]));
        const view = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(view.result.current.load.state).toBe('ready'));
        expect(api.legacyDirectOptions).not.toHaveBeenCalled();
        view.unmount();
    });
    it('거래 가능 여부와 별개로 서버 canonical 이름과 저장 equipmentId를 연결한다', async () => {
        vi.mocked(api.reservedCommands).mockResolvedValue(equipmentRing(1));
        vi.mocked(api.legacyDirectOptions).mockResolvedValue(options('노기(+1)'));
        const view = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(view.result.current.load.state === 'ready' && view.result.current.load.slots[0].name).toBe('노기(+1) 매입'));
        expect(api.legacyDirectOptions).toHaveBeenCalledWith('action.tradeEquipment', 1);
        view.unmount();
    });
    it.each<Record<string, string>>([{}, { [equipmentId]: '' }, { 'equipment:other': '다른 장비' }])('누락/빈/다른 장비 이름을 대입하지 않는다 %j', async equipmentNames => {
        vi.mocked(api.reservedCommands).mockResolvedValue(equipmentRing(1));
        vi.mocked(api.legacyDirectOptions).mockResolvedValue({ ...options(''), equipmentNames });
        const view = renderHook(() => useTurnSlots(1));
        await waitFor(() => expect(api.legacyDirectOptions).toHaveBeenCalled());
        await waitFor(() => expect(view.result.current.load.state === 'ready' && view.result.current.load.slots[0].name).toBe('장비 이름 확인 불가 매입'));
        view.unmount();
    });
    it('장수/server/refresh/generation 뒤 늦은 장비 응답을 버린다', async () => {
        let resolveOld!: (value: Awaited<ReturnType<typeof api.legacyDirectOptions>>) => void;
        vi.mocked(readServerCookie).mockReturnValue('alpha');
        vi.mocked(api.reservedCommands).mockImplementation(async id => equipmentRing(id ?? 1));
        vi.mocked(api.legacyDirectOptions).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
            .mockImplementation(async (_, id) => options(`${readServerCookie()} 장수${id} 장비`));
        const view = renderHook(({ id, refresh }) => useTurnSlots(id, refresh), { initialProps: { id: 1, refresh: 0 } });
        await waitFor(() => expect(api.legacyDirectOptions).toHaveBeenCalledTimes(1));
        view.rerender({ id: 1, refresh: 1 });
        await waitFor(() => expect(view.result.current.names.equipment?.[equipmentId]).toBe('alpha 장수1 장비'));
        vi.mocked(readServerCookie).mockReturnValue('beta');
        view.rerender({ id: 2, refresh: 1 });
        expect(view.result.current.names.equipment?.[equipmentId]).toBeUndefined();
        await waitFor(() => expect(view.result.current.names.equipment?.[equipmentId]).toBe('beta 장수2 장비'));
        await act(async () => resolveOld(options('옛 장비')));
        expect(view.result.current.names.equipment?.[equipmentId]).toBe('beta 장수2 장비');
        const oldCalls = vi.mocked(api.legacyDirectOptions).mock.calls.length;
        await act(async () => view.result.current.reload());
        await waitFor(() => expect(vi.mocked(api.legacyDirectOptions).mock.calls.length).toBeGreaterThan(oldCalls));
        expect(view.result.current.names.equipment?.[equipmentId]).toBe('beta 장수2 장비');
        view.unmount();
    });
});
