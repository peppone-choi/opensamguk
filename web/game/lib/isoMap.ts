// 아이소메트릭 투영 + 뷰포트 수학 (OPENSAM-201).
//
// 후한 군현 격자는 768×669 = 513k 셀이다. 렌더러는 매 프레임 전부 그릴 수 없으므로
// 화면에 걸치는 셀만 골라야 하고, 그러려면 투영과 그 역변환이 한 곳에 있어야 한다.
// 이 파일에 DOM 은 없다 — 순수 함수만 둬서 캔버스 없이 테스트한다.
//
// 타일은 2:1 다이아몬드다. scale=1 에서 타일 가로 2, 세로 1 로 잡고
// 화면 픽셀은 scale 로만 곱한다. "scale = 셀 하나의 화면 반폭(px)" 이다.

/** 뷰포트 — 배율과 화면 원점 오프셋. 이게 줌/팬의 전체 상태다. */
export interface IsoView {
    scale: number;
    ox: number;
    oy: number;
}

/** 셀 격자 크기. */
export interface GridSize {
    cols: number;
    rows: number;
}

/** 셀 중심 (col,row) → 화면 픽셀. 격자 원점(0,0)이 위쪽 꼭짓점이다. */
export function cellToScreen(col: number, row: number, v: IsoView): [number, number] {
    return [(col - row) * v.scale + v.ox, (col + row) * 0.5 * v.scale + v.oy];
}

/** 화면 픽셀 → 셀 좌표(실수). 히트 테스트는 이걸 반올림해 쓴다. */
export function screenToCell(sx: number, sy: number, v: IsoView): [number, number] {
    const ix = (sx - v.ox) / v.scale;
    const iy = (sy - v.oy) / v.scale;
    return [(ix + 2 * iy) / 2, (2 * iy - ix) / 2];
}

/**
 * 화면 사각형(0,0)-(w,h)에 걸치는 셀의 바운딩 박스.
 *
 * 아이소 격자는 화면 사각형과 축이 어긋나 있어서 네 꼭짓점을 역변환한 min/max 가
 * 곧 답이다(투영이 아핀이라 그렇다). 다이아몬드 반 칸이 걸치는 경우를 위해 1칸 여유를 준다.
 */
export function visibleCells(w: number, h: number, v: IsoView, g: GridSize) {
    const corners: [number, number][] = [
        screenToCell(0, 0, v), screenToCell(w, 0, v),
        screenToCell(0, h, v), screenToCell(w, h, v),
    ];
    const cols = corners.map((c) => c[0]);
    const rows = corners.map((c) => c[1]);
    return {
        col0: Math.max(0, Math.floor(Math.min(...cols)) - 1),
        col1: Math.min(g.cols - 1, Math.ceil(Math.max(...cols)) + 1),
        row0: Math.max(0, Math.floor(Math.min(...rows)) - 1),
        row1: Math.min(g.rows - 1, Math.ceil(Math.max(...rows)) + 1),
    };
}

/** 격자 전체가 화면에 들어가는 배율. 줌 아웃의 하한이다. */
export function fitScale(w: number, h: number, g: GridSize): number {
    const spanX = g.cols + g.rows - 2;        // isoX ∈ [-(rows-1), cols-1]
    const spanY = (g.cols + g.rows - 2) / 2;  // isoY ∈ [0, (cols+rows-2)/2]
    return Math.min(w / spanX, h / spanY);
}

/** 줌 상한 — 셀 하나가 이 픽셀 반폭이 되면 縣 단위가 읽힌다. 더 당길 이유가 없다. */
export const MAX_SCALE = 14;

/**
 * 커서를 고정점으로 두고 확대/축소한다.
 * 커서 밑에 있던 셀이 그대로 커서 밑에 남아야 지도를 손으로 끄는 느낌이 난다.
 */
export function zoomAt(v: IsoView, sx: number, sy: number, factor: number, min: number): IsoView {
    const scale = Math.min(MAX_SCALE, Math.max(min, v.scale * factor));
    const k = scale / v.scale;
    return { scale, ox: sx - (sx - v.ox) * k, oy: sy - (sy - v.oy) * k };
}

/**
 * 격자가 화면 밖으로 완전히 빠지지 않게 오프셋을 가둔다.
 *
 * 투영된 격자는 사각형이 아니라 다이아몬드라, 바운딩 박스가 화면에 걸치는 것만으로는
 * 부족하다 — 박스 모서리만 걸치고 본체는 밖에 있을 수 있다. 그래서 **화면 중앙에 오는
 * 셀**을 격자 안으로 가둔다. 어떤 셀이든 화면 중앙에 놓을 수 있으니 확대 상태에서도
 * 구석까지 자유롭게 끌리고, 빈 화면은 나오지 않는다.
 */
export function clampView(v: IsoView, w: number, h: number, g: GridSize): IsoView {
    const [fc, fr] = screenToCell(w / 2, h / 2, v);
    if (fc >= 0 && fc <= g.cols - 1 && fr >= 0 && fr <= g.rows - 1) return v;
    return viewAt(w, h,
        Math.min(g.cols - 1, Math.max(0, fc)),
        Math.min(g.rows - 1, Math.max(0, fr)),
        v.scale);
}

/** 격자 중심을 화면 중앙에 두는 초기 뷰. */
export function centeredView(w: number, h: number, g: GridSize): IsoView {
    return viewAt(w, h, (g.cols - 1) / 2, (g.rows - 1) / 2, fitScale(w, h, g));
}

/** 주어진 셀을 화면 중앙에 두는 뷰. */
export function viewAt(w: number, h: number, col: number, row: number, scale: number): IsoView {
    const [cx, cy] = cellToScreen(col, row, { scale, ox: 0, oy: 0 });
    return { scale, ox: w / 2 - cx, oy: h / 2 - cy };
}

/**
 * 셀 `span` 칸이 화면에 담기는 배율.
 * span×span 정사각 영역은 아이소에서 가로 2·span·scale, 세로 span·scale 로 눕는다.
 */
export function scaleForSpan(w: number, h: number, span: number): number {
    return Math.min(MAX_SCALE, Math.min(w / (2 * span), h / span));
}

/**
 * 郡 하나의 대략적인 폭(셀). 郡治끼리의 최근접 거리 중앙값을 쓴다.
 *
 * 면적을 세지 않는 이유는 소유 격자를 다시 훑지 않고도 같은 답이 나오기 때문이다 —
 * 이웃한 治所 사이 거리가 곧 그 郡 한 칸의 크기다. 중앙값이라 섬·변군 이상치에 안 끌린다.
 */
export function junSpanCells(juns: { col: number; row: number }[]): number {
    if (juns.length < 2) return 24;
    const near: number[] = [];
    for (let i = 0; i < juns.length; i++) {
        let best = Infinity;
        for (let j = 0; j < juns.length; j++) {
            if (i === j) continue;
            const dx = juns[i].col - juns[j].col;
            const dy = juns[i].row - juns[j].row;
            const d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        if (best < Infinity) near.push(Math.sqrt(best));
    }
    near.sort((a, b) => a - b);
    return near[near.length >> 1];
}
