import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
    buildIsoScene, cityFallbackHitBox, cityMarkerDrawBox, cityMarkerHitBox, cityMarkerRadius,
    cellToScreen, cityMarkerZoomStep, expandOwner, fitScale, flagClothPoints, initialView, labelledRegions,
    labelZoomFor, maxScaleForDpr, seatLabel,
    TIER2_LABEL_ZOOM, TIER2_MARKER_ZOOM, tierZoom,
    type CountyAdministrativeIndex, type HanTiles, type IsoSceneOptions, type ProvinceIdentityMap,
} from '@opensamguk/ui';

const hanTiles: HanTiles = JSON.parse(
    readFileSync(resolve(__dirname, '../../../data/map/han-tiles.json'), 'utf8'),
);
const grid = { cols: hanTiles._meta.cols, rows: hanTiles._meta.rows };
const MIN_MARKER_K = Math.min(...Object.values(TIER2_MARKER_ZOOM));

describe('지도 아이콘 배율과 앵커', () => {
    it('바다에 놓인 도시 기준점은 가장 가까운 같은 군의 육지 셀로 옮긴다', () => {
        const provinces = new Int16Array(15).fill(-1);
        provinces[1 * 5 + 2] = 0; // 더 가까워도 다른 군인 육지
        provinces[0 * 5 + 2] = 1; // 같은 군에서 가장 가까운 육지
        const provinceMap: ProvinceIdentityMap = {
            width: 5,
            height: 3,
            provinces,
            commanderies: new Int16Array(15),
            provinceEdges: [],
            commanderyEdges: [],
        };
        const countyIndex: CountyAdministrativeIndex = {
            commanderyByProvince: new Int16Array([1, 0]),
            commanderyByName: new Map([['A군', 0]]),
            administrativeSystemByProvince: ['', ''],
        };
        const tiles = {
            _meta: { cols: 5, rows: 3, year: 220, terrainLegend: {} },
            terrain: [], owner: [], juns: [], adjacency: { county: [], commandery: [] }, regions: [], cities: [],
        } satisfies HanTiles;
        const options = {
            markerPlacement: { provinceMap, countyIndex },
        } as IsoSceneOptions & {
            markerPlacement: { provinceMap: ProvinceIdentityMap; countyIndex: CountyAdministrativeIndex };
        };

        const scene = buildIsoScene(
            tiles,
            [{ id: 1, name: '해안현', level: 5, nationId: 1, x: 40, y: 40, commanderyName: 'A군' }],
            { width: 100, height: 60 },
            options,
        );

        expect({ col: scene.cities[0].col, row: scene.cities[0].row }).toEqual({ col: 2, row: 0 });
    });

    it('미리 계산한 기준점을 사용해 선택 상태 변경 시 지형을 다시 탐색하지 않는다', () => {
        const tiles = {
            _meta: { cols: 5, rows: 3, year: 220, terrainLegend: {} },
            terrain: [], owner: [], juns: [], adjacency: { county: [], commandery: [] }, regions: [], cities: [],
        } satisfies HanTiles;
        const options = {
            markerPositions: new Map([[1, { col: 2, row: 0 }]]),
        } as IsoSceneOptions & { markerPositions: ReadonlyMap<number, { col: number; row: number }> };

        const scene = buildIsoScene(
            tiles,
            [{ id: 1, name: '해안현', level: 5, nationId: 1, x: 40, y: 40, commanderyName: 'A군' }],
            { width: 100, height: 60 },
            options,
        );

        expect({ col: scene.cities[0].col, row: scene.cities[0].row }).toEqual({ col: 2, row: 0 });
    });

    it('이미 육지인 소수 좌표와 역사 좌표는 그대로 보존한다', () => {
        const provinces = new Int16Array(15).fill(-1);
        provinces[2 * 5 + 2] = 0;
        const provinceMap: ProvinceIdentityMap = {
            width: 5,
            height: 3,
            provinces,
            commanderies: new Int16Array(15),
            provinceEdges: [],
            commanderyEdges: [],
        };
        const countyIndex: CountyAdministrativeIndex = {
            commanderyByProvince: new Int16Array([0]),
            commanderyByName: new Map([['A군', 0]]),
            administrativeSystemByProvince: [''],
        };
        const tiles = {
            _meta: { cols: 5, rows: 3, year: 220, terrainLegend: {} },
            terrain: [], owner: [], juns: [], adjacency: { county: [], commandery: [] }, regions: [], cities: [],
        } satisfies HanTiles;
        const city = { id: 1, name: '내륙현', level: 5, nationId: 1, x: 30, y: 30, commanderyName: 'A군' };
        const scene = buildIsoScene(tiles, [city], { width: 100, height: 60 }, {
            markerPlacement: { provinceMap, countyIndex },
        });

        expect({ col: scene.cities[0].col, row: scene.cities[0].row }).toEqual({ col: 1.5, row: 1.5 });
        expect({ x: scene.cities[0].x, y: scene.cities[0].y }).toEqual({ x: 30, y: 30 });
    });

    it.each([1, 1.5, 2, 3])('DPR %s에서 CSS 크기와 지점 앵커를 보존한다', (dpr) => {
        const box = cityMarkerDrawBox(5, 100, 80, dpr);
        const scale = dpr / 2;

        expect(box.width / dpr).toBe(32);
        expect(box.height / dpr).toBe(32);
        expect(box.x + 32 * scale).toBe(100);
        expect(box.y + 63 * scale).toBe(80);
    });

    it('군치 소는 영현보다, 영현은 장현보다 큰 실루엣을 쓴다', () => {
        expect(cityMarkerDrawBox(5, 0, 0, 1).visualExtent).toBeGreaterThan(
            cityMarkerDrawBox(10, 0, 0, 1).visualExtent,
        );
        expect(cityMarkerDrawBox(10, 0, 0, 1).visualExtent).toBeGreaterThan(
            cityMarkerDrawBox(11, 0, 0, 1).visualExtent,
        );
    });

    it('히트 영역이 위쪽 포인터를 포함한 전체 마커를 덮는다', () => {
        const draw = cityMarkerDrawBox(5, 100, 80, 2);
        const hit = cityMarkerHitBox(5, 100, 80, 2);

        expect(hit.left).toBeLessThan(draw.x);
        expect(hit.top).toBeLessThan(draw.y);
        expect(hit.right).toBeGreaterThan(draw.x + draw.width);
        expect(hit.bottom).toBeGreaterThan(draw.y + draw.height);
    });

    it('fallback 마커와 히트 영역은 DPR에 맞춰 같은 비율로 커진다', () => {
        const radius1 = cityMarkerRadius(5, 1);
        const radius2 = cityMarkerRadius(5, 2);
        const hit1 = cityFallbackHitBox(100, 80, radius1);
        const hit2 = cityFallbackHitBox(200, 160, radius2);

        expect(radius2).toBe(radius1 * 2);
        expect(hit2.right - hit2.left).toBe((hit1.right - hit1.left) * 2);
        expect(hit2.bottom - hit2.top).toBe((hit1.bottom - hit1.top) * 2);
    });

    it('지도 확대에 맞춰 마커와 히트 영역이 단계적으로 함께 커진다', () => {
        expect(cityMarkerZoomStep(8, 2)).toBe(1);
        expect(cityMarkerZoomStep(20, 2)).toBe(1.5);
        expect(cityMarkerZoomStep(32, 2)).toBe(2);

        const normal = cityMarkerDrawBox(5, 100, 80, 2, 1);
        const zoomed = cityMarkerDrawBox(5, 100, 80, 2, 2);
        const normalHit = cityMarkerHitBox(5, 100, 80, 2, 1);
        const zoomedHit = cityMarkerHitBox(5, 100, 80, 2, 2);
        expect(zoomed.width).toBe(normal.width * 2);
        expect(zoomed.height).toBe(normal.height * 2);
        expect(zoomedHit.right - zoomedHit.left).toBeGreaterThan(normalHit.right - normalHit.left);
        expect(zoomedHit.bottom - zoomedHit.top).toBeGreaterThan(normalHit.bottom - normalHit.top);
    });
});

