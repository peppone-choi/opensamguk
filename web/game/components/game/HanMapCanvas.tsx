'use client';

// 후한 군현 지형 맵 렌더러 — game-api `GET /api/map/terrain` 의 셀 격자.
// 격자는 정사각이 아니다. 세계 프레임(93~133E · 15~45N)이 가로로 길어 cols≠rows 다.
//
// 기존 MapViewer 와는 그리는 방식이 다르다. MapViewer 는 php 정본 700×500 좌표계 위에 CDN
// 베이스 이미지를 깔고 도시 점을 얹는다. 이쪽은 사료 좌표에서 구운 셀 격자를 직접 칠한다.
// 같은 컴포넌트를 넓히지 않고 렌더 경로를 나눈 이유가 그것이다 — 좌표계도 소스도 다르다.
//
// 격자가 배포에 주입되지 않으면 엔드포인트가 404 를 주고, 이 컴포넌트는 null 을 렌더한다.
// 호출부는 그때 기존 맵으로 폴백한다(ADR-LITE-040 의 철거 경로가 그대로 동작하려면 필요하다).

import { useEffect, useRef, useState } from 'react';

export interface Jun { name: string; seat: number; col: number; row: number }
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
    roads: Record<string, number>;
    juns: Jun[];
    adjacency: { county: AdjEdge[]; commandery: AdjEdge[] };
    fords: { col: number; row: number; roads: number }[];
    regions: { name: string; en: string; cls: string; col: number; row: number; cells: number }[];
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

const WATER_BIT = 16;              // 수로 — 하천 조운과 연안 항해를 한 망으로 둔다
// 4방향 비트(N=1 E=2 S=4 W=8) → 셀 중심에서 뻗는 방향. 이웃과 만나 길이 이어진다.
const DIRS: [number, number, number][] = [[1, 0, -1], [2, 1, 0], [4, 0, 1], [8, -1, 0]];

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

/** 소유 격자에서 경계 셀만 뽑는다. 라벨이 갈리는 자리가 곧 국경이다. */
export function borderCells(lab: Int16Array, cols: number, rows: number) {
    const out: number[] = [];
    for (let y = 0; y < rows; y++) {
        for (let x = 0; x < cols; x++) {
            const v = lab[y * cols + x];
            if (v < 0) continue;
            if ((x + 1 < cols && lab[y * cols + x + 1] !== v)
                || (y + 1 < rows && lab[(y + 1) * cols + x] !== v)) out.push(y * cols + x);
        }
    }
    return out;
}

