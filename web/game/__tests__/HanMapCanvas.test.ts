import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { fitScale, MAX_SCALE } from '../lib/isoMap';
import {
    expandOwner, type HanTiles, initialView, labelledRegions, labelZoomFor, seatLabel,
    TIER2_LABEL_ZOOM, TIER2_MARKER_ZOOM, tierZoom,
} from '../components/game/HanMapCanvas';

const hanTiles: HanTiles = JSON.parse(
    readFileSync(resolve(__dirname, '../../../data/map/han-tiles.json'), 'utf8'),
);
const grid = { cols: hanTiles._meta.cols, rows: hanTiles._meta.rows };
const MIN_MARKER_K = Math.min(...Object.values(TIER2_MARKER_ZOOM));

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
        expect(seatLabel('회현')).toBe('회');       // 한 글자로 줄어도 사료 그대로
        expect(seatLabel('현')).toBe('현');         // 이름 자체가 '현'이면 그대로
        expect(seatLabel('요동군')).toBe('요동군'); // 縣 기록이 없는 자리는 손대지 않는다
    });
});

describe('등급 → 최소 표시 zoom 매핑', () => {
    it('마커 문턱은 fit 배수(K)를 그대로 돌려준다', () => {
        const fit = 1.0035; // 대표 뷰포트(1440x800)의 fitScale
        expect(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)).toBeCloseTo(TIER2_MARKER_ZOOM.COUNTY * fit, 6);
        expect(tierZoom(TIER2_MARKER_ZOOM, 'MARQUISATE', fit)).toBeCloseTo(TIER2_MARKER_ZOOM.MARQUISATE * fit, 6);
    });

    it('1급(郡·國·州)과 아직 데이터에 없는 KINGDOM은 undefined — 호출부가 안전하게 안 그린다', () => {
        expect(tierZoom(TIER2_MARKER_ZOOM, 'COMMANDERY', 1)).toBeUndefined();
        expect(tierZoom(TIER2_MARKER_ZOOM, 'KINGDOM', 1)).toBeUndefined();
        expect(tierZoom(TIER2_MARKER_ZOOM, 'PROVINCE', 1)).toBeUndefined();
        expect(labelZoomFor('KINGDOM', 1)).toBeUndefined();
    });

    it('마커 문턱은 fitScale 의 배수(K>1)라 어떤 화면에서도 fitScale 자체를 절대 못 내려간다', () => {
        // 이게 (b) 수정의 핵심 보장이다 — 화면·DPR 이 무엇이든 "완전히 줌아웃(s=fit)"
        // 하면 문턱을 반드시 밑돈다. 절대 상수였다면 넓고 고DPI인 화면에서 fitScale 자체가
        // 문턱을 넘어(예: 1600css@2x → fitScale≈2.23 ≥ 이전 2.2) 縣이 절대 안 사라졌다.
        for (const fit of [0.01, 0.5575, 1.0035, 2.676, 100]) {
            expect(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)!).toBeGreaterThan(fit);
        }
    });

    it('라벨 문턱은 좁은 화면에서 절대 밀도값(TIER2_LABEL_ZOOM)을 그대로 쓴다', () => {
        // fit 이 작으면 마커 문턱(K·fit)도 작아 절대값보다 낮다 — max() 가 절대값을 고른다.
        expect(labelZoomFor('COUNTY', 0.01)).toBeCloseTo(TIER2_LABEL_ZOOM.COUNTY, 6);
    });

    it('라벨 문턱은 넓고 고DPI인 화면에서도 MAX_SCALE 아래에 머문다 — 영원히 안 뜨는 화면이 없다', () => {
        // 독립 리뷰가 잡은 결함: 라벨 문턱을 마커처럼 fit 배수로만 두면 1920css@2x
        // (fit≈2.68) 같은 화면에서 K·fit 이 MAX_SCALE(14)을 넘어 縣 이름이 그 화면에서
        // 절대 안 나타났다. labelZoomFor 는 MAX_SCALE 아래로 클램프해 항상 도달 가능해야 한다.
        // fit=5 는 1440css 컨테이너 기준 dpr≈5 인 화면과 같다 — 실사용 하드웨어(최대 3x
        // 안팎)를 훌쩍 넘는 여유다. 그 너머(dpr≈6.4+)에서는 마커 문턱 자체가 MAX_SCALE 을
        // 넘어 마커도 못 뜨는 별개의 극단 케이스라 이 불변식 대상이 아니다.
        for (const fit of [0.5575, 1.0035, 2.676, 5]) {
            const t = labelZoomFor('COUNTY', fit)!;
            expect(t).toBeLessThan(MAX_SCALE);
            // 구조적 제약 — 라벨은 마커가 뜨기 전에는 뜨지 않는다.
            expect(t).toBeGreaterThanOrEqual(tierZoom(TIER2_MARKER_ZOOM, 'COUNTY', fit)!);
        }
    });

    it('실제 격자·郡治 좌표로 5 뷰포트×2 dpr 전부에서 initialView·완전줌아웃 둘 다 마커 문턱 아래에서 시작한다', () => {
        // 실측 데이터(data/map/han-tiles.json)로 직접 검증한다 — 그래프 상수를 다시 베낀
        // 스크립트가 아니라 실제 initialView/fitScale 을 호출한다.
        const viewports: [number, number][] = [[800, 600], [1280, 800], [1600, 900], [1920, 1080], [3013, 1200]];
        for (const [cw, ch] of viewports) {
            for (const dpr of [1, 2]) {
                const w = cw * dpr;
                const h = ch * dpr;
                const fit = fitScale(w, h, grid);
                const markerThreshold = MIN_MARKER_K * fit;
                // (b) 완전 줌아웃(scale=fit) 은 어떤 화면에서도 문턱을 절대 못 넘는다.
                expect(fit).toBeLessThan(markerThreshold);
                // (a) 초기 뷰는 문턱보다 낮게 시작해 縣이 첫 화면에 안 깔린다.
                expect(initialView(w, h, grid, hanTiles).scale).toBeLessThan(markerThreshold);
            }
        }
    });
});
