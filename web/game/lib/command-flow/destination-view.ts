/** Destination order legality and arrival estimates come from the same server assessment. */
export interface DestinationRead {
    readonly available?: boolean;
    readonly reachability?: 'THIS_TURN' | 'MULTI_TURN' | 'UNAVAILABLE' | null;
    readonly arrivesThisTurn?: boolean | null;
    readonly distanceMm?: number | null;
    readonly costMm?: number | null;
    readonly estimatedTurns?: number | null;
    readonly forcedFatigueDelta?: number | null;
    readonly forcedMoraleDelta?: number | null;
    readonly afterFatigue?: number | null;
    readonly afterMorale?: number | null;
}

export function destinationRange(d: DestinationRead): string {
    if (d.available === false) return '주문 불가';
    if (d.reachability === 'THIS_TURN' && d.arrivesThisTurn === true) return '이번 턴 도착';
    if (d.reachability === 'MULTI_TURN' && d.arrivesThisTurn === false) return '다턴 이동 · 이번 턴 미도착';
    return '도달 정보 미확인';
}

function distance(label: string, mm: number | null | undefined): string | null {
    if (typeof mm !== 'number' || !Number.isSafeInteger(mm) || mm < 0) return null;
    return `${label} ${(mm / 1_000_000).toLocaleString('ko-KR', { maximumFractionDigits: 6 })}km`;
}

export function destinationDetail(d: DestinationRead): string {
    const turns = typeof d.estimatedTurns === 'number' && Number.isSafeInteger(d.estimatedTurns) && d.estimatedTurns >= 0
        ? `예상 ${d.estimatedTurns.toLocaleString('ko-KR')}순` : null;
    return [destinationRange(d), turns, forcedCost(d), distance('경로 거리', d.distanceMm)]
        .filter(Boolean).join(' · ');
}

/** Only a complete, valid server projection is shown; terrain cost is not a player speed. */
function forcedCost(d: DestinationRead): string | null {
    const valid = (v: number | null | undefined, min: number, max: number): v is number =>
        typeof v === 'number' && Number.isSafeInteger(v) && v >= min && v <= max;
    if (!valid(d.forcedFatigueDelta, 0, 100) || !valid(d.forcedMoraleDelta, -100, 0)
        || !valid(d.afterFatigue, 0, 100) || !valid(d.afterMorale, 0, 100)) return null;
    return `강행 비용 피로 +${d.forcedFatigueDelta} → ${d.afterFatigue} · 사기 ${d.forcedMoraleDelta} → ${d.afterMorale}`;
}

/** 표시명 누락을 지명으로 추정하지 않는다. 전송할 원 ID는 바꾸지 않는다. */
export function destinationLabel(id: string, name: string | null | undefined): string {
    const label = typeof name === 'string' ? name.trim() : null;
    return label && label !== id ? label : `이름 미확인 · 구역 ${id}`;
}

/** 목록에서는 예상 턴을 먼저 보여주고, 정확한 상세와 서버 계산값은 별도로 유지한다. */
export function destinationSummary(d: DestinationRead): string {
    const turns = typeof d.estimatedTurns === 'number' && Number.isSafeInteger(d.estimatedTurns) && d.estimatedTurns >= 0
        ? `예상 ${d.estimatedTurns.toLocaleString('ko-KR')}순` : null;
    const compact = (label: string, mm: number | null | undefined): string | null => {
        if (typeof mm !== 'number' || !Number.isSafeInteger(mm) || mm < 0) return null;
        const km = mm / 1_000_000;
        const value = km > 0 && km < 0.01 ? '0.01km 미만' : `${km.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}km`;
        return `${label} ${value}`;
    };
    return [destinationRange(d), turns, forcedCost(d), compact('경로 거리', d.distanceMm)]
        .filter(Boolean).join(' · ');
}
