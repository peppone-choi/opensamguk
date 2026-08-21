'use client';

// 후한 군현 지형 맵 렌더러 — game-api `GET /api/map/terrain` 의 셀 격자.
// 격자는 정사각이 아니다. 세계 프레임(93~133E · 15~45N)이 가로로 길어 cols≠rows 다.
//
// 기존 MapViewer 와는 그리는 방식이 다르다. MapViewer 는 php 정본 700×500 좌표계 위에 CDN
// 베이스 이미지를 깔고 도시 점을 얹는다. 이쪽은 사료 좌표에서 구운 셀 격자를 직접 칠한다.
// 같은 컴포넌트를 넓히지 않고 렌더 경로를 나눈 이유가 그것이다 — 좌표계도 소스도 다르다.
//
// 투영은 **아이소메트릭**이다(OPENSAM-201). 셀→화면 변환과 그 역, 줌/팬 수학은 `@/lib/isoMap`
// 에 순수 함수로 두고 여기서는 그리기만 한다. 지형 513k 셀을 다이아몬드로 하나씩 찍으면
// 프레임이 죽으므로, 셀당 1픽셀 오프스크린을 한 번 굽고 **아핀 변환 행렬**로 통째로 기울인다 —
// 격자 정사각형을 2:1 다이아몬드로 보내는 행렬이 곧 아이소 투영이다.
//
// 격자가 배포에 주입되지 않으면 엔드포인트가 404 를 주고, 이 컴포넌트는 null 을 렌더한다.

import { useCallback, useEffect, useRef, useState } from 'react';
import {
    cellToScreen, centeredView, clampView, fitScale, junSpanCells, scaleForSpan,
    viewAt, visibleCells, zoomAt,
    type GridSize, type IsoView,
} from '@/lib/isoMap';

export interface Jun { name: string; nameCh: string; seat: number; col: number; row: number }
export interface AdjEdge { a: number; b: number; cells: number; cross: string; ford?: number[] }

export interface HanTiles {
    _meta: {
        cols: number;
        rows: number;
        year: number;
        terrainLegend: Record<string, string>;
        roadMaskBits: Record<string, number>;
    };
    terrain: string[];
    owner: [number, number][];
    seatOwner: [number, number][];
    juns: Jun[];
    adjacency: { county: AdjEdge[]; commandery: AdjEdge[] };
    regions: { name: string; nameCh: string; en: string; cls: string; col: number; row: number; cells: number }[];
    cities: {
        id: string; name: string; nameCh: string; level: number; kind: string;
        seat: boolean; col: number; row: number;
    }[];
}

// 지형 색 — 범례 인덱스 순서(0 SEA … 8 HILL). 채도를 낮춰 도시·도로가 위에서 읽히게 한다.
const TERRAIN = [
    '#1d3f5c', // 0 바다
    '#5f7f4a', // 1 평야
    '#6b6257', // 2 산지
    '#3d6b8a', // 3 강
    '#2f5f7a', // 4 호수
    '#b3a271', // 5 사막
    '#8a7f5c', // 6 고원
    '#7a7050', // 7 분지
    '#6f7a4e', // 8 구릉
];

// 배율 문턱 — 줌 아웃 상태에서 지명을 다 그리면 지형이 안 보인다.
// 여기서는 켜고 끄기만 한다. 州→郡→縣 정식 3계층 LOD 는 OPENSAM-202 다.
const COUNTY_ZOOM = 2.2;
const JUN_LABEL_ZOOM = 1.4;
const COUNTY_LABEL_ZOOM = 5.5;   // 縣 970개 이름 — 여기까지 당겨야 서로 안 겹친다

/**
 * 성(城) 이름은 郡 이름이 아니라 **治所 縣** 이름이다 — 遼東郡이 아니라 襄平, 河南尹이 아니라 낙양.
 * 郡 은 그 성이 다스리는 영역(HOI4 의 State)이라 배경 라벨로 따로 깔린다.
 * 縣 접미사는 뗀다. 한 글자로 줄어드는 이름(懷縣→회)도 사료 그대로다.
 */
export function seatLabel(name: string): string {
    return name.length > 1 && name.endsWith('현') ? name.slice(0, -1) : name;
}

/** 런렝스 소유 격자를 셀 배열로 되돌린다. -1 은 바다다. */
export function expandOwner(rle: [number, number][], cells: number): Int16Array {
    const out = new Int16Array(cells);
    let i = 0;
    for (const [v, n] of rle) {
        for (let k = 0; k < n && i < cells; k++) out[i++] = v;
    }
    return out;
}

/** 라벨을 띄울 지역만 고른다 — 작은 조각까지 쓰면 글자가 서로 겹쳐 읽히지 않는다. */
export function labelledRegions(regions: HanTiles['regions'], minCells = 120) {
    return regions.filter((r) => r.cells >= minCells);
}

