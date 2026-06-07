// 컴포넌트 상호작용 테스트 — components/game/MapViewer.tsx.
// MapViewer는 로비 MapPreview와 동일한 "정적" 마커 맵으로 재작성됐다(줌/팬/안개/현재도시 blink 제거).
// 유일한 인터랙션: 도시 마커 클릭 → 해당 도시 정보 페이지(`/game/city?id=<id>`)로 라우팅.
//
// 라이브 백엔드 없이 실행(headless): /api/map/preview 를 global.fetch 모킹으로 대체.
// flagTint(canvas/Image)도 결정적 stub 으로 모킹해 깃발 dataURL 이 즉시 준비되게 한다.
// next/navigation 의 useRouter 도 모킹해 push 호출(라우팅)을 검증한다.
//
// 검증 대상(정적 렌더 + 클릭→도시페이지 이동):
//   1) 정적 렌더 — 줌 컨트롤/줌 레이어가 없고, 모든 도시명이 항상 표시된다.
//   2) 도시 마커 클릭 — router.push 가 해당 도시의 `/game/city?id=<id>` 로 호출된다.
//   3) 오오라 — 소유국만 city-aura 가 렌더되고 공백지엔 없다(MapPreview 패턴).
//   4) 깃발 틴트 — 소유국 색이 flagTint 에 전달되고 마커에 깃발이 렌더된다.
//   5) 미보급(supply-off) — 소유국 미보급 도시는 city-base 에 supply-off 가 붙는다.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { MapPreviewResponse } from '@/lib/types';

// ── flagTint 모킹: 실제 canvas 합성 대신 색을 그대로 식별 가능한 dataURL 4프레임으로 반환. ──
// 어떤 색이 틴트 요청됐는지 기록해 "깃발 틴트가 nation 색으로 적용"을 검증한다.
const tintedColors: string[] = [];
vi.mock('@/lib/flagTint', () => ({
    FLAG_FRAMES: 4,
    tintFlag: vi.fn(async (color: string) => {
        tintedColors.push(color);
        // 색을 dataURL 에 박아 마커 src 로 색을 역추적할 수 있게 한다.
        return Array.from({ length: 4 }, (_, i) => `data:image/png;tint=${color};frame=${i}`);
    }),
}));

// ── next/navigation 모킹: useRouter().push 호출(라우팅)을 캡처. ──
const pushMock = vi.fn();
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: pushMock, replace: vi.fn(), prefetch: vi.fn(), back: vi.fn() }),
}));

import MapViewer from '@/components/game/MapViewer';

// ── 고정 맵 스냅샷(소유국 수도 + 공백지 + 소유국 미보급) ───────────────────────
const NATION_RED = '#ff0000';
const MAP_FIXTURE: MapPreviewResponse = {
    serverName: '테스트섭',
    year: 200,
    month: 5,
    mapCode: 'che',
    width: 700,
    height: 500,
    cities: [
        // 소유국(위) 수도 — 깃발 + 별. supply=true → 흐리지 않음.
        { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true },
        // 공백지 — 깃발/오오라 없음.
        { id: 22, name: '장안', level: 3, nationId: 0, x: 120, y: 120, state: 0, supply: true, isCapital: false },
        // 소유국 미보급(supply=false) — city-base 에 supply-off(opacity 0.6) 적용 검증용.
        { id: 33, name: '허창', level: 6, nationId: 1, x: 500, y: 300, state: 0, supply: false, isCapital: false },
    ],
    nations: [{ id: 1, name: '위', color: NATION_RED }],
};

// fetch 모킹 — 맵 프리뷰 응답. 라이브 스택 0.
function mockFetch() {
    return vi.fn(async (url: string | URL | Request) => {
        const path = typeof url === 'string' ? url : url.toString();
        if (path.includes('/api/map/preview')) {
            return jsonResponse(MAP_FIXTURE);
        }
        // 알 수 없는 경로 → 404(컴포넌트가 graceful 처리).
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

beforeEach(() => {
    tintedColors.length = 0;
    pushMock.mockReset();
    vi.stubGlobal('fetch', mockFetch());
    // 캔버스 폭 추적 — 외곽 스케일(canvasW)이 0 으로 죽지 않게 clientWidth 를 700 으로 고정.
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
            return this.classList?.contains('map-viewer-canvas') ? 700 : 0;
        },
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
});

async function renderAndLoad() {
    const utils = render(<MapViewer />);
    // 맵 데이터 로드 완료 → 캔버스가 그려질 때까지 대기.
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
        // lv3 공백지(장안)도 도시명이 보인다 — 옛 줌 조건(lv>=5)이 제거됨.
        expect(screen.getByText('낙양')).toBeInTheDocument();
        expect(screen.getByText('장안')).toBeInTheDocument();
        expect(screen.getByText('허창')).toBeInTheDocument();
    });
});

describe('MapViewer — 도시 마커 클릭 → 도시 정보 페이지 라우팅', () => {
    it('도시 클릭 시 router.push 가 `/game/city?id=<id>` 로 호출된다', async () => {
        await renderAndLoad();
        // 낙양(id 11) 마커 버튼 — aria-label 로 찾는다.
        const cityBtn = screen.getByRole('button', { name: /낙양 레벨 8 위/ });
        fireEvent.click(cityBtn);
        expect(pushMock).toHaveBeenCalledTimes(1);
        expect(pushMock).toHaveBeenCalledWith('/game/city?id=11');
    });

    it('공백지(장안 id 22) 클릭도 해당 id 로 라우팅된다', async () => {
        await renderAndLoad();
        const cityBtn = screen.getByRole('button', { name: /장안 레벨 3 공 백 지/ });
        fireEvent.click(cityBtn);
        expect(pushMock).toHaveBeenCalledWith('/game/city?id=22');
    });
});

describe('MapViewer — 오오라(소유국만)', () => {
    it('소유국 마커에는 city-aura 가 있고 공백지에는 없다', async () => {
        await renderAndLoad();
        // 소유국(낙양) base 안에는 오오라.
        const ownedBase = screen.getByRole('button', { name: /낙양 레벨 8 위/ });
        expect(ownedBase.querySelector('.city-aura')).toBeTruthy();
        // 공백지(장안) base 안에는 오오라 없음(회색 중립 오오라 미렌더).
        const neutralBase = screen.getByRole('button', { name: /장안 레벨 3 공 백 지/ });
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
        // 수도(별) 아이콘도 소유국 깃발 안에 렌더.
        expect(document.querySelector('.city-capital')).toBeTruthy();
    });

    it('공백지 마커에는 깃발 이미지가 없다', async () => {
        await renderAndLoad();
        const neutralBase = screen.getByRole('button', { name: /장안 레벨 3 공 백 지/ });
        expect(neutralBase.querySelector('.city-flag-img')).toBeNull();
    });
});

describe('MapViewer — 미보급(supply-off) 도시 흐리게', () => {
    it('소유국 미보급(supply=false)은 city-base 에 supply-off 가 붙고, 보급(supply=true)은 붙지 않는다', async () => {
        await renderAndLoad();
        // 허창(id 33, 소유국·미보급) — city-base 에 supply-off.
        const unsuppliedBase = screen.getByRole('button', { name: /허창 레벨 6 위/ });
        expect(unsuppliedBase.className).toContain('supply-off');
        // 낙양(id 11, 소유국·보급) — supply-off 없음.
        const suppliedBase = screen.getByRole('button', { name: /낙양 레벨 8 위/ });
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
