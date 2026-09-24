import type { CommanderyVisibility, MapCorpsOverlay } from '@opensamguk/ui';
import type { HwihaCorps } from './hwiha-reads';

/** Defense in depth: the projected corps response is the only source, then current visibility gates it. */
export function buildVisibleCorps(
    corps: readonly HwihaCorps[] | undefined,
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
