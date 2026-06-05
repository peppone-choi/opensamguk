// 컴포넌트 상호작용 테스트 — components/game/MapViewer.tsx.
// 라이브 백엔드 없이 실행(headless): /api/map/preview 와 per-city /api/city/{id} 를 global.fetch 모킹으로 대체.
// flagTint(canvas/Image)도 결정적 stub 으로 모킹해 깃발 dataURL 이 즉시 준비되게 한다.
//
// 검증 대상(사용자 명시 줌/팬/클릭선택/깃발틴트):
//   1) 휠 줌 — scale(view.k) 가 0.5x~4x 한계 안에서 변한다(줌인/줌아웃/클램프).
//   2) 드래그 팬 — 줌 레이어 transform 의 translate 가 포인터 이동만큼 바뀐다.
//   3) 도시 마커 클릭 — 선택 상태(is-selected ring)가 켜지고 MapCityDetail 패널이 열린다.
//   4) 깃발 틴트 — 소유국 색이 flagTint 에 전달되고 마커에 깃발이 렌더된다.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { MapPreviewResponse, CityDetailResponse } from '@/lib/types';

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

import MapViewer from '@/components/game/MapViewer';

// ── 고정 맵 스냅샷(2 도시: 소유국 1 + 공백지 1) + 도시 상세 ───────────────────
const NATION_RED = '#ff0000';
const MAP_FIXTURE: MapPreviewResponse = {
    serverName: '테스트섭',
    year: 200,
    month: 5,
    mapCode: 'che',
    width: 700,
    height: 500,
    cities: [
        // 소유국(위 국가) 수도 — 큰 레벨이라 도시명 항상 표시 + 깃발 + 별. supply=true → 흐리지 않음.
        { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true },
        // 공백지 — 깃발 없음.
        { id: 22, name: '장안', level: 3, nationId: 0, x: 120, y: 120, state: 0, supply: true, isCapital: false },
        // 소유국 미보급(supply=false) — city-base 에 supply-off(opacity 0.6) 적용 검증용.
        { id: 33, name: '허창', level: 6, nationId: 1, x: 500, y: 300, state: 0, supply: false, isCapital: false },
    ],
    nations: [{ id: 1, name: '위', color: NATION_RED }],
};

const CITY_DETAIL_11: CityDetailResponse = {
    id: 11,
    name: '낙양',
    level: 8,
    region: 0,
    nationId: 1,
    population: 100000,
    populationMax: 200000,
    agriculture: 500,
    agricultureMax: 1000,
    commerce: 400,
    commerceMax: 1000,
    security: 80,
    securityMax: 100,
    defense: 300,
    defenseMax: 500,
    wall: 600,
    wallMax: 800,
    trust: 75,
    trade: 110,
    supplyState: 0,
    frontState: 0,
    officers: 3,
};

