import type { CommanderyVisibility, MapCorpsOverlay } from '@opensamguk/ui';
import { headingOf, type CorpsMarker } from '@opensamguk/ui/map/topdown';
import type { Corps } from './campaign-reads';

/** Defense in depth: the projected corps response is the only source, then current visibility gates it. */
export function buildVisibleCorps(
    corps: readonly Corps[] | undefined,
    visibility: ReadonlyMap<number, CommanderyVisibility> | null,
    provinceCenter: (provinceId: string) => { col: number; row: number } | undefined,
): MapCorpsOverlay[] {
    if (!corps || !visibility) return [];
    return corps.flatMap((corpsRow) => {
        const tier = visibility.get(corpsRow.commanderyNo);
        if (!tier || tier === 'FOG' || corpsRow.visibility === 'FOG') return [];
        const at = provinceCenter(corpsRow.provinceId);
        if (!at) return [];
        const path = corpsRow.marchPath?.map(provinceCenter)
            .filter((point): point is { col: number; row: number } => point != null);
        return [{
            id: corpsRow.corpsId, col: at.col, row: at.row,
            label: corpsRow.commanderName ?? corpsRow.ownerName ?? '군단',
            troopsLabel: corpsRow.troops != null ? `${corpsRow.troops.toLocaleString('ko-KR')}명` : corpsRow.troopsBand?.label,
            color: corpsRow.nationColor, own: corpsRow.own, stale: tier === 'INTEL' || corpsRow.visibility === 'INTEL', path,
        } satisfies MapCorpsOverlay];
    });
}

const UNKNOWN_NATION_COLOR = '#8e8879';

/**
 * 새 지도(탑다운) 부대 표지. bake 칸은 han-tiles col/row 격자 그대로라 옛 지도 省 중심 칸을 그대로 쓴다.
 * 행군 경로는 지금 칸에서 시작하므로 앞쪽의 지금 칸을 떼고 남은 칸만 넘긴다(방향은 첫 남은 칸 쪽).
 * 상태(ADR-LITE-049 개정 · 원장 §1 D34): 내 군단 = own, 마지막 목격 = intel(+ 「N순 전」, 서버 ageTurns), 나머지 = seen.
 * 병력 띠 글은 시야 거르기가 만든 그대로다 — 내 군단은 정확한 수(troops), 다른 세력은 병력대(troopsBand.label). 지어내지 않는다.
 */
export function toTopdownCorps(overlays: readonly MapCorpsOverlay[], ageTurnsById?: ReadonlyMap<string, number | undefined>): CorpsMarker[] {
    return overlays.map((overlay) => {
        const cell = { col: overlay.col, row: overlay.row };
        const path = overlay.path ?? [];
        let start = 0;
        while (start < path.length && path[start].col === cell.col && path[start].row === cell.row) start += 1;
        const route = path.slice(start);
        const standing = overlay.own ? 'own' : overlay.stale ? 'intel' : 'seen';
        const age = standing === 'intel' ? ageTurnsById?.get(overlay.id) : undefined;
        return {
            id: overlay.id, cell, nationColor: overlay.color ?? UNKNOWN_NATION_COLOR, leaderName: overlay.label,
            heading: headingOf(cell, route), ...(route.length ? { route } : {}), standing,
            ...(overlay.troopsLabel ? { troopsLabel: overlay.troopsLabel } : {}),
            ...(age != null ? { ageLabel: `${age}순 전` } : {}),
        };
    });
}
