// 연감 연말 판도 지도(소비 안 K5-WAIT-04): 그해 판(mapPin)이 지금 지도 판과 같을 때만 그해 소유를 칠한다. 지도 그리기(GL)는 가짜.
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { TopdownMap as TopdownMapType } from '@opensamguk/ui/map/topdown';
import { BAKE_PIN, YEARBOOK_200_FULL } from './fixtures/yearbook';

const shared = vi.hoisted(() => ({
    topdown: null as ComponentProps<typeof TopdownMapType> | null,
    places: vi.fn(async () => ({ provinceCount: 4 })),
}));
vi.mock('@opensamguk/ui/map/topdown', async () => {
    const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
    return {
        ...actual,
        loadBakePlaces: shared.places,
        TopdownMap: (props: ComponentProps<typeof TopdownMapType>) => { shared.topdown = props; return <div data-testid="topdown-map" />; },
    };
});

import YearbookMap from '@/components/records/YearbookMap';

const OWNERSHIP = YEARBOOK_200_FULL.ownership!;
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

beforeEach(() => {
    shared.topdown = null;
    shared.places.mockReset();
    shared.places.mockImplementation(async () => ({ provinceCount: 4 }));
    vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
});
afterEach(() => vi.unstubAllEnvs());

describe('연감 연말 판도 지도', () => {
    it('그해 판(mapPin)을 읽어 그해 소유 · 그해 세력 색으로 칠한다(무주 제외), 천하 전체 맞춤', async () => {
        render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        await settle();
        expect(shared.places).toHaveBeenCalledWith(expect.objectContaining({ bakeUrl: `/api/game/api/map/topdown/${BAKE_PIN}` }));
        expect(shared.topdown!.source.bakeUrl).toBe(`/api/game/api/map/topdown/${BAKE_PIN}`);
        expect(shared.topdown!.initialView).toBe('fit');
        expect(shared.topdown!.ariaLabel).toBe('200년 말 판도 지도');
        expect(shared.topdown!.world!.occupancy).toEqual([
            { provinceIndex: 0, nationId: 2 }, { provinceIndex: 1, nationId: 1 }, { provinceIndex: 2, nationId: 0 }, { provinceIndex: 3, nationId: 3 },
        ]);
        expect(shared.topdown!.world!.nations.map((n) => [n.id, n.color])).toEqual([[2, '#b05a4a'], [1, '#4f7fbf'], [3, '#a5744a']]);
    });

    it('구역 수가 판과 다르면 칠하지 않고 사유 — 색 없는 지도를 판도로 내놓지 않는다', async () => {
        shared.places.mockImplementation(async () => ({ provinceCount: 5 }));
        render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        await settle();
        expect(screen.getByText('200년 말 판도를 지도에 칠하지 못했습니다')).toBeInTheDocument();
        expect(screen.getByText(/구역 수가 다릅니다\(서버 4, 지도 5\)/)).toBeInTheDocument();
        expect(screen.queryByTestId('topdown-map')).toBeNull();
    });

    it('장소 표를 못 읽으면 오류 + 다시 시도로 다시 읽는다', async () => {
        shared.places.mockImplementationOnce(async () => { throw new Error('404'); });
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
        render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        await settle();
        expect(screen.getByText('지도 장소를 불러오지 못했습니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        await settle();
        expect(shared.places).toHaveBeenCalledTimes(2);
        expect(screen.getByTestId('topdown-map')).toBeInTheDocument();
        warn.mockRestore();
    });

    it('판 번호가 bake id 가 아니거나 새 지도 스위치가 꺼져 있으면 그리지 않는다고 말한다(지금 지도로 대신하지 않는다)', async () => {
        const { rerender } = render(<YearbookMap year={200} ownership={{ ...OWNERSHIP, mapPin: 'han-v3' }} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        expect(screen.getByText('200년 말 판도 지도를 그릴 수 없습니다')).toBeInTheDocument();
        vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '0');
        rerender(<YearbookMap year={199} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        expect(screen.getByText('199년 말 판도 지도를 그릴 수 없습니다')).toBeInTheDocument();
        expect(shared.places).not.toHaveBeenCalled();
    });

    it('그해 판이 지금 지도 판과 다르면 칠하지 않는다(엉뚱한 구역) — 지금 판을 받는 동안은 기다린다', async () => {
        const { rerender } = render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={undefined} />);
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        expect(screen.queryByText(/그릴 수 없습니다/)).toBeNull();
        rerender(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={'d'.repeat(64)} />);
        expect(screen.getByText('200년 지도 판이 지금과 달라 판도를 그릴 수 없습니다')).toBeInTheDocument();
        rerender(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={null} />);
        expect(screen.getByText('200년 지도 판이 지금과 달라 판도를 그릴 수 없습니다')).toBeInTheDocument();
        await settle();
        expect(shared.places).not.toHaveBeenCalled();
        expect(screen.queryByTestId('topdown-map')).toBeNull();
    });
});


describe('C10 bake guard', () => {
    it('does not draw before validating the selected bake places', async () => {
        let finish!: (value: { provinceCount: number }) => void;
        shared.places.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
        render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        await act(async () => { finish({ provinceCount: 5 }); });
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        expect(screen.getByText(/구역 수가 다릅니다/)).toBeInTheDocument();
    });

    it('does not reuse old places or its late response after the bake changes', async () => {
        let finishOld!: (value: { provinceCount: number }) => void;
        let finishNew!: (value: { provinceCount: number }) => void;
        shared.places
            .mockImplementationOnce(() => new Promise((resolve) => { finishOld = resolve; }))
            .mockImplementationOnce(() => new Promise((resolve) => { finishNew = resolve; }));
        const { rerender } = render(<YearbookMap year={200} ownership={OWNERSHIP} territory={YEARBOOK_200_FULL.territory} currentPin={BAKE_PIN} />);
        const newPin = 'd'.repeat(64);
        rerender(<YearbookMap year={200} ownership={{ ...OWNERSHIP, mapPin: newPin }} territory={YEARBOOK_200_FULL.territory} currentPin={newPin} />);
        await act(async () => { finishOld({ provinceCount: 4 }); });
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        await act(async () => { finishNew({ provinceCount: 5 }); });
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        expect(screen.getByText(/구역 수가 다릅니다/)).toBeInTheDocument();
        expect(shared.places).toHaveBeenLastCalledWith(expect.objectContaining({ bakeUrl: `/api/game/api/map/topdown/${newPin}` }));
    });
});