// fetch 모킹 — 경로별로 맵/도시 상세 응답. 라이브 스택 0.
function mockFetch() {
    return vi.fn(async (url: string | URL | Request) => {
        const path = typeof url === 'string' ? url : url.toString();
        if (path.includes('/api/map/preview')) {
            return jsonResponse(MAP_FIXTURE);
        }
        if (path.includes('/api/city/11')) {
            return jsonResponse(CITY_DETAIL_11);
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

// 줌 레이어 transform 의 scale(k) 값을 파싱.
function readScale(zoomLayer: HTMLElement): number {
    const m = /scale\(([-0-9.]+)\)/.exec(zoomLayer.style.transform);
    return m ? parseFloat(m[1]) : NaN;
}
// 줌 레이어 transform 의 translate(tx,ty)를 파싱.
function readTranslate(zoomLayer: HTMLElement): { tx: number; ty: number } {
    const m = /translate\(([-0-9.]+)px,\s*([-0-9.]+)px\)/.exec(zoomLayer.style.transform);
    return m ? { tx: parseFloat(m[1]), ty: parseFloat(m[2]) } : { tx: NaN, ty: NaN };
}

function getCanvas(): HTMLElement {
    return document.querySelector('.map-viewer-canvas') as HTMLElement;
}
function getZoomLayer(): HTMLElement {
    return document.querySelector('.map-zoomlayer') as HTMLElement;
}

beforeEach(() => {
    tintedColors.length = 0;
    vi.stubGlobal('fetch', mockFetch());
    // 캔버스 폭 추적 — mapScale() 이 0 으로 죽지 않게 clientWidth 를 700 으로 고정.
    Object.defineProperty(HTMLElement.prototype, 'clientWidth', {
        configurable: true,
        get() {
            return this.classList?.contains('map-viewer-canvas') ? 700 : 0;
        },
    });
    // getBoundingClientRect — 휠 커서 좌표 계산용(원점 0,0 / 700×500).
    HTMLElement.prototype.getBoundingClientRect = function () {
        return { left: 0, top: 0, right: 700, bottom: 500, width: 700, height: 500, x: 0, y: 0, toJSON() {} } as DOMRect;
    };
});

afterEach(() => {
    vi.unstubAllGlobals();
});

async function renderAndLoad() {
    const utils = render(<MapViewer currentCityId={11} />);
    // 맵 데이터 로드 완료 → 캔버스가 그려질 때까지 대기.
    await waitFor(() => expect(getCanvas()).toBeTruthy());
    return utils;
}

describe('MapViewer — 휠 줌(0.5x~4x 클램프)', () => {
    it('초기 줌은 1x(scale=1)이고 100% 로 표시된다', async () => {
        await renderAndLoad();
        expect(readScale(getZoomLayer())).toBeCloseTo(1, 5);
        expect(screen.getByText('100%')).toBeInTheDocument();
    });

    it('휠 업(deltaY<0)은 줌인 하되 4x 를 넘지 않는다', async () => {
        await renderAndLoad();
        const canvas = getCanvas();
        // 한 번 줌인 → 1.15x.
        fireEvent.wheel(canvas, { deltaY: -100, clientX: 350, clientY: 250 });
        expect(readScale(getZoomLayer())).toBeCloseTo(1.15, 5);
        // 강하게 여러 번 줌인 → 상한 4x 에 클램프.
        for (let i = 0; i < 30; i++) {
            fireEvent.wheel(canvas, { deltaY: -100, clientX: 350, clientY: 250 });
        }
        expect(readScale(getZoomLayer())).toBeLessThanOrEqual(4);
        expect(readScale(getZoomLayer())).toBeCloseTo(4, 5);
    });

    it('휠 다운(deltaY>0)은 줌아웃 하되 0.5x 미만으로 내려가지 않는다', async () => {
        await renderAndLoad();
        const canvas = getCanvas();
        for (let i = 0; i < 30; i++) {
            fireEvent.wheel(canvas, { deltaY: 100, clientX: 350, clientY: 250 });
        }
        const k = readScale(getZoomLayer());
        expect(k).toBeGreaterThanOrEqual(0.5);
        expect(k).toBeCloseTo(0.5, 5);
    });

    it('전체 보기 버튼은 줌/팬을 1x·원점으로 리셋한다', async () => {
        await renderAndLoad();
        const canvas = getCanvas();
        fireEvent.wheel(canvas, { deltaY: -100, clientX: 350, clientY: 250 });
        expect(readScale(getZoomLayer())).not.toBeCloseTo(1, 5);
        fireEvent.click(screen.getByRole('button', { name: '지도 초기화' }));
        const zl = getZoomLayer();
        expect(readScale(zl)).toBeCloseTo(1, 5);
        expect(readTranslate(zl)).toEqual({ tx: 0, ty: 0 });
    });
});

describe('MapViewer — 드래그 팬', () => {
    it('임계(3px) 초과 드래그는 줌 레이어를 translate 만큼 이동시킨다', async () => {
        await renderAndLoad();
        const canvas = getCanvas();
        const before = readTranslate(getZoomLayer());
        expect(before).toEqual({ tx: 0, ty: 0 });

        // 포인터 다운 → 이동(임계 초과) → 업.
        fireEvent.pointerDown(canvas, { button: 0, clientX: 100, clientY: 100, pointerId: 1 });
        fireEvent.pointerMove(canvas, { clientX: 160, clientY: 140, pointerId: 1 });
        const after = readTranslate(getZoomLayer());
        // clientWidth/width = 700/700 = 1 → 화면 px == 맵 px. dx=60, dy=40.
        expect(after.tx).toBeCloseTo(60, 5);
        expect(after.ty).toBeCloseTo(40, 5);
        fireEvent.pointerUp(canvas, { pointerId: 1 });
    });

    it('임계 미만(2px)의 미세 이동은 팬으로 보지 않는다(클릭 보존)', async () => {
        await renderAndLoad();
        const canvas = getCanvas();
        fireEvent.pointerDown(canvas, { button: 0, clientX: 100, clientY: 100, pointerId: 1 });
        fireEvent.pointerMove(canvas, { clientX: 101, clientY: 101, pointerId: 1 });
        const after = readTranslate(getZoomLayer());
        expect(after).toEqual({ tx: 0, ty: 0 });
        fireEvent.pointerUp(canvas, { pointerId: 1 });
    });
});

describe('MapViewer — 도시 마커 클릭 선택 + 상세 패널', () => {
    it('도시 클릭 시 선택 ring(is-selected)이 켜지고 MapCityDetail 이 열린다', async () => {
        await renderAndLoad();
        // 낙양(id 11) 마커 버튼 — aria-label 로 찾는다.
        const cityBtn = screen.getByRole('button', { name: /낙양 레벨 8 위/ });
        expect(cityBtn.className).not.toContain('is-selected');

        fireEvent.click(cityBtn);

        // 선택 ring.
        await waitFor(() => expect(cityBtn.className).toContain('is-selected'));

        // 상세 패널(MapCityDetail) 헤더 — 【특】 낙양 (lv8 → "특").
        const panel = await screen.findByLabelText('낙양 도시 정보');
        expect(within(panel).getByText(/【특】 낙양/)).toBeInTheDocument();
        // per-city /api/city/11 모킹 응답이 반영돼 지표(주민)가 렌더.
        await waitFor(() => expect(within(panel).getByText('주민')).toBeInTheDocument());
        // 지배 국가 패널.
        expect(within(panel).getByText(/지배 국가 【 위 】/)).toBeInTheDocument();
    });

    it('onSelectCity 콜백이 클릭된 도시로 호출된다', async () => {
        const onSelect = vi.fn();
        render(<MapViewer currentCityId={11} onSelectCity={onSelect} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        fireEvent.click(screen.getByRole('button', { name: /낙양 레벨 8 위/ }));
        await waitFor(() => expect(onSelect).toHaveBeenCalledTimes(1));
        expect(onSelect.mock.calls[0][0]).toMatchObject({ id: 11, name: '낙양' });
    });

    it('공백지(nationId=0)는 상세에서 "공 백 지"로 표시된다', async () => {
        await renderAndLoad();
        // 장안(id 22, 공백지). lv3 → 줌 1x 에선 도시명 미표시지만 aria-label 로 마커 식별.
        const cityBtn = screen.getByRole('button', { name: /장안 레벨 3 공 백 지/ });
        fireEvent.click(cityBtn);
        const panel = await screen.findByLabelText('장안 도시 정보');
        expect(within(panel).getByText('공 백 지')).toBeInTheDocument();
    });
});

describe('MapViewer — 깃발 틴트(nation 색)', () => {
    it('소유국 색(#ff0000)으로 flagTint 가 호출되고 공백지 색은 틴트하지 않는다', async () => {
        await renderAndLoad();
        // 소유국 색만 틴트 요청 — 공백지(중립색)는 제외.
        await waitFor(() => expect(tintedColors).toContain(NATION_RED));
        expect(tintedColors).not.toContain('#555555'); // NEUTRAL_COLOR
    });

    it('소유국 마커에 nation 색이 박힌 깃발 이미지가 렌더된다', async () => {
        await renderAndLoad();
        // 깃발 img src 에 tint=#ff0000 이 들어간다(모킹 dataURL).
        await waitFor(() => {
            const flag = document.querySelector('.city-flag-img') as HTMLImageElement | null;
            expect(flag).toBeTruthy();
            expect(flag!.getAttribute('src')).toContain(`tint=${NATION_RED}`);
        });
        // 수도(별) 아이콘도 소유국 깃발 안에 렌더.
        expect(document.querySelector('.city-capital')).toBeTruthy();
    });

    it('공백지 마커에는 깃발 이미지가 없다', async () => {
        await renderAndLoad();
        // 장안 마커 영역 안에는 city-flag-img 가 없어야(공백지=깃발 없음).
        const cityBtn = screen.getByRole('button', { name: /장안 레벨 3 공 백 지/ });
        const base = cityBtn.closest('.city-base') as HTMLElement;
        expect(base.querySelector('.city-flag-img')).toBeNull();
    });
});

describe('MapViewer — 미보급(supply-off) 도시 흐리게', () => {
    it('소유국 미보급(supply=false)은 city-base 에 supply-off 가 붙고, 보급(supply=true)은 붙지 않는다', async () => {
        await renderAndLoad();
        // 허창(id 33, 소유국·미보급) — city-base 에 supply-off.
        const unsuppliedBtn = screen.getByRole('button', { name: /허창 레벨 6 위/ });
        const unsuppliedBase = unsuppliedBtn.closest('.city-base') as HTMLElement;
        expect(unsuppliedBase).toBeTruthy();
        expect(unsuppliedBase.className).toContain('supply-off');

        // 낙양(id 11, 소유국·보급) — supply-off 없음.
        const suppliedBtn = screen.getByRole('button', { name: /낙양 레벨 8 위/ });
        const suppliedBase = suppliedBtn.closest('.city-base') as HTMLElement;
        expect(suppliedBase).toBeTruthy();
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
