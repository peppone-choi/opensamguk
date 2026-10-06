// 봉신 계약 상세의 봉토 지도 — K2 #1400 읽기 전용 표지 · 칸 여럿 첫 보기. 지도 그리기(GL)와 표지 층은 가짜로 받은 값만 본다.
import { render, screen, waitFor } from '@testing-library/react';
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { MapTargetLayer as MapTargetLayerType, TopdownMap as TopdownMapType } from '@opensamguk/ui/map/topdown';

const shared = vi.hoisted(() => ({
    topdown: null as ComponentProps<typeof TopdownMapType> | null,
    layer: null as ComponentProps<typeof MapTargetLayerType> | null,
    places: vi.fn<() => Promise<unknown>>(),
}));

vi.mock('@opensamguk/ui/map/topdown', async () => {
    const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
    return { ...actual,
        loadBakePlaces: () => shared.places(),
        // 발자국 가운데는 소수 칸이다(진짜 cityCell 처럼) — 화면이 내림해서 넘기는지 본다
        cityCell: (_places: unknown, cityId: number) => (cityId === 99 ? null : { col: cityId * 10 + 1.5, row: cityId * 10 + 2.5 }),
        TopdownMap: (props: ComponentProps<typeof TopdownMapType>) => {
            shared.topdown = props;
            return <div data-testid="topdown-map" />;
        },
        MapTargetLayer: (props: ComponentProps<typeof MapTargetLayerType>) => {
            shared.layer = props;
            return <div data-testid="target-layer" />;
        },
    };
});

import FiefMap from '@/components/offices/FiefMap';

const PREVIEW = {
    mapCode: 'han-world-v3', cities: [], nations: [{ id: 1, name: '조조', color: '#4f7fbf' }],
    provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 1 }],
    topdownBakeId: 'b'.repeat(64),
};
const NAMES: Record<number, string> = { 3: '번창현', 5: '임영현' };
const countyName = (id: number) => NAMES[id] ?? null;

function show(props: Partial<ComponentProps<typeof FiefMap>> = {}) {
    return render(<FiefMap preview={PREVIEW as never} countyIds={[5, 3]} countyName={countyName} label="조인" {...props} />);
}

beforeEach(() => {
    shared.topdown = null;
    shared.layer = null;
    shared.places.mockReset();
    shared.places.mockResolvedValue({ provinceCount: 1 });
    vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
});
afterEach(() => {
    vi.unstubAllEnvs();
});

describe('FiefMap', () => {
    it('봉토 현을 계약 차례대로 읽기 전용 표지로 강조하고, 첫 보기는 그 칸들을 다 담는다(정수 칸)', async () => {
        show();
        await screen.findByTestId('topdown-map');
        expect(shared.topdown!.initialView).toEqual({ cells: [{ col: 51, row: 52 }, { col: 31, row: 32 }] });
        expect(shared.topdown!.ariaLabel).toBe('조인 — 봉토 지도');
        expect(shared.topdown!.onSelect).toBeUndefined();
        const layer = shared.layer as Extract<ComponentProps<typeof MapTargetLayerType>, { readOnly: true }>;
        expect(layer.readOnly).toBe(true);
        expect(layer.marked).toEqual(['5', '3']);
        expect(layer.candidates.map((c) => [c.targetId, c.name, c.cell, c.available]))
            .toEqual([['5', '임영현', { col: 51, row: 52 }, true], ['3', '번창현', { col: 31, row: 32 }, true]]);
        expect('picker' in layer).toBe(false);
        // 카메라는 지도가 알려 준 것을 표지 층에 그대로 넘긴다
        expect(layer.camera).toBeNull();
        const camera = { center: { col: 40, row: 40 }, zoom: 16 };
        act(() => shared.topdown!.onViewChange!({ camera, level: 'county' } as never));
        expect(shared.layer!.camera).toBe(camera);
    });

    it('지도에 없는 현은 표지 없이 건너뛰고, 하나도 없으면 지도 대신 한 줄', async () => {
        const { unmount } = show({ countyIds: [99, 3] });
        await screen.findByTestId('topdown-map');
        expect(shared.layer!.candidates.map((c) => c.targetId)).toEqual(['3']);
        unmount();
        shared.topdown = null;
        show({ countyIds: [99] });
        expect(await screen.findByText('봉토 현이 이 지도에 없습니다.')).toBeInTheDocument();
        expect(shared.topdown).toBeNull();
    });

    it('bakeId 가 없거나 교체 스위치가 꺼졌거나 봉토가 없으면 아무것도 그리지 않는다(이름 줄만 남는다)', () => {
        for (const props of [{ preview: { ...PREVIEW, topdownBakeId: undefined } as never }, { countyIds: [] as number[] }]) {
            const { container, unmount } = show(props);
            expect(container).toBeEmptyDOMElement();
            unmount();
        }
        vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '');
        const { container } = show();
        expect(container).toBeEmptyDOMElement();
        expect(shared.places).not.toHaveBeenCalled();
    });

    it('장소 표를 못 받으면 지도 대신 알림 한 줄(빈 지도를 그리지 않는다)', async () => {
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        shared.places.mockRejectedValue(new Error('404'));
        show();
        expect(await screen.findByRole('alert')).toHaveTextContent('봉토 지도의 장소를 불러오지 못했습니다.');
        expect(shared.topdown).toBeNull();
        await waitFor(() => expect(warn).toHaveBeenCalled());
        warn.mockRestore();
    });
});
