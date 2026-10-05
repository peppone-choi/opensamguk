// 작전실 새 지도 「보급선」 층의 보기 모델 — `/api/warehouses` 창고별 연결(계약판 K4-06 `links`)을 지도 선으로 바꾼다. React 없음.
// 서버가 연결 칸을 아직 주지 않으면(links 없음) null — 화면은 「서버 대기 · K4-06」. 칸이 생기면 고칠 것 없이 바로 그린다.
// 끊긴 까닭은 서버 문구(cutReason)만 쓴다. 짓지 않는다.

import type { SupplyLink, Warehouses } from './campaign-reads';

export interface SupplyLine {
    readonly fromCityId: number;
    readonly toCityId: number;
    readonly via: SupplyLink['via'];
    readonly state: SupplyLink['state'];
    /** 끊긴 까닭 — 서버 문구 그대로, 없으면 null. */
    readonly cutReason: string | null;
}

const VIA: ReadonlySet<string> = new Set(['ROAD', 'WATER']);
const STATE: ReadonlySet<string> = new Set(['OPEN', 'CUT']);

/**
 * 창고 읽기 → 보급선. 읽기가 READY 가 아니거나 어느 창고에도 연결 칸(links)이 없으면 null(서버 대기).
 * 연결 칸이 있으면(빈 배열 포함) 목록 — 모르는 길 · 상태, 정수가 아닌 城 번호, 제 자신으로 가는 연결은 버린다.
 * 같은 두 城 · 길 · 상태 · 까닭이 양쪽 창고에서 오면 하나만 남긴다.
 */
export function supplyLinesOf(read: Warehouses | null | undefined): SupplyLine[] | null {
    if (!read || read.status !== 'READY') return null;
    if (!read.warehouses.some((warehouse) => Array.isArray(warehouse.links))) return null;
    const seen = new Set<string>();
    const lines: SupplyLine[] = [];
    for (const warehouse of read.warehouses) {
        for (const link of warehouse.links ?? []) {
            if (!Number.isInteger(link.toCityId) || link.toCityId === warehouse.cityId || !VIA.has(link.via) || !STATE.has(link.state)) continue;
            const cutReason = link.state === 'CUT' && typeof link.cutReason === 'string' && link.cutReason.trim() ? link.cutReason.trim() : null;
            const [a, b] = warehouse.cityId < link.toCityId ? [warehouse.cityId, link.toCityId] : [link.toCityId, warehouse.cityId];
            const key = `${a}-${b}-${link.via}-${link.state}-${cutReason ?? ''}`;
            if (seen.has(key)) continue;
            seen.add(key);
            lines.push({ fromCityId: warehouse.cityId, toCityId: link.toCityId, via: link.via, state: link.state, cutReason });
        }
    }
    return lines;
}