/**
 * 지형을 셀당 1픽셀로 굽는다. 데이터가 오면 **한 번만** 굽고 이후 프레임은 이걸 기울여 쓴다.
 * 매 프레임 513k 픽셀을 다시 칠하면 줌/팬이 끊긴다.
 */
function bakeTerrain(data: HanTiles): HTMLCanvasElement | null {
    const { cols, rows } = data._meta;
    const off = document.createElement('canvas');
    off.width = cols;
    off.height = rows;
    const octx = off.getContext('2d');
    if (!octx) return null;
    const img = octx.createImageData(cols, rows);
    for (let y = 0; y < rows; y++) {
        const row = data.terrain[y];
        for (let x = 0; x < cols; x++) {
            const hex = TERRAIN[Number(row[x])] ?? TERRAIN[0];
            const o = (y * cols + x) * 4;
            img.data[o] = parseInt(hex.slice(1, 3), 16);
            img.data[o + 1] = parseInt(hex.slice(3, 5), 16);
            img.data[o + 2] = parseInt(hex.slice(5, 7), 16);
            img.data[o + 3] = 255;
        }
    }
    octx.putImageData(img, 0, 0);
    return off;
}

function draw(
    cv: HTMLCanvasElement, data: HanTiles, terrain: HTMLCanvasElement,
    v: IsoView, w: number, h: number,
) {
    const { cols, rows } = data._meta;
    const ctx = cv.getContext('2d');
    if (!ctx) return;
    const s = v.scale;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, w, h);

    // 지형 — 격자 (x,y) 를 아이소로 보내는 아핀 행렬. 픽셀 하나가 다이아몬드 하나가 된다.
    // 셀 중심 기준이라 좌상단으로 반 칸 물려 그린다.
    ctx.save();
    ctx.transform(s, s / 2, -s, s / 2, v.ox, v.oy);
    // 지형은 1픽셀=1셀 래스터다. 축소할 땐 보간이 있어야 매끄럽고, 확대하면 그 보간이
    // 셀을 뭉갠다 — 셀 하나가 화면에서 2px 을 넘으면 보간을 꺼서 칸이 칸으로 보이게 한다.
    ctx.imageSmoothingEnabled = s < 2;
    ctx.drawImage(terrain, -0.5, -0.5, cols, rows);
    ctx.restore();

    const box = visibleCells(w, h, v, { cols, rows });
    const seen = (c: number, r: number) => c >= box.col0 && c <= box.col1 && r >= box.row0 && r <= box.row1;
    const px = (c: number, r: number) => cellToScreen(c, r, v);

    // 지역 이름 — 지형 바로 위, 도시 아래. 큰 지형지물이 배경으로 읽히는 층이다.
    ctx.textAlign = 'center';
    ctx.fillStyle = 'rgba(255,255,255,0.34)';
    ctx.font = `${Math.min(34, Math.max(10, Math.round(s * 6)))}px serif`;
    for (const r of labelledRegions(data.regions)) {
        const [x, y] = px(r.col, r.row);
        ctx.fillText(r.name, x, y);
    }
    // 郡 이름 — 성이 다스리는 영역의 이름이다. 성 이름(治所 縣)과 겹치지 않게 아래에 흐리게 깐다.
    if (s >= JUN_LABEL_ZOOM) {
        ctx.font = `${Math.min(15, Math.max(9, Math.round(s * 2.4)))}px serif`;
        ctx.fillStyle = 'rgba(255,236,200,0.42)';
        for (const j of data.juns) {
            if (!seen(j.col, j.row)) continue;
            const [x, y] = px(j.col, j.row);
            ctx.fillText(j.name, x, y + Math.max(9, s * 1.8));
        }
    }

    // 도시 — 郡治만 이름을 단다. 縣 970개까지 쓰면 글자가 지도를 덮는다.
    for (const c of data.cities) {
        // 이민족 거점(EXTERNAL_PLACE)은 줌과 무관하게 항상 그린다 — 縣처럼 접히면
        // devsam che 의 이민족 세력(남만·산월·오환·왜 …)이 통째로 사라져 보인다.
        const ext = c.kind === 'EXTERNAL_PLACE';
        // 郡治로 뽑히지 못한 郡·州 점은 찍지 않는다. 그 郡은 이미 治所가 대표하고 있어서
        // 여기서 또 찍으면 治所 옆에 같은 郡이 縣 마크로 하나 더 박힌다.
        if (!c.seat && !ext && (s < COUNTY_ZOOM || c.kind !== 'COUNTY')) continue;
        if (!seen(c.col, c.row)) continue;
        const [x, y] = px(c.col, c.row);
        ctx.beginPath();
        ctx.arc(x, y, c.seat || ext ? Math.max(2, s * 0.8) : Math.max(1, s * 0.4), 0, Math.PI * 2);
        ctx.fillStyle = ext ? '#d9705d' : c.seat ? '#f4e4b8' : 'rgba(244,228,184,0.55)';
        ctx.fill();
        if (c.seat || ext) {
            ctx.strokeStyle = 'rgba(0,0,0,0.55)';
            ctx.lineWidth = 1;
            ctx.stroke();
        }
    }
    // 이름은 郡 이름을 단다. 郡治의 縣 이름(襄平)보다 郡 이름(遼東郡)이 이동 단위다.
    ctx.font = `${Math.min(20, Math.max(10, Math.round(s * 4)))}px sans-serif`;
    ctx.fillStyle = '#fff';
    ctx.strokeStyle = 'rgba(0,0,0,0.75)';
    ctx.lineWidth = 3;
    // 이민족 거점 이름도 항상 단다. 25곳뿐이라 지도를 덮지 않는다.
    for (const c of data.cities) {
        if (c.kind !== 'EXTERNAL_PLACE' || !seen(c.col, c.row)) continue;
        const [x, y] = px(c.col, c.row);
        const ty = y - Math.max(6, s * 1.6);
        ctx.strokeText(c.name, x, ty);
        ctx.fillText(c.name, x, ty);
    }
    for (const j of data.juns) {
        if (s < JUN_LABEL_ZOOM) break;
        if (!seen(j.col, j.row)) continue;
        const [x, y] = px(j.col, j.row);
        const ty = y - Math.max(6, s * 1.6);
        const label = seatLabel(data.cities[j.seat]?.name ?? j.name);
        ctx.strokeText(label, x, ty);
        ctx.fillText(label, x, ty);
    }
    // 縣 이름 — 郡治가 아닌 縣. 이 배율 아래에서는 점만 찍고 이름은 숨긴다.
    if (s >= COUNTY_LABEL_ZOOM) {
        ctx.font = `${Math.min(13, Math.max(9, Math.round(s * 2.6)))}px sans-serif`;
        ctx.fillStyle = 'rgba(255,255,255,0.82)';
        ctx.lineWidth = 2.5;
        for (const c of data.cities) {
            if (c.seat || c.kind !== 'COUNTY' || !seen(c.col, c.row)) continue;
            const [x, y] = px(c.col, c.row);
            const ty = y - Math.max(5, s * 1.1);
            const label = seatLabel(c.name);
            ctx.strokeText(label, x, ty);
            ctx.fillText(label, x, ty);
        }
    }
}