describe('보급 깃발 형태', () => {
    it('보급 중인 깃발은 3프레임으로 펀럭이고 보급 단절 깃발은 항상 축 늘어진다', () => {
        const waving = [0, 1, 2].map((phase) => flagClothPoints(100, 80, 20, true, phase));
        expect(new Set(waving.map((points) => JSON.stringify(points))).size).toBe(3);

        const drooped = [0, 1, 2].map((phase) => flagClothPoints(100, 80, 20, false, phase));
        expect(new Set(drooped.map((points) => JSON.stringify(points))).size).toBe(1);
        expect(drooped[0][2][1]).toBeGreaterThan(waving[0][2][1]);
    });
});

describe('HanMapCanvas 격자 해제', () => {
    it('런렝스를 셀 배열로 되돌린다', () => {
        expect(Array.from(expandOwner([[-1, 2], [7, 3]], 5))).toEqual([-1, -1, 7, 7, 7]);
    });

    it('격자 크기를 넘는 런렝스는 잘라 낸다 — 손상된 파일이 배열을 늘리지 못한다', () => {
        expect(expandOwner([[1, 999]], 4)).toHaveLength(4);
    });

    it('작은 지역은 라벨을 달지 않는다 — 겹쳐 읽히지 않게', () => {
        const r = (name: string, cells: number) =>
            ({ name, nameCh: name, en: name, cls: 'Range/mtn', col: 1, row: 1, cells });
        expect(labelledRegions([r('太行山', 500), r('조각', 3)]).map((x) => x.name)).toEqual(['太行山']);
    });

    it('성 이름은 治所 縣 이름에서 縣을 뗀다 — 郡 이름이 아니다', () => {
        expect(seatLabel('낙양현')).toBe('낙양');
        expect(seatLabel('회현')).toBe('회');
        expect(seatLabel('현')).toBe('현');
        expect(seatLabel('요동군')).toBe('요동군');
    });
});

