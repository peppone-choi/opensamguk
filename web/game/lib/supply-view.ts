// 창고망 · 보급(P-T04)의 보기 모델 — `/api/warehouses` 를 화면 줄로 바꾼다. React 없음.
//
// 서버는 창고마다 수도 여부(isCapital)와 수도와 이어졌는지(supplied)만 준다. 군 치소 · 야전 치중 구분, 끊긴 구간 · 까닭,
// 조각 이름은 계약판 K4-06(보급망 읽기) 전까지 짓지 않는다 — 화면은 「본망 · 끊김」 두 가지로만 가른다.

import { CAMPAIGN_RESOURCE_LABELS, type Stock, type Warehouses } from './campaign-reads';

export type WarehouseKind = 'capital' | 'county';

export interface WarehouseRow {
    readonly cityId: number;
    readonly name: string;
    readonly commanderyName: string | null;
    readonly kind: WarehouseKind;
    /** 수도와 이어졌는가(본망). false = 끊김 — 제 창고만 쓴다. */
    readonly connected: boolean;
    readonly stock: Stock;
}

export const WAREHOUSE_KIND_LABEL: Readonly<Record<WarehouseKind, string>> = { capital: '수도', county: '현' };

/** 수도 먼저(서버 순서를 믿되 한 번 더 세운다), 나머지는 서버 순서. */
export function warehouseRows(w: Warehouses): WarehouseRow[] {
    const rows = w.warehouses.map((x) => ({
        cityId: x.cityId,
        name: x.name,
        commanderyName: x.commanderyName,
        kind: (x.isCapital ? 'capital' : 'county') as WarehouseKind,
        connected: x.supplied,
        stock: x.stock,
    }));
    return [...rows.filter((r) => r.kind === 'capital'), ...rows.filter((r) => r.kind !== 'capital')];
}

/** 본망(수도와 이어진 창고) 합계. 이어진 창고가 없으면 null. */
export function connectedTotal(rows: readonly WarehouseRow[]): Stock | null {
    const on = rows.filter((r) => r.connected);
    if (on.length === 0) return null;
    const sum: Record<keyof Stock, number> = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
    for (const r of on) for (const { key } of CAMPAIGN_RESOURCE_LABELS) sum[key] += r.stock[key];
    return sum;
}

/** 끊긴 창고 — 「{이름}은 수도와 끊겨 제 창고만 씁니다」. */
export function cutRows(rows: readonly WarehouseRow[]): WarehouseRow[] {
    return rows.filter((r) => !r.connected);
}

const fmt = new Intl.NumberFormat('ko-KR');

/** 5자원 한 줄(모바일 카드) — 「금 1,200 · 쌀 800 · 철 0 · 목재 40 · 말 3」. 0 도 보인다(재고 표이므로). */
export function stockLine(s: Stock): string {
    return CAMPAIGN_RESOURCE_LABELS.map(({ key, label }) => `${label} ${fmt.format(s[key])}`).join(' · ');
}

export function stockCell(n: number): string {
    return fmt.format(n);
}
