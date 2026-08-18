// W0-6 MapViewer prop widen 테스트 — components/game/MapViewer.tsx.
// 감사 PAGE_PARITY_AUDIT_2026-06-10.md §5 W0-6: mapData/disallowClick/currentCityId/live·showMe 확장
// + P1-061 계절 경계 공식 교정 + P0-36 FE측(state<=5 캡 해제) + P1-062 도시명 표기 토글.
//
// 레거시 정본(hwe/ts):
//   - mapData 주입: MapViewer.vue:241-244(required prop — Vue 뷰어는 self-fetch 없음),
//     PageHistory.vue:23-33 / PageCachedMap.vue:5-16(:map-data + :disallow-click="true"),
//     PageFront.vue:37-49(:map-data + :disallow-click="false").
//   - disallowClick: MapViewer.vue:225 prop → 392-394 clickable=0.
//   - currentCityId(내 도시): MapViewer.vue:62,76 is-my-city → MapCityDetail.vue:34 .my_city blink.
//   - live/showMe: PageFront.vue:516-529 GetMap({neutralView:0, showMe:1}) + refreshCounter 재조회,
//     func_map.php:78-95(showMe → myCity 포함).
//   - 계절: MapViewer.vue:306-319 — month<=3 봄 / <=6 여름 / <=9 가을 / 나머지 겨울.
//   - state 아이콘: MapCityDetail.vue:44 `v-if="city.state > 0"` — 상한 캡 없음(코드 6~9 포함).
//   - 도시명 토글: MapViewer.vue:30-40 + state/mapViewer.ts(localStorage 'sam.hideMapCityName').
import { readFileSync } from 'node:fs';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { MapPreviewResponse, WorldMapResponse } from '@/lib/types';

// ── flagTint 모킹: canvas 합성 대신 결정적 dataURL 4프레임. ──
vi.mock('@/lib/flagTint', () => ({
    FLAG_FRAMES: 4,
    tintFlag: vi.fn(async (color: string) =>
        Array.from({ length: 4 }, (_, i) => `data:image/png;tint=${color};frame=${i}`),
    ),
}));

import MapViewer, { mapTitleColor, mapTitleTooltip, seasonOf } from '@/components/game/MapViewer';

const NATION_RED = '#ff0000';
const NATION_BLUE = '#0000ff';

// 프리뷰(캐시) 픽스처 — 위(1) 소유 낙양(수도)/허창 + 공백지 장안(state=6: P0-36 코드 6~9 검증).
const MAP_FIXTURE: MapPreviewResponse = {
    serverName: '테스트섭',
    year: 200,
    month: 5,
    turnPhase: 1,
    turnPhaseText: '상순',
    mapCode: 'che',
    width: 700,
    height: 500,
    cities: [
        { id: 11, name: '낙양', level: 8, nationId: 1, x: 300, y: 250, state: 0, supply: true, isCapital: true },
        { id: 22, name: '장안', level: 3, nationId: 0, x: 120, y: 120, state: 6, supply: true, isCapital: false },
        { id: 33, name: '허창', level: 6, nationId: 1, x: 500, y: 300, state: 0, supply: false, isCapital: false },
    ],
    nations: [{ id: 1, name: '위', color: NATION_RED }],
};

// 라이브(GetMap 패러티 /api/map) 픽스처 — 낙양이 오(2) 소유로 바뀌고 내 도시=허창(33).
// cityList tuple = [city, level, state, nation, region, supply] / nationList tuple = [id, name, color, capital].
const WM_FIXTURE: WorldMapResponse = {
    result: true,
    version: 4,
    startYear: 180,
    year: 201,
    month: 7,
    turnPhase: 3,
    turnPhaseText: '하순',
    cityList: [
        [11, 8, 0, 2, 0, 1],
        [22, 3, 0, 0, 0, 1],
        [33, 6, 0, 1, 0, 0],
    ],
    nationList: [
        [1, '위', NATION_RED, 33],
        [2, '오', NATION_BLUE, 11],
    ],
    spyList: {},
    shownByGeneralList: [],
    myCity: 33,
    myNation: 1,
};

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (reason?: unknown) => void;
    const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
    });
    return { promise, resolve, reject };
}

