import type { DestinationRead } from './destination-view';

export type DestinationSort = 'distance' | 'turns' | 'name';
export type DestinationGroup = 'all' | 'nearby' | 'this-turn' | 'multi-turn';
interface Choice {
    readonly value: string;
    readonly label: string;
    readonly destination?: DestinationRead;
}

const names = new Intl.Collator('ko', { numeric: true, sensitivity: 'base' });
const metric = (value: number | null | undefined): number | null =>
    typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : null;
const compare = (a: number | null, b: number | null): number =>
    a === null ? (b === null ? 0 : 1) : b === null ? -1 : a - b;
const normalize = (value: string): string => value.normalize('NFKC').toLocaleLowerCase('ko-KR').replace(/\s/g, '');

/** 서버 거리/도착 판정만 분류하며, 필터링해도 원 ID·가능 여부·예약 초안은 바꾸지 않는다. */
export function destinationChoices<T extends Choice>(all: readonly T[], query: string, group: DestinationGroup, sort: DestinationSort): T[] {
    const byDistance = (a: T, b: T) => compare(metric(a.destination?.distanceMm), metric(b.destination?.distanceMm))
        || names.compare(a.label, b.label) || names.compare(a.value, b.value);
    const nearby = group === 'nearby' ? new Set([...all]
        .filter(c => metric(c.destination?.distanceMm) !== null).sort(byDistance).slice(0, 20).map(c => c.value)) : null;
    const needle = normalize(query);
    return all.filter(c => {
        const d = c.destination;
        if (nearby && !nearby.has(c.value)) return false;
        if (group === 'this-turn' && !(d?.available === true && d.reachability === 'THIS_TURN' && d.arrivesThisTurn === true)) return false;
        if (group === 'multi-turn' && !(d?.available === true && d.reachability === 'MULTI_TURN' && d.arrivesThisTurn === false)) return false;
        return !needle || normalize(c.label).includes(needle) || normalize(c.value).includes(needle);
    }).sort((a, b) => (sort === 'distance' ? byDistance(a, b) : sort === 'turns'
        ? compare(metric(a.destination?.estimatedTurns), metric(b.destination?.estimatedTurns)) || byDistance(a, b)
        : names.compare(a.label, b.label) || names.compare(a.value, b.value)));
}
