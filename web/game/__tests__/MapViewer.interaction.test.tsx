// 컴포넌트 상호작용 테스트 — components/game/MapViewer.tsx.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import type { MapPreviewResponse } from '@/lib/types';

const tintedColors: string[] = [];
vi.mock('@/lib/flagTint', () => ({
    FLAG_FRAMES: 4,
    tintFlag: vi.fn(async (color: string) => {
        tintedColors.push(color);
        return Array.from({ length: 4 }, (_, i) => `data:image/png;tint=${color};frame=${i}`);
    }),
}));

import MapViewer from '@/components/game/MapViewer';

const NATION_RED = '#ff0000';
const MAP_FIXTURE: MapPreviewResponse = {
    serverName: '테스트섭',
    year: 200,
    month: 5,
    mapCode: 'che',
    width: 700,
    height: 500,
    cities: [
        { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true },
        { id: 22, name: '장안', level: 3, nationId: 0, x: 120, y: 120, state: 0, supply: true, isCapital: false },
        { id: 33, name: '허창', level: 6, nationId: 1, x: 500, y: 300, state: 0, supply: false, isCapital: false },
    ],
    nations: [{ id: 1, name: '위', color: NATION_RED }],
};

function mockFetch() {
    return vi.fn(async (url: string | URL | Request) => {
        const path = typeof url === 'string' ? url : url.toString();
        if (path.includes('/api/map/preview')) {
            return jsonResponse(MAP_FIXTURE);
        }
        return new Response('not found', { status: 404, statusText: 'Not Found' });
    });
}
function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

function getCanvas(): HTMLElement {
    return document.querySelector('.map-viewer-canvas') as HTMLElement;
}

function setServerCookie(serverId: string) {
    document.cookie = `sam_server=${serverId}; path=/`;
}

function clearServerCookie() {
    document.cookie = 'sam_server=; Max-Age=0; path=/';
}

beforeEach(() => {
    clearServerCookie();
    tintedColors.length = 0;
    vi.stubGlobal('fetch', mockFetch());
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
            return this.classList?.contains('map-viewer-canvas') ? 700 : 0;
        },
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
    clearServerCookie();
});

async function renderAndLoad() {
    const utils = render(<MapViewer />);
    await waitFor(() => expect(getCanvas()).toBeTruthy());
    return utils;
}

describe('MapViewer — 정적 렌더(로비 MapPreview와 동일)', () => {
    it('줌 컨트롤/줌 레이어가 없다(정적)', async () => {
        await renderAndLoad();
        expect(document.querySelector('.map-zoomlayer')).toBeNull();
        expect(document.querySelector('.map-controls')).toBeNull();
        expect(screen.queryByRole('button', { name: '지도 초기화' })).toBeNull();
    });

    it('모든 도시명이 항상 표시된다(줌 조건 제거)', async () => {
        await renderAndLoad();
        expect(screen.getByText('낙양')).toBeInTheDocument();
        expect(screen.getByText('장안')).toBeInTheDocument();
        expect(screen.getByText('허창')).toBeInTheDocument();
    });
});

describe('MapViewer — 도시 마커 클릭 → 도시 정보 페이지 라우팅', () => {
    it('도시 마커가 `/game/city?id=<id>` 링크를 가진다', async () => {
        await renderAndLoad();
        const cityLink = screen.getByRole('link', { name: /낙양 레벨 8 위/ });
        expect(cityLink).toHaveAttribute('href', '/game/city?id=11');
    });

    it('공백지(장안 id 22)도 해당 id 링크를 가진다', async () => {
        await renderAndLoad();
        const cityLink = screen.getByRole('link', { name: /장안 레벨 3 공 백 지/ });
        expect(cityLink).toHaveAttribute('href', '/game/city?id=22');
    });

    it('선택 서버 쿠키가 있으면 도시 클릭 URL에 서버 경로를 보존한다', async () => {
        setServerCookie('s1');
        await renderAndLoad();
        const cityLink = screen.getByRole('link', { name: /낙양 레벨 8 위/ });
        expect(cityLink).toHaveAttribute('href', '/game/s1/city?id=11');
    });
});

describe('MapViewer — 오오라(소유국만)', () => {
    it('소유국 마커에는 city-aura 가 있고 공백지에는 없다', async () => {
        await renderAndLoad();
        const ownedBase = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(ownedBase.querySelector('.city-aura')).toBeTruthy();
        const neutralBase = screen.getByLabelText(/장안 레벨 3 공 백 지/);
        expect(neutralBase.querySelector('.city-aura')).toBeNull();
    });
});

describe('MapViewer — 깃발 틴트(nation 색)', () => {
    it('소유국 색(#ff0000)으로 flagTint 가 호출되고 공백지 색은 틴트하지 않는다', async () => {
        await renderAndLoad();
        await waitFor(() => expect(tintedColors).toContain(NATION_RED));
        expect(tintedColors).not.toContain('#555555'); // NEUTRAL_COLOR
    });

    it('소유국 마커에 nation 색이 박힌 깃발 이미지가 city-img 안에 렌더된다', async () => {
        await renderAndLoad();
        await waitFor(() => {
            const flag = document.querySelector('.city-img .city-flag-img') as HTMLImageElement | null;
            expect(flag).toBeTruthy();
            expect(flag!.getAttribute('src')).toContain(`tint=${NATION_RED}`);
        });
        expect(document.querySelector('.city-capital')).toBeTruthy();
    });

    it('공백지 마커에는 깃발 이미지가 없다', async () => {
        await renderAndLoad();
        const neutralBase = screen.getByLabelText(/장안 레벨 3 공 백 지/);
        expect(neutralBase.querySelector('.city-flag-img')).toBeNull();
    });
});

describe('MapViewer — 미보급(supply-off) 도시 흐리게', () => {
    it('소유국 미보급(supply=false)은 city-base 에 supply-off 가 붙고, 보급(supply=true)은 붙지 않는다', async () => {
        await renderAndLoad();
        const unsuppliedBase = screen.getByLabelText(/허창 레벨 6 위/);
        expect(unsuppliedBase.className).toContain('supply-off');
        const suppliedBase = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(suppliedBase.className).not.toContain('supply-off');
    });
});

describe('MapViewer — graceful 상태', () => {
    it('빈 월드(cities=[])는 placeholder 를 렌더한다(크래시 없음)', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(async () =>
                jsonResponse({ ...MAP_FIXTURE, cities: [], nations: [] } as MapPreviewResponse),
            ),
        );
        render(<MapViewer />);
        expect(await screen.findByText('지도 데이터 준비 중입니다.')).toBeInTheDocument();
    });

    it('preview fetch 실패 시 placeholder 를 렌더한다', async () => {
        vi.stubGlobal('fetch', vi.fn(async () => new Response('err', { status: 500, statusText: 'err' })));
        render(<MapViewer />);
        expect(await screen.findByText('지도 데이터 준비 중입니다.')).toBeInTheDocument();
    });
});