// fetch 모킹 — 호출 경로를 기록해 self-fetch 생략/라이브 재조회를 검증한다.
const fetchedPaths: string[] = [];
function mockFetch() {
    return vi.fn(async (url: string | URL | Request) => {
        const path = typeof url === 'string' ? url : url.toString();
        fetchedPaths.push(path);
        if (path.includes('/api/map/preview')) return jsonResponse(MAP_FIXTURE);
        if (path.includes('/api/map?')) return jsonResponse(WM_FIXTURE);
        return new Response('not found', { status: 404, statusText: 'Not Found' });
    });
}

function getCanvas(): HTMLElement {
    return document.querySelector('.map-viewer-canvas') as HTMLElement;
}

beforeEach(() => {
    fetchedPaths.length = 0;
    window.localStorage.clear();
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
});

describe('seasonOf — P1-061 계절 경계(레거시 MapViewer.vue:306-319 <=3/<=6/<=9)', () => {
    it('1·2·3월=봄, 4~6월=여름, 7~9월=가을, 10~12월=겨울', () => {
        expect(seasonOf(1)).toBe('spring');
        expect(seasonOf(3)).toBe('spring'); // 경계 — 옛 공식(3-5월 봄)과 같은 값이지만 4월부터 갈린다.
        expect(seasonOf(4)).toBe('summer');
        expect(seasonOf(6)).toBe('summer'); // 경계 — 옛 공식은 6월을 summer로 두지만 7월이 갈린다.
        expect(seasonOf(7)).toBe('fall');
        expect(seasonOf(9)).toBe('fall');
        expect(seasonOf(10)).toBe('winter');
        expect(seasonOf(12)).toBe('winter');
    });
});

describe('MapViewer — 레거시 박스 구조(map_title + 700x500 map_body)', () => {
    it('연월 제목을 캔버스 위에 두고 하단 캡션을 만들지 않는다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());

        const section = document.querySelector('.map-viewer') as HTMLElement;
        expect(section.firstElementChild).toHaveClass('map-viewer-title');
        expect(section.firstElementChild).toHaveTextContent('200년 5월 상순');
        expect(section.children[1]).toHaveClass('map-viewer-canvas');
        expect(section.querySelector('.map-viewer-cap')).toBeNull();
    });

    it('초반 3년 제목 색상은 legacy MapViewer.vue getTitleColor 와 같다', async () => {
        expect(mapTitleColor(200, 200)).toBe('magenta');
        expect(mapTitleColor(200, 201)).toBe('orange');
        expect(mapTitleColor(200, 202)).toBe('yellow');
        expect(mapTitleColor(200, 203)).toBeUndefined();
    });

    it('연월 제목 툴팁은 36턴 초반제한과 기술등급 제한을 노출한다', async () => {
        const gameConst = { maxTechLevel: 12, initialAllowedTechLevel: 1, techLevelIncYear: 5, openingLimitTurns: 36 };
        expect(mapTitleTooltip(200, 200, 5, 1, gameConst)).toBe(
            '초반제한 기간 : 8개월 (201년 1월 상순 해제)\n기술등급 제한 : 1등급 (205년 해제)',
        );

        render(<MapViewer mapData={{ ...MAP_FIXTURE, startYear: 200 }} gameConst={gameConst} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());

        const title = screen.getByText('200년 5월 상순');
        expect(title).toHaveAttribute(
            'title',
            '초반제한 기간 : 8개월 (201년 1월 상순 해제)\n기술등급 제한 : 1등급 (205년 해제)',
        );
    });
});

