// 새 장수 만들기(P-E02) 본관 지도 — 새 지도(교체 스위치 + bakeId)면 지도 표지로도 고르고, 목록과 같은 picker 를 쓴다.
// 지도 그리기(GL)는 가짜, 표지 층(MapTargetLayer)은 진짜. 지도 칸 600×420 으로 잰다.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ComponentProps } from 'react';
import type { TopdownMap as TopdownMapType, TopdownMapHandle } from '@opensamguk/ui/map/topdown';
import { OPTIONS } from './fixtures/creation';

const shared = vi.hoisted(() => ({
    topdown: null as ComponentProps<typeof TopdownMapType> | null,
    centerOn: vi.fn(),
    viewport: 'desktop' as string | null,
}));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn() }),
    usePathname: () => '/game/pep/create',
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', refresh: vi.fn() }) }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => shared.viewport,
}));
vi.mock('@opensamguk/ui/map/topdown', async () => {
    const actual = await vi.importActual<typeof import('@opensamguk/ui/map/topdown')>('@opensamguk/ui/map/topdown');
    return { ...actual,
        loadBakePlaces: async () => ({ provinceCount: 2 }),
        TopdownMap: (props: ComponentProps<typeof TopdownMapType>) => {
            shared.topdown = props;
            props.onReady?.({ centerOn: shared.centerOn, setLevel: vi.fn(), zoomStep: vi.fn(), focusCity: vi.fn(() => true) } as TopdownMapHandle);
            return <div data-testid="topdown-map" />;
        },
    };
});

import CreateScreen from '@/components/entry/CreateScreen';

const PREVIEW = {
    mapCode: 'han-world-v3', cities: [], nations: [{ id: 1, name: '조조', color: '#4f7fbf' }],
    provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 }],
    topdownBakeId: 'b'.repeat(64),
};
/**
 * 고를 수 없는데 칸은 있는 현(영음현)을 더한다 — 지도 표지에서 사유를 연다.
 * 결손현(704)은 서버가 칸을 못 맞춘 城(cell 없음 · available=false · 사유 코드 없음, C9 「본관 셀 안전 수」 결손 17곳의 꼴)이다.
 */
const WITH_BLOCKED = {
    ...OPTIONS,
    nativeCounties: [...OPTIONS.nativeCounties,
        { cityId: 13, name: '영음현', commanderyId: 'yingchuan', commanderyName: '영천군', provinceName: '예주', cell: { col: 121, row: 81 }, available: false, reason: 'INVALID_NATIVE_COUNTY' },
        { cityId: 704, name: '결손현', commanderyId: 'yingchuan', commanderyName: '영천군', provinceName: '예주', cell: null, available: false, reason: null }],
};

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, () => Response>;
let asked: string[];

beforeEach(() => {
    shared.topdown = null;
    shared.centerOn.mockReset();
    shared.viewport = 'desktop';
    asked = [];
    vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '1');
    routes = {
        '/api/game/api/generals/creation/options': () => json(200, WITH_BLOCKED),
        '/api/game/api/map/preview': () => json(200, PREVIEW),
    };
    vi.stubGlobal('fetch', vi.fn(async (input: string) => {
        const path = new URL(input, 'http://x').pathname;
        asked.push(path);
        return routes[path]?.() ?? json(404, {});
    }));
    // 지도 칸(그리고 표지 층) 크기 — jsdom 은 0 을 준다
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(
        { x: 0, y: 0, left: 0, top: 0, right: 600, bottom: 420, width: 600, height: 420, toJSON: () => ({}) } as DOMRect);
});
afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

const list = () => screen.getByRole('listbox', { name: '본관 현 후보' });
/** 지도가 군 보기(배율 4)로 (col,row) 를 가운데 두었다고 알린다(TopdownMap onViewChange). */
function view(col: number, row: number, zoom = 4) {
    act(() => shared.topdown!.onViewChange!({ camera: { center: { col, row }, zoom }, level: zoom < 2 ? 'ju' : zoom < 8 ? 'commandery' : 'county' }));
}
async function open() {
    render(<CreateScreen />);
    await screen.findByTestId('topdown-map');
}

