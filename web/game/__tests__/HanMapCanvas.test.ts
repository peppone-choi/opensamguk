import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
    cityMarkerDrawBox, cityMarkerHitBox, expandOwner, fitScale, initialView, labelledRegions, labelZoomFor, maxScaleForDpr, seatLabel,
    TIER2_LABEL_ZOOM, TIER2_MARKER_ZOOM, tierZoom, type HanTiles,
} from '@opensamguk/ui';

const hanTiles: HanTiles = JSON.parse(
    readFileSync(resolve(__dirname, '../../../data/map/han-tiles.json'), 'utf8'),
);
const grid = { cols: hanTiles._meta.cols, rows: hanTiles._meta.rows };
const MIN_MARKER_K = Math.min(...Object.values(TIER2_MARKER_ZOOM));

describe('지도 아이콘 배율과 앵커', () => {
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
});
