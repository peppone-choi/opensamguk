// 지도 위 군단 겹(#465 라이브 오버레이) — 군단 깃발·이름표·행군 경로·요격 범위.
//
// 무엇을 보일지는 서버가 정한다(시야 투영, #343). 이 겹은 받은 것을 그리기만 한다: 안 보이는 적 군단은
// 애초에 오지 않고, 규칙이 허용한 마지막 목격만 `stale` 로 온다 — 흐리게 그리고 「?」를 단다.

import { cellToScreen, type IsoView } from '../isoMap';

type View = IsoView;

export interface MapCorpsOverlay {
    readonly id: string;
    /** 군단이 선 칸(격자 좌표). 省 중심 칸을 서버나 호출부가 풀어 넘긴다. */
    readonly col: number;
    readonly row: number;
    /** 이름표 — 「하후돈」. 병력 띠는 [troopsLabel] 로 따로. */
    readonly label: string;
    readonly troopsLabel?: string;
    /** 세력 색. 없으면 무소속 회색. */
    readonly color?: string;
    /** 내 군단 — 테두리를 청동으로, 경로·요격 범위를 그린다. */
    readonly own: boolean;
    /** 마지막 목격(첩보 시야) — 흐리게, 「?」. */
    readonly stale?: boolean;
    /** 행군 경로(칸). 내 군단에만 온다. */
    readonly path?: readonly { readonly col: number; readonly row: number }[];
    /** 요격 범위(칸 반경). 요격 방침이 걸린 내 군단에만 온다. */
    readonly interceptRadiusCells?: number;
}

const OWN_STROKE = '#c9a656';
const INK = 'rgba(18,12,6,0.92)';
const NEUTRAL = '#8e8879';

/** 아이소 변환 안에서 칸 반경 원 — 화면에서는 납작한 타원이 된다. */
function traceCellCircle(context: CanvasRenderingContext2D, view: View, col: number, row: number, radius: number) {
    context.save();
    context.transform(view.scale, view.scale / 2, -view.scale, view.scale / 2, view.ox, view.oy);
    context.beginPath();
    context.arc(col, row, radius, 0, Math.PI * 2);
    context.restore();
}

export function drawCorpsOverlay(
    context: CanvasRenderingContext2D,
    corps: readonly MapCorpsOverlay[],
    view: View,
    dpr: number,
): Array<{ corps: MapCorpsOverlay; x: number; y: number; radius: number }> {
    const hits: Array<{ corps: MapCorpsOverlay; x: number; y: number; radius: number }> = [];
    const ratio = Math.max(1, dpr);

    // 경로·범위를 먼저 — 깃발이 그 위에 온다.
    for (const item of corps) {
        if (!item.own) continue;
        if (item.interceptRadiusCells && item.interceptRadiusCells > 0) {
            traceCellCircle(context, view, item.col, item.row, item.interceptRadiusCells);
            context.save();
            context.fillStyle = 'rgba(201,166,86,0.10)';
            context.fill();
            context.setLineDash([6 * ratio, 4 * ratio]);
            context.strokeStyle = 'rgba(201,166,86,0.75)';
            context.lineWidth = 1.5 * ratio;
            context.stroke();
            context.restore();
        }
        if (item.path && item.path.length > 1) {
            context.save();
            context.setLineDash([8 * ratio, 5 * ratio]);
            context.lineCap = 'round';
            context.beginPath();
            item.path.forEach((point, index) => {
                const [x, y] = cellToScreen(point.col, point.row, view);
                if (index === 0) context.moveTo(x, y);
                else context.lineTo(x, y);
            });
            context.strokeStyle = INK;
            context.lineWidth = 5 * ratio;
            context.stroke();
            context.strokeStyle = OWN_STROKE;
            context.lineWidth = 2.5 * ratio;
            context.stroke();
            context.restore();
        }
    }

    for (const item of corps) {
        const [x, y] = cellToScreen(item.col, item.row, view);
        const size = 9 * ratio;
        context.save();
        context.globalAlpha = item.stale ? 0.55 : 1;
        // 깃대 + 네모 깃발. 세력 색으로 채우고 내 군단은 청동 테두리.
        context.strokeStyle = INK;
        context.lineWidth = 2 * ratio;
        context.beginPath();
        context.moveTo(x, y);
        context.lineTo(x, y - size * 2.2);
        context.stroke();
        context.fillStyle = item.color ?? NEUTRAL;
        context.fillRect(x, y - size * 2.2, size * 1.6, size * 1.1);
        if (item.stale) context.setLineDash([3 * ratio, 2 * ratio]);
        context.strokeStyle = item.own ? OWN_STROKE : INK;
        context.lineWidth = (item.own ? 2 : 1.5) * ratio;
        context.strokeRect(x, y - size * 2.2, size * 1.6, size * 1.1);
        context.setLineDash([]);
        if (item.stale) {
            context.fillStyle = '#ffffff';
            context.font = `bold ${9 * ratio}px sans-serif`;
            context.textAlign = 'center';
            context.textBaseline = 'middle';
            context.fillText('?', x + size * 0.8, y - size * 1.65);
        }

        const text = item.troopsLabel ? `${item.label} · ${item.troopsLabel}` : item.label;
        context.font = `bold ${10 * ratio}px sans-serif`;
        context.textAlign = 'center';
        context.textBaseline = 'middle';
        const chipWidth = context.measureText(text).width + 10 * ratio;
        const chipHeight = 16 * ratio;
        const chipY = y + 10 * ratio;
        context.fillStyle = INK;
        context.fillRect(x - chipWidth / 2, chipY - chipHeight / 2, chipWidth, chipHeight);
        context.strokeStyle = item.own ? OWN_STROKE : item.color ?? NEUTRAL;
        context.lineWidth = ratio;
        context.strokeRect(x - chipWidth / 2, chipY - chipHeight / 2, chipWidth, chipHeight);
        context.fillStyle = '#ffffff';
        context.fillText(text, x, chipY);
        context.restore();
        hits.push({ corps: item, x, y: y - size, radius: size * 1.6 });
    }
    return hits;
}