describe('등급 → 최소 표시 zoom 매핑', () => {
    it('마커 문턱은 fit 배수(K)를 그대로 돌려준다', () => {
        const fit = 1.0035;
        expect(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)).toBeCloseTo(TIER2_MARKER_ZOOM.COUNTY * fit, 6);
        expect(tierZoom(TIER2_MARKER_ZOOM, 'MARQUISATE', fit)).toBeCloseTo(TIER2_MARKER_ZOOM.MARQUISATE * fit, 6);
    });

    it('1급과 테이블에 없는 등급은 undefined다', () => {
        expect(tierZoom(TIER2_MARKER_ZOOM, 'COMMANDERY', 1)).toBeUndefined();
        expect(tierZoom(TIER2_MARKER_ZOOM, 'KINGDOM', 1)).toBeUndefined();
        expect(tierZoom(TIER2_MARKER_ZOOM, 'PROVINCE', 1)).toBeUndefined();
        expect(labelZoomFor('KINGDOM', 1)).toBeUndefined();
    });

    it('마커 문턱은 모든 화면에서 fitScale보다 높다', () => {
        for (const fit of [0.01, 0.5575, 1.0035, 2.676, 100]) {
            expect(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)!).toBeGreaterThan(fit);
        }
    });

    it('좁은 화면의 라벨 문턱은 절대 밀도값을 보존한다', () => {
        expect(labelZoomFor('COUNTY', 0.01)).toBeCloseTo(TIER2_LABEL_ZOOM.COUNTY, 6);
        expect(labelZoomFor('COUNTY', 0.02, 2)).toBeCloseTo(TIER2_LABEL_ZOOM.COUNTY * 2, 6);
        expect(labelZoomFor('COUNTY', 0.008, 0.8)).toBeCloseTo(TIER2_LABEL_ZOOM.COUNTY * 0.8, 6);
    });

    it('넓고 고DPI인 화면에서도 라벨 문턱은 도달 가능하다', () => {
        for (const fit of [0.5575, 1.0035, 2.676, 5]) {
            const threshold = labelZoomFor('COUNTY', fit, 2)!;
            expect(threshold).toBeLessThan(maxScaleForDpr(2));
            expect(threshold).toBeGreaterThanOrEqual(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)!);
        }
    });

    it('실제 격자와 郡治에서 초기·완전 줌아웃은 마커 문턱 아래다', () => {
        const viewports: [number, number][] = [[800, 600], [1280, 800], [1600, 900], [1920, 1080], [3013, 1200]];
        for (const [cssWidth, cssHeight] of viewports) {
            for (const dpr of [0.8, 1, 2]) {
                const width = cssWidth * dpr;
                const height = cssHeight * dpr;
                const fit = fitScale(width, height, grid);
                const markerThreshold = MIN_MARKER_K * fit;
                expect(fit).toBeLessThan(markerThreshold);
                expect(initialView(width, height, grid, hanTiles, dpr).scale).toBeLessThan(markerThreshold);
            }
        }
    });

    it.each([
        [360, 640],
        [768, 540],
        [1280, 720],
        [1920, 1080],
    ])('초기 fit은 %sx%s 컨테이너에서 전체 han 지형을 안전 여백 안에 둔다', (cssWidth, cssHeight) => {
        for (const dpr of [1, 1.5, 2, 3]) {
            const width = cssWidth * dpr;
            const height = cssHeight * dpr;
            const padding = 32 * dpr;
            const view = initialView(width, height, grid, hanTiles, dpr);
            const corners = [
                cellToScreen(0, 0, view),
                cellToScreen(grid.cols - 1, 0, view),
                cellToScreen(0, grid.rows - 1, view),
                cellToScreen(grid.cols - 1, grid.rows - 1, view),
            ];

            for (const [x, y] of corners) {
                expect(x).toBeGreaterThanOrEqual(padding);
                expect(x).toBeLessThanOrEqual(width - padding);
                expect(y).toBeGreaterThanOrEqual(padding);
                expect(y).toBeLessThanOrEqual(height - padding);
            }
        }
    });
});
