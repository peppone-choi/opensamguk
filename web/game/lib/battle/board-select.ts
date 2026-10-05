// 실시간 전투 판 조작 — 보드 V31K6v2BattleLiveMany · MBattleLive(D24 세부 1 · 2). 화면 좌표만 다루는 순수 계산이다(그리기는 BattleBoardCanvas).
// - 판에서 고르기: 데스크톱은 마우스로 판을 끌어 만든 사각형, 「판에서 고르기」를 켜면 두 점이 만든 사각형. 그 안에 화면 중심이 든 내 부곡을 고른다.
// - 많을 때 묶기: 축소했을 때만 같은 장수의 가까이 모인 부곡을 깃발 하나 + 숫자로 묶는다. 묶음을 누르면 그 자리로 다가가 하나씩 갈라진다.
// - 판 움직이기: 모바일은 끌기가 판 움직이기다(데스크톱 끌기는 사각형 고르기).

export interface ScreenPoint {
    readonly x: number;
    readonly y: number;
}

/** 판 위 내 부곡 하나의 화면 중심 — group 은 장수 차례(1부터, 목록 「장수 n」과 같다). */
export interface ScreenMark extends ScreenPoint {
    readonly id: string;
    readonly group: number;
}

export interface MarkCluster extends ScreenPoint {
    readonly group: number;
    readonly ids: readonly string[];
}

/** 이만큼(화면 px) 움직이면 누르기가 아니라 끌기다. */
export const DRAG_THRESHOLD = 8;

/** 축소했을 때 묶는 반경(화면 px) — 깃발 누를 영역(44)보다 조금 작게. */
export const CLUSTER_RADIUS = 36;

export function isDrag(a: ScreenPoint, b: ScreenPoint): boolean {
    return Math.abs(b.x - a.x) >= DRAG_THRESHOLD || Math.abs(b.y - a.y) >= DRAG_THRESHOLD;
}

/** 두 점이 만든 사각형(경계 포함) 안에 화면 중심이 든 부곡 id — 판에 그린 차례 그대로. */
export function idsInRect(marks: readonly ScreenMark[], a: ScreenPoint, b: ScreenPoint): string[] {
    const [x0, x1] = a.x <= b.x ? [a.x, b.x] : [b.x, a.x];
    const [y0, y1] = a.y <= b.y ? [a.y, b.y] : [b.y, a.y];
    return marks.filter((m) => m.x >= x0 && m.x <= x1 && m.y >= y0 && m.y <= y1).map((m) => m.id);
}

/**
 * 같은 장수 부곡끼리, 묶음 중심에서 radius 안이면 한 묶음(앞에서부터 차례로 붙인다 — 결과가 그리기 차례에 따라 정해진다).
 * 다른 장수 부곡은 겹쳐도 묶지 않는다(깃발 하나 = 장수 하나, 보드).
 */
export function clusterMarks(marks: readonly ScreenMark[], radius: number): MarkCluster[] {
    const out: { group: number; ids: string[]; sx: number; sy: number }[] = [];
    for (const m of marks) {
        const hit = out.find((c) => c.group === m.group && Math.hypot(c.sx / c.ids.length - m.x, c.sy / c.ids.length - m.y) <= radius);
        if (hit) {
            hit.ids.push(m.id);
            hit.sx += m.x;
            hit.sy += m.y;
        } else {
            out.push({ group: m.group, ids: [m.id], sx: m.x, sy: m.y });
        }
    }
    return out.map((c) => ({ group: c.group, ids: c.ids, x: c.sx / c.ids.length, y: c.sy / c.ids.length }));
}

/** 화면 점 → 판 그림 좌표(렌더러 view 기준). */
export function toPicture(p: ScreenPoint, view: { readonly scale: number; readonly offsetX: number; readonly offsetY: number }): ScreenPoint {
    return { x: (p.x - view.offsetX) / view.scale, y: (p.y - view.offsetY) / view.scale };
}

/** 끈 만큼 판 가운데(판 그림 좌표)를 반대로 옮긴다 — 판 그림 밖으로는 나가지 않는다. */
export function panCenter(center: ScreenPoint, delta: ScreenPoint, scale: number, picture: { readonly width: number; readonly height: number }): ScreenPoint {
    const clamp = (v: number, max: number) => Math.min(max, Math.max(0, v));
    return { x: clamp(center.x - delta.x / scale, picture.width), y: clamp(center.y - delta.y / scale, picture.height) };
}
