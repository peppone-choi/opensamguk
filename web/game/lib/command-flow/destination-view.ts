/** Destination order legality and arrival estimates come from the same server assessment. */
export interface DestinationRead {
    readonly available?: boolean;
    readonly reachability?: 'THIS_TURN' | 'MULTI_TURN' | 'UNAVAILABLE' | null;
    readonly arrivesThisTurn?: boolean | null;
    readonly distanceMm?: number | null;
    readonly costMm?: number | null;
    readonly estimatedTurns?: number | null;
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
        ? `예상 ${d.estimatedTurns.toLocaleString('ko-KR')}턴` : null;
    return [destinationRange(d), distance('거리', d.distanceMm), distance('지형 반영 비용', d.costMm), turns]
        .filter(Boolean).join(' · ');
}