/**
 * 첫 화면 — 郡 두세 개가 보이는 배율로 낙양(河南尹)에 맞춘다.
 *
 * 격자 전체(centeredView)로 시작하면 768×669 가 한 화면에 눌려 郡 이름조차 안 읽힌다.
 * 郡 하나의 폭은 郡治끼리의 최근접 거리로 잡고(junSpanCells), 그 3배가 담기는 배율을 쓴다.
 * 중심은 후한의 수도다 — 어느 방향으로 끌어도 중원이 먼저 나온다.
 */
function initialView(w: number, h: number, g: GridSize, data: HanTiles): IsoView {
    const luo = data.juns.find((j) => j.name === '河南尹' || j.name === '하남윤') ?? data.juns[0];
    if (!luo) return centeredView(w, h, g);
    const span = 3 * junSpanCells(data.juns);
    return clampView(viewAt(w, h, luo.col, luo.row, scaleForSpan(w, h, span)), w, h, g);
}

export default function HanMapCanvas({ onMissing }: { onMissing?: () => void }) {
    const boxRef = useRef<HTMLDivElement>(null);
    const cvRef = useRef<HTMLCanvasElement>(null);
    const terrainRef = useRef<HTMLCanvasElement | null>(null);
    const seatRef = useRef<Int16Array | null>(null);
    const viewRef = useRef<IsoView | null>(null);
    const sizeRef = useRef({ w: 0, h: 0 });
    const dragRef = useRef<{ x: number; y: number } | null>(null);

    const [data, setData] = useState<HanTiles | null>(null);
    const [missing, setMissing] = useState(false);

    useEffect(() => {
        let alive = true;
        fetch('/api/game/api/map/terrain')
            .then((r) => {
                if (r.status === 404) return null;
                if (!r.ok) throw new Error(`terrain fetch failed: ${r.status}`);
                return r.json();
            })
            .then((d) => {
                if (!alive) return;
                if (d) setData(d as HanTiles);
                else { setMissing(true); onMissing?.(); }
            })
            .catch(() => {
                if (!alive) return;
                setMissing(true); onMissing?.();
            });
        return () => {
            alive = false;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // 데이터가 오면 지형을 한 번 굽는다. 이후 줌/팬은 이 산출물만 다시 기울인다.
    useEffect(() => {
        if (!data) return;
        const { cols, rows } = data._meta;
        terrainRef.current = bakeTerrain(data);
        seatRef.current = expandOwner(data.seatOwner, cols * rows);
        viewRef.current = null;   // 새 데이터면 뷰를 다시 맞춘다
    }, [data]);

    const render = useCallback(() => {
        const cv = cvRef.current;
        const t = terrainRef.current;
        const v = viewRef.current;
        if (!cv || !data || !t || !v || !seatRef.current) return;
        const { w, h } = sizeRef.current;
        draw(cv, data, t, v, w, h);
    }, [data]);

    // 컨테이너 크기에 캔버스를 맞춘다. 레티나에서 흐려지지 않게 dpr 을 곱해 백버퍼를 키운다.
    useEffect(() => {
        const box = boxRef.current;
        const cv = cvRef.current;
        if (!box || !cv || !data) return;
        const fit = () => {
            const w = box.clientWidth;
            const h = Math.round(w * 0.53);      // 아이소 다이아몬드는 가로:세로 2:1 이라 여유를 조금 준 0.53 이 딱 맞는다
            const dpr = window.devicePixelRatio || 1;
            cv.width = Math.round(w * dpr);
            cv.height = Math.round(h * dpr);
            cv.style.height = `${h}px`;
            sizeRef.current = { w: w * dpr, h: h * dpr };
            const g = { cols: data._meta.cols, rows: data._meta.rows };
            viewRef.current = viewRef.current
                ? clampView(viewRef.current, sizeRef.current.w, sizeRef.current.h, g)
                : initialView(sizeRef.current.w, sizeRef.current.h, g, data);
            render();
        };
        fit();
        const ro = new ResizeObserver(fit);
        ro.observe(box);
        return () => ro.disconnect();
    }, [data, render]);

    // 휠 줌 — 커서 밑 셀을 고정점으로 둔다. 하한은 격자 전체가 들어가는 배율이다.
    const onWheel = useCallback((e: React.WheelEvent<HTMLCanvasElement>) => {
        const cv = cvRef.current;
        const v = viewRef.current;
        if (!cv || !v || !data) return;
        e.preventDefault();
        const rect = cv.getBoundingClientRect();
        const dpr = cv.width / rect.width;
        const g = { cols: data._meta.cols, rows: data._meta.rows };
        const min = fitScale(sizeRef.current.w, sizeRef.current.h, g);
        const next = zoomAt(v, (e.clientX - rect.left) * dpr, (e.clientY - rect.top) * dpr,
                            e.deltaY < 0 ? 1.15 : 1 / 1.15, min);
        viewRef.current = clampView(next, sizeRef.current.w, sizeRef.current.h, g);
        render();
    }, [data, render]);

    const zoomBy = useCallback((factor: number) => {
        const v = viewRef.current;
        if (!v || !data) return;
        const { w, h } = sizeRef.current;
        const g = { cols: data._meta.cols, rows: data._meta.rows };
        const next = zoomAt(v, w / 2, h / 2, factor, fitScale(w, h, g));
        viewRef.current = clampView(next, w, h, g);
        render();
    }, [data, render]);

    const onPointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
        e.currentTarget.setPointerCapture(e.pointerId);
        dragRef.current = { x: e.clientX, y: e.clientY };
    };
    const onPointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
        const d = dragRef.current;
        const v = viewRef.current;
        const cv = cvRef.current;
        if (!d || !v || !cv || !data) return;
        const dpr = cv.width / cv.getBoundingClientRect().width;
        const { w, h } = sizeRef.current;
        viewRef.current = clampView(
            { scale: v.scale, ox: v.ox + (e.clientX - d.x) * dpr, oy: v.oy + (e.clientY - d.y) * dpr },
            w, h, { cols: data._meta.cols, rows: data._meta.rows },
        );
        dragRef.current = { x: e.clientX, y: e.clientY };
        render();
    };
    const endDrag = () => { dragRef.current = null; };

    if (missing) return null;
    return (
        <div ref={boxRef} style={{ position: 'relative', width: '100%' }}>
            <canvas
                ref={cvRef}
                aria-label={data ? `후한 군현 지도 (${data._meta.year}년)` : '지도 불러오는 중'}
                onWheel={onWheel}
                onPointerDown={onPointerDown}
                onPointerMove={onPointerMove}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                style={{ width: '100%', display: 'block', touchAction: 'none', cursor: 'grab', background: TERRAIN[0] }}
            />
            {/* 휠이 없는 환경(터치·트랙패드 없는 접근성 경로)을 위한 줌 버튼. */}
            <div style={{ position: 'absolute', right: 8, bottom: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
                <button type="button" aria-label="확대" onClick={() => zoomBy(1.4)}>+</button>
                <button type="button" aria-label="축소" onClick={() => zoomBy(1 / 1.4)}>−</button>
            </div>
        </div>
    );
}