describe('MapViewer — mapData 주입(P0-22)', () => {
    it('mapData 주입 시 self-fetch 를 생략하고 주입 데이터를 렌더한다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        expect(screen.getByText('낙양')).toBeInTheDocument();
        expect(fetchedPaths).toHaveLength(0); // 네트워크 0
    });

    it('mapData 주입 기본값은 클릭 비활성(레거시 PageHistory disallow-click 패턴)', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const cityMarker = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(cityMarker).not.toHaveAttribute('href');
        expect(cityMarker).toHaveAttribute('aria-disabled', 'true');
    });

    it('mapData 주입 + disallowClick={false} 면 클릭이 살아있다(레거시 PageFront 패턴)', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} disallowClick={false} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        expect(screen.getByRole('link', { name: /낙양 레벨 8 위/ })).toHaveAttribute('href', '/game/city?id=11');
    });

    it('단순 지도는 레거시 basicMapCitySize를 사용하고 상세 지도와 크기를 섞지 않는다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} isDetailMap={false} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());

        const icon = document.querySelector('.city-img') as HTMLElement;
        expect(icon.style.width).toBe('24px');
        expect(icon.style.height).toBe('18px');
    });
});

describe('MapViewer — disallowClick(레거시 MapViewer.vue:392-394 clickable=0)', () => {
    it('self-fetch 모드에서도 disallowClick 이면 클릭이 라우팅하지 않는다', async () => {
        render(<MapViewer disallowClick />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const cityMarker = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(cityMarker).not.toHaveAttribute('href');
        expect(cityMarker).toHaveAttribute('aria-disabled', 'true');
    });
});

describe('MapViewer — currentCityId(레거시 is-my-city → .my_city blink)', () => {
    it('currentCityId 도시의 filler에 my-city 클래스가 붙는다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} currentCityId={33} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const mine = screen.getByLabelText(/허창 레벨 6 위/);
        expect(mine.querySelector('.city-filler.my-city')).toBeTruthy();
        expect(mine.querySelector('.city-img.my-city')).toBeNull();
        const other = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(other.querySelector('.my-city')).toBeNull();
    });
});

describe('MapViewer CSS — legacy hover tooltip and my-city marker', () => {
    const css = readFileSync('app/globals.css', 'utf8');

    it('hover tooltip은 카드형 UI가 아니라 legacy city_tooltip 형태다', () => {
        const block = css.match(/\.map-tooltip\s*\{[^}]+\}/s)?.[0] ?? '';
        expect(block).toContain('min-width: 120px');
        expect(block).toContain('border: 1px solid gray');
        expect(block).toContain('border-radius: 0');
        expect(block).toContain('box-shadow: none');
        expect(block).not.toContain('var(--shadow-md)');
        expect(css).toMatch(/\.map-tooltip-name,\s*\.map-tooltip-meta\s*\{[^}]*background-color: rgb\(30, 164, 255\)/s);
    });

    it('내 도시 filler 자체도 legacy my_city처럼 둥근 링 기준을 가진다', () => {
        expect(css).toMatch(/\.city-filler\.my-city\s*\{\s*border-radius: 33%;\s*\}/);
    });
});