function draw(cv: HTMLCanvasElement, data: HanTiles, px: number) {
    const { cols, rows } = data._meta;
    const ctx = cv.getContext('2d');
    if (!ctx) return;
    const s = px / cols;                 // 셀 하나의 화면 크기. 등적 투영이라 가로세로가 같다.
    cv.width = px;
    cv.height = Math.round(rows * s);

    // 지형은 셀당 1픽셀 오프스크린에 찍고 한 번에 확대한다. 65536번 fillRect 하는 것보다 빠르고,
    // 확대 보간이 켜져 있어 셀 경계가 부드럽게 풀린다(픽셀 격자처럼 보이지 않는다).
    const off = document.createElement('canvas');
    off.width = cols;
    off.height = rows;
    const octx = off.getContext('2d');
    if (!octx) return;
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
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(off, 0, 0, cv.width, cv.height);

    const cx = (c: number) => (c + 0.5) * s;

    // 郡 경계 — 이동 판정의 단위다. 길이 아니라 이 선을 넘느냐로 움직인다.
    // 지형과 같은 오프스크린 해상도로 찍어야 셀 경계와 어긋나지 않는다.
    const seat = expandOwner(data.seatOwner, cols * rows);
    ctx.save();
    ctx.fillStyle = 'rgba(24,18,16,0.72)';
    for (const i of borderCells(seat, cols, rows)) {
        ctx.fillRect(((i % cols) + 0.5) * s - s * 0.28, (Math.floor(i / cols) + 0.5) * s - s * 0.28,
                     Math.max(1, s * 0.56), Math.max(1, s * 0.56));
    }
    ctx.restore();

    // 지역 이름 — 지형 바로 위, 도시 아래. 큰 지형지물이 배경으로 읽히는 층이다.
    ctx.textAlign = 'center';
    ctx.fillStyle = 'rgba(255,255,255,0.34)';
    ctx.font = `${Math.max(9, Math.round(px / 52))}px serif`;
    for (const r of labelledRegions(data.regions)) ctx.fillText(r.name, cx(r.col), cx(r.row));

    // 도로 — 셀 중심에서 이웃 방향으로 반 칸씩 그어 잇는다. 해로는 점선이다.
    ctx.lineCap = 'round';
    for (const kind of ['LAND', 'WATER'] as const) {
        ctx.strokeStyle = kind === 'LAND' ? 'rgba(226,206,160,0.85)' : 'rgba(150,200,225,0.7)';
        ctx.lineWidth = Math.max(1, s * (kind === 'LAND' ? 0.9 : 0.6));
        ctx.setLineDash(kind === 'WATER' ? [s * 1.5, s * 1.5] : []);
        ctx.beginPath();
        for (const [key, mask] of Object.entries(data.roads)) {
            if ((mask & WATER_BIT ? 'WATER' : 'LAND') !== kind) continue;
            const [gx, gy] = key.split(',').map(Number);
            for (const [bit, dx, dy] of DIRS) {
                if (!(mask & bit)) continue;
                ctx.moveTo(cx(gx), cx(gy));
                ctx.lineTo(cx(gx + dx / 2), cx(gy + dy / 2));
            }
        }
        ctx.stroke();
    }
    ctx.setLineDash([]);

    // 나루터 — 길이 강에 끊긴 자리. 도하 인접은 여기를 지난다.
    ctx.fillStyle = 'rgba(120,200,235,0.9)';
    ctx.strokeStyle = 'rgba(10,30,45,0.8)';
    ctx.lineWidth = 1;
    for (const f of data.fords) {
        const r = Math.max(1.5, s * 0.7);
        ctx.beginPath();
        ctx.arc(cx(f.col), cx(f.row), r, 0, Math.PI * 2);
        ctx.fill();
        ctx.stroke();
    }

    // 도시 — 郡治만 이름을 단다. 縣 970개까지 쓰면 글자가 지도를 덮는다.
    ctx.font = `${Math.max(9, Math.round(px / 64))}px sans-serif`;
    for (const c of data.cities) {
        const r = c.seat ? Math.max(2, s * 1.1) : Math.max(1, s * 0.55);
        ctx.beginPath();
        ctx.arc(cx(c.col), cx(c.row), r, 0, Math.PI * 2);
        ctx.fillStyle = c.seat ? '#f4e4b8' : 'rgba(244,228,184,0.55)';
        ctx.fill();
        if (c.seat) {
            ctx.strokeStyle = 'rgba(0,0,0,0.55)';
            ctx.lineWidth = 1;
            ctx.stroke();
        }
    }
    // 이름은 郡 이름을 단다. 郡治의 縣 이름(襄平)보다 郡 이름(遼東郡)이 이동 단위다.
    ctx.fillStyle = '#fff';
    ctx.strokeStyle = 'rgba(0,0,0,0.75)';
    ctx.lineWidth = 3;
    for (const j of data.juns) {
        const [tx, ty] = [cx(j.col), cx(j.row) - Math.max(4, s * 2)];
        ctx.strokeText(j.name, tx, ty);
        ctx.fillText(j.name, tx, ty);
    }
}

export default function HanMapCanvas(
    { px = 900, onMissing }: { px?: number; onMissing?: () => void },
) {
    const ref = useRef<HTMLCanvasElement>(null);
    const [data, setData] = useState<HanTiles | null>(null);
    const [missing, setMissing] = useState(false);

    useEffect(() => {
        let alive = true;
        fetch('/api/game/api/map/terrain')
            .then((r) => (r.status === 404 ? null : r.json()))
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
        // onMissing 은 폴백 신호 한 번뿐이라 재구독 대상이 아니다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        if (data && ref.current) draw(ref.current, data, px);
    }, [data, px]);

    if (missing) return null;   // 호출부가 기존 맵으로 폴백한다.
    return (
        <canvas
            ref={ref}
            aria-label={data ? `후한 군현 지도 (${data._meta.year}년)` : '지도 불러오는 중'}
            style={{ width: '100%', maxWidth: px, aspectRatio: data ? `${data._meta.cols} / ${data._meta.rows}` : '1 / 1', display: 'block', margin: '0 auto' }}
        />
    );
}
