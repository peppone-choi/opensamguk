import { act, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { HitResult } from '@opensamguk/ui/map/topdown';

// 선택 카드(P-W01 2단계, K4) — 지도가 고르기를 틀에 넘긴다. 렌더러(캔버스)는 흉내: 누른 것(onSelect) · 내 위치 표지(onPick)만 부른다.
const td = vi.hoisted(() => ({
    onSelect: null as ((hit: HitResult) => void) | null,
    selected: undefined as number | null | undefined,
    onMe: undefined as (() => void) | undefined,
}));
vi.mock('@opensamguk/ui/map/topdown', async () => {
    const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
    return {
        ...actual,
        loadBakeProvinceCenters: async () => [],
        // bake 장소 표: 城 2(진류)는 관(passes, M2-12)
        loadBakePlaces: async () => ({ provinceCount: 2, passes: [{ cityId: 2, orientation: 'EW', gateCells: [], wallCells: [] }] }),
        bakeCommanderyAnchors: () => [],
        cityCell: () => ({ col: 10, row: 10 }),
        worldFromPreview: () => ({ ok: true, world: {} }),
        TopdownMap: (props: { onSelect?: (hit: HitResult) => void; selectedCityId?: number | null }) => {
            td.onSelect = props.onSelect ?? null;
            td.selected = props.selectedCityId;
            return <div data-testid="topdown" />;
        },
        MyLocationLayer: (props: { onPick?: () => void }) => { td.onMe = props.onPick; return null; },
        MapLayerButtons: () => null,
        MapViewBar: () => null,
    };
});
// 새 지도면 훅은 미리보기에서 멈춘다(kind 'preview' — 옛 지형 없음, #1231). 고르기도 미리보기만으로 행 · 세력 · 구역 id 를 싣는다.
vi.mock('@/lib/campaign-map', () => ({ CAMPAIGN_MAP_CODE: 'han-world-v3',
    useCampaignWorldMap: () => ({ kind: 'preview', legend: [],
        preview: {
            cities: [{ id: 1, name: '선무', nationId: 1, commanderyName: '하남윤', provinceId: 1, supply: true, isCapital: false },
                { id: 2, name: '진류', nationId: 2, commanderyName: '진류군', supply: true, isCapital: false }],
            nations: [{ id: 1, name: '위', color: '#b03a2e' }, { id: 2, name: '원소', color: '#4f7fbf' }],
            topdownBakeId: 'b'.repeat(64),
            provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'B', provinceIndex: 1, nationId: 1 }] } }) }));
import WarRoomMap from '@/components/campaign/WarRoomMap';

const me = { name: '하후돈', nationColor: '#b03a2e' };
const hit = (kind: HitResult['kind'], id: HitResult['id']): HitResult => ({ kind, id, cell: { col: 1, row: 1 } });

describe('작전실 지도 고르기를 틀이 쥔다(onPick)', () => {
    it('城 · 깃발 → 그 城 + 미리보기 행 · 세력 · 구역 id, 내 위치 표지 → 내 城(me), 빈 땅 · 구역 · Esc → null, 「고른 곳」 글줄 없음', async () => {
        const onPick = vi.fn();
        const { rerender } = render(<WarRoomMap fill homeCityId={1} visibility={null} myGeneral={me} pickedCityId={null} onPick={onPick} />);
        await waitFor(() => expect(td.onSelect).not.toBeNull());
        await waitFor(() => expect(td.onMe).toBeDefined());
        act(() => td.onSelect!(hit('city', 2)));
        expect(onPick).toHaveBeenLastCalledWith(expect.objectContaining({ cityId: 2, me: false, provinceRecordId: null,
            city: expect.objectContaining({ name: '진류' }), nations: expect.arrayContaining([expect.objectContaining({ name: '원소' })]) }));
        act(() => td.onSelect!(hit('flag', '1')));
        expect(onPick).toHaveBeenLastCalledWith(expect.objectContaining({ cityId: 1, me: false, provinceRecordId: 'B' }));
        act(() => td.onMe!());
        expect(onPick).toHaveBeenLastCalledWith(expect.objectContaining({ cityId: 1, me: true }));
        for (const kind of ['none', 'province'] as const) {
            act(() => td.onSelect!(hit(kind, kind === 'province' ? 0 : null)));
            expect(onPick).toHaveBeenLastCalledWith(null);
        }
        // 미리보기에 없는 城은 고르지 않는다
        act(() => td.onSelect!(hit('city', 99)));
        expect(onPick).toHaveBeenLastCalledWith(null);
        // 노란 테두리는 틀이 넘긴 城을 따른다 — 누르기만으로는 바뀌지 않는다
        expect(td.selected).toBeNull();
        rerender(<WarRoomMap fill homeCityId={1} visibility={null} myGeneral={me} pickedCityId={2} onPick={onPick} />);
        expect(td.selected).toBe(2);
        expect(screen.queryByTestId('war-room-picked')).toBeNull();
    });

    it('관(bake passes)을 고르면 pass 칸을 싣는다 — 선택 카드 「관」 칩(K4), 관이 아닌 城 · 내 위치에는 없다', async () => {
        const onPick = vi.fn();
        render(<WarRoomMap fill homeCityId={1} visibility={null} myGeneral={me} pickedCityId={null} onPick={onPick} />);
        await waitFor(() => expect(td.onSelect).not.toBeNull());
        // 장소 표는 늦게 온다 — 온 뒤 고른 관에 pass 가 붙는다
        await waitFor(() => {
            act(() => td.onSelect!(hit('city', 2)));
            expect(onPick).toHaveBeenLastCalledWith(expect.objectContaining({ cityId: 2, pass: { cityId: 2 } }));
        });
        act(() => td.onSelect!(hit('flag', '1')));
        expect(onPick.mock.lastCall?.[0]).toMatchObject({ cityId: 1 });
        expect(onPick.mock.lastCall?.[0]).not.toHaveProperty('pass');
        act(() => td.onMe!());
        expect(onPick.mock.lastCall?.[0]).not.toHaveProperty('pass');
    });

    it('onPick 이 없으면 지금 그대로 — 지도가 스스로 고르고 「고른 곳」 글줄로 알린다', async () => {
        render(<WarRoomMap fill homeCityId={1} visibility={null} myGeneral={me} />);
        await waitFor(() => expect(td.onSelect).not.toBeNull());
        act(() => td.onSelect!(hit('city', 2)));
        expect(await screen.findByTestId('war-room-picked')).toHaveTextContent('진류군 진류');
        expect(td.selected).toBe(2);
        act(() => td.onMe!());
        expect(screen.getByTestId('war-room-picked')).toHaveTextContent('내 위치 — 하남윤 선무');
        act(() => td.onSelect!(hit('none', null)));
        expect(screen.queryByTestId('war-room-picked')).toBeNull();
    });
});