describe('MapViewer — live/showMe(P1-003, GetMap neutralView:0 showMe:1 패러티)', () => {
    it('live 면 /api/map?neutralView=0&showMe=1 을 추가 조회해 소유/연월을 라이브로 머지한다', async () => {
        render(<MapViewer live />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        expect(fetchedPaths.some((p) => p.includes('/api/map?neutralView=0&showMe=1'))).toBe(true);
        // 낙양 소유가 라이브 기준 오(2)로 바뀐다(프리뷰 캐시는 위(1)).
        expect(screen.getByRole('link', { name: /낙양 레벨 8 오/ })).toBeTruthy();
        expect(screen.getByText(/201년 7월/)).toBeInTheDocument();
    });

    it('live + showMe=1 이면 myCity(허창)가 my-city 마커를 받는다(func_map.php:78-95)', async () => {
        render(<MapViewer live />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const mine = screen.getByLabelText(/허창 레벨 6 위/);
        expect(mine.querySelector('.city-filler.my-city')).toBeTruthy();
    });

    it('refreshKey 변경 시 재조회한다(레거시 refreshCounter watch)', async () => {
        const { rerender } = render(<MapViewer live refreshKey={0} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const before = fetchedPaths.filter((p) => p.includes('/api/map?')).length;
        rerender(<MapViewer live refreshKey={1} />);
        await waitFor(() => {
            const after = fetchedPaths.filter((p) => p.includes('/api/map?')).length;
            expect(after).toBeGreaterThan(before);
        });
    });

    it('refreshKey 재조회 중에는 기존 지도를 유지한다(레거시 map.value 교체 시점 패리티)', async () => {
        const pendingPreview = deferred<Response>();
        let previewCalls = 0;
        vi.stubGlobal(
            'fetch',
            vi.fn(async (url: string | URL | Request) => {
                const path = typeof url === 'string' ? url : url.toString();
                fetchedPaths.push(path);
                if (path.includes('/api/map/preview')) {
                    previewCalls += 1;
                    if (previewCalls === 1) return jsonResponse(MAP_FIXTURE);
                    return pendingPreview.promise;
                }
                if (path.includes('/api/map?')) return jsonResponse(WM_FIXTURE);
                return new Response('not found', { status: 404, statusText: 'Not Found' });
            }),
        );

        const { rerender } = render(<MapViewer live refreshKey={0} />);
        await waitFor(() => expect(screen.getByText(/201년 7월/)).toBeInTheDocument());

        rerender(<MapViewer live refreshKey={1} />);
        await waitFor(() => expect(previewCalls).toBe(2));

        expect(screen.getByText(/201년 7월/)).toBeInTheDocument();
        expect(document.querySelector('.map-viewer-ph')).toBeNull();

        pendingPreview.resolve(jsonResponse(MAP_FIXTURE));
    });
});

describe('MapViewer — 도시 아이콘 출처(자작 에셋, tools/assets/build_city_icons.py)', () => {
    it('성 아이콘이 CDN gif 가 아니라 로컬 자작 png `/city/cast_<lv>.png` 를 가리킨다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const nakyang = screen.getByLabelText(/낙양 레벨 8 위/);
        const cast = nakyang.querySelector('.city-cast') as HTMLImageElement;
        expect(cast.getAttribute('src')).toBe('/city/cast_8.png');
    });
});

describe('MapViewer — P0-36 FE측 state 아이콘(레거시 MapCityDetail.vue:44 state>0, 캡 없음)', () => {
    it('state=6(코드 6~9)도 상태 아이콘이 렌더된다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const jangan = screen.getByLabelText(/장안 레벨 3/);
        const stateImg = jangan.querySelector('.city-state') as HTMLImageElement | null;
        expect(stateImg).toBeTruthy();
        expect(stateImg!.getAttribute('src')).toContain('event6.gif');
        expect(stateImg!.getAttribute('width')).toBe('8');
        expect(stateImg!.getAttribute('height')).toBe('8');
    });

    it('state=0 은 상태 아이콘이 없다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const nakyang = screen.getByLabelText(/낙양 레벨 8 위/);
        expect(nakyang.querySelector('.city-state')).toBeNull();
    });
});

describe('MapViewer — 도시명 표기 토글(P1-062, 레거시 MapViewer.vue:30-40 + sam.hideMapCityName)', () => {
    it('토글 클릭 시 hide-cityname 클래스와 localStorage 가 함께 바뀐다', async () => {
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const section = document.querySelector('.map-viewer') as HTMLElement;
        expect(section.classList.contains('hide-cityname')).toBe(false);
        fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
        expect(section.classList.contains('hide-cityname')).toBe(true);
        expect(window.localStorage.getItem('sam.hideMapCityName')).toBe('yes');
        fireEvent.click(screen.getByRole('button', { name: '도시명 표기' }));
        expect(section.classList.contains('hide-cityname')).toBe(false);
        expect(window.localStorage.getItem('sam.hideMapCityName')).toBe('no');
    });

    it('localStorage 에 yes 가 저장돼 있으면 초기부터 숨김(레거시 state/mapViewer.ts)', async () => {
        window.localStorage.setItem('sam.hideMapCityName', 'yes');
        render(<MapViewer mapData={MAP_FIXTURE} />);
        await waitFor(() => expect(getCanvas()).toBeTruthy());
        const section = document.querySelector('.map-viewer') as HTMLElement;
        expect(section.classList.contains('hide-cityname')).toBe(true);
    });
});