describe('본관 지도', () => {
    it('주 보기에선 표지 없이 안내만, 확대하면 화면 안 후보에 표지 — 표지로 고르면 목록도 고른다(지도는 그대로)', async () => {
        await open();
        expect(screen.getByText('지도를 확대하거나 주 · 군을 고르면 현 표지가 나옵니다.')).toBeInTheDocument();
        expect(document.querySelector('[data-map-targets]')).toBeNull();
        view(120, 80, 8);
        // 배율 8(600×420 → 75×52칸): 허현(120,80) · 장사현(118,76) · 영음현(121,81)은 화면 안, 업현(130,40)은 화면 밖, 마피영은 칸이 없다
        const markers = await waitFor(() => {
            const found = [...document.querySelectorAll<HTMLButtonElement>('[data-map-targets] [data-target-id]')].map((b) => b.getAttribute('aria-label'));
            expect(found).toHaveLength(3);
            return found;
        });
        expect(markers).toEqual(['허현 — 고를 수 있음', '장사현 — 고를 수 있음', '영음현 — 고를 수 없음 — 누르면 이유']);
        expect(screen.queryByText('지도를 확대하거나 주 · 군을 고르면 현 표지가 나옵니다.')).toBeNull();

        fireEvent.click(screen.getByRole('button', { name: '허현 — 고를 수 있음' }));
        expect(within(list()).getByRole('option', { name: /허현/ })).toHaveAttribute('aria-selected', 'true');
        expect(screen.getByRole('button', { name: '허현 — 고른 곳' })).toHaveAttribute('aria-pressed', 'true');
        expect(shared.topdown!.selectedCityId).toBe(11);
        // 누른 표지는 이미 화면 안 — 지도가 움직이지 않는다
        expect(shared.centerOn).not.toHaveBeenCalled();
    });

    it('목록에서 화면 밖 현을 고르면 그 현으로 옮긴다(군 보기 배율 이상 유지)', async () => {
        await open();
        view(120, 80, 8);
        fireEvent.click(within(list()).getByRole('option', { name: /업현/ }));
        expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 130.5, row: 40.5 }, 8);
        expect(shared.topdown!.selectedCityId).toBe(21);
    });

    it('주 · 군을 거르면 그 후보들 가운데로 군 보기 — 주 보기(천하 전체)에서도', async () => {
        await open();
        fireEvent.change(screen.getByRole('combobox', { name: '주' }), { target: { value: '기주' } });
        expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 130.5, row: 40.5 }, 4);
        fireEvent.change(screen.getByRole('combobox', { name: '주' }), { target: { value: '예주' } });
        fireEvent.change(screen.getByRole('combobox', { name: '군 · 국' }), { target: { value: '영천군' } });
        // 영천군 칸 있는 현 셋(허현 120.5,80.5 · 장사현 118.5,76.5 · 영음현 121.5,81.5)의 평균
        const [centre, zoom] = shared.centerOn.mock.lastCall!;
        expect(centre.col).toBeCloseTo(360.5 / 3);
        expect(centre.row).toBeCloseTo(79.5);
        expect(zoom).toBe(4);
    });

    it('고를 수 없는 표지를 누르면 고르지 않고 목록과 같은 사유를 보인다', async () => {
        await open();
        view(120, 80);
        fireEvent.click(await screen.findByRole('button', { name: '영음현 — 고를 수 없음 — 누르면 이유' }));
        expect(screen.getByText('영음현 — 시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.')).toHaveAttribute('role', 'status');
        expect(within(list()).getByRole('option', { name: /영음현/ })).not.toHaveAttribute('aria-selected', 'true');
        expect(shared.topdown!.selectedCityId).toBeNull();
    });

    it('주를 바꾼 뒤 이름 · 능력을 바꿔도 지도는 그 주에 머문다 — 이미 고른 현으로 되돌아가지 않는다(#1348 리뷰)', async () => {
        await open();
        fireEvent.click(within(list()).getByRole('option', { name: /허현/ }));
        expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 120.5, row: 80.5 }, 4);
        fireEvent.change(screen.getByRole('combobox', { name: '주' }), { target: { value: '기주' } });
        expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 130.5, row: 40.5 }, 4);
        const afterFilter = shared.centerOn.mock.calls.length;
        fireEvent.change(screen.getByRole('textbox', { name: '이름' }), { target: { value: '하후연' } });
        fireEvent.click(screen.getByRole('button', { name: '무력 내리기' }));
        // 그 뒤로 허현(120.5,80.5)으로 옮긴 적이 없다
        const later = shared.centerOn.mock.calls.slice(afterFilter).map(([cell]) => cell as { col: number; row: number });
        expect(later.filter((cell) => cell.col === 120.5 && cell.row === 80.5)).toEqual([]);
        expect(shared.centerOn).toHaveBeenLastCalledWith({ col: 130.5, row: 40.5 }, 4);
    });

    it('못 고르는 표지의 사유 줄은 다른 현을 제대로 고르면 지운다', async () => {
        await open();
        view(120, 80, 8);
        fireEvent.click(await screen.findByRole('button', { name: '영음현 — 고를 수 없음 — 누르면 이유' }));
        expect(screen.getByText('영음현 — 시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '허현 — 고를 수 있음' }));
        expect(screen.queryByText('영음현 — 시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.')).toBeNull();
    });

    it('칸 없는 결손 현(cell 없음 · 사유 코드 없음)은 지도 표지 없이 목록에 「못 고름 + 사유」 — 다른 칸으로 대신하지 않는다', async () => {
        await open();
        view(120, 80, 8);
        const option = within(list()).getByRole('option', { name: /결손현/ });
        expect(option).toHaveAttribute('aria-disabled', 'true');
        expect(document.getElementById(option.getAttribute('aria-describedby')!.split(' ').pop()!)).toHaveTextContent('지금 고를 수 없습니다.');
        await waitFor(() => expect(document.querySelectorAll('[data-map-targets] [data-target-id]').length).toBe(3));
        expect(document.querySelector('[data-map-targets] [data-target-id="704"]')).toBeNull();
    });

    it('모바일 — 걸음을 오가도 지도 미리보기는 한 번만 읽는다', async () => {
        shared.viewport = 'mobile';
        render(<CreateScreen />);
        fireEvent.click(await screen.findByRole('button', { name: '다음 — 본관' }));
        await screen.findByTestId('topdown-map');
        fireEvent.click(screen.getByRole('button', { name: '다음 — 능력' }));
        fireEvent.click(screen.getByRole('button', { name: '이전 — 본관' }));
        await screen.findByTestId('topdown-map');
        expect(asked.filter((path) => path === '/api/game/api/map/preview')).toHaveLength(1);
    });

    it('옮겨 가는 동안: 옛 납작한 모양(cellCol · cellRow)으로 와도 표지가 그려지고, 칸이 빠진 현은 표지 · NaN 없이 목록에만', async () => {
        const flat = WITH_BLOCKED.nativeCounties.map(({ cell, ...rest }, i) => (i === 0
            ? rest // 허현: 칸 정보가 아예 없다
            : { ...rest, cellCol: cell?.col ?? null, cellRow: cell?.row ?? null }));
        routes['/api/game/api/generals/creation/options'] = () => json(200, { ...WITH_BLOCKED, nativeCounties: flat });
        await open();
        view(120, 80, 8);
        const markers = await waitFor(() => {
            const found = [...document.querySelectorAll<HTMLButtonElement>('[data-map-targets] [data-target-id]')].map((b) => b.getAttribute('aria-label'));
            expect(found).toHaveLength(2);
            return found;
        });
        expect(markers).toEqual(['장사현 — 고를 수 있음', '영음현 — 고를 수 없음 — 누르면 이유']);
        // 칸 없는 허현을 목록에서 골라도 지도를 NaN 으로 옮기지 않는다
        fireEvent.click(within(list()).getByRole('option', { name: /허현/ }));
        for (const [cell] of shared.centerOn.mock.calls) expect(Number.isFinite(cell.col) && Number.isFinite(cell.row)).toBe(true);
    });

    it('bakeId 가 없으면 지도 없이 목록만', async () => {
        routes['/api/game/api/map/preview'] = () => json(200, { ...PREVIEW, topdownBakeId: undefined });
        render(<CreateScreen />);
        expect(await screen.findByText(/지도 없이 목록에서 고릅니다/)).toBeInTheDocument();
        expect(screen.queryByTestId('topdown-map')).toBeNull();
        expect(within(list()).getAllByRole('option').length).toBeGreaterThan(0);
    });

    it('교체 스위치가 꺼지면 미리보기를 청하지도 않는다', async () => {
        vi.stubEnv('NEXT_PUBLIC_TOPDOWN_SCREENS', '');
        render(<CreateScreen />);
        expect(await screen.findByText(/지도 없이 목록에서 고릅니다/)).toBeInTheDocument();
        expect(asked).not.toContain('/api/game/api/map/preview');
    });
});
