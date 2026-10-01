// 외교(P-K02) 관계 모델 — 계약판 K6-05(`/api/diplomacy/relations`, 휘하 상태)가 오기 전까지 지금 읽기
// `/api/diplomacy/conflict`의 관계 코드를 글자 칩으로 옮긴다. K6 설계서 §3.7.
// - 뜻이 확인된 코드만 이름을 붙인다(0 교전 · 1 선전포고 유예 · 2 관계 없음 · 7 불가침). 나머지는 「알 수 없음」.
// - 국력 · 분쟁 기여(삼모 규칙)는 옮기지 않는다. 다른 두 세력 사이는 서버가 교전 · 선포만 보인다.
import type { DiplomacyConflictResponse } from '../../types/game';

export type RelationKind = 'war' | 'declared' | 'none' | 'nonAggression' | 'unknown';

export const RELATION_LABEL: Readonly<Record<RelationKind, string>> = {
    war: '교전', declared: '선전포고 유예', none: '관계 없음', nonAggression: '불가침', unknown: '알 수 없음',
};

export function relationOf(code: number | undefined): RelationKind {
    switch (code) {
        case 0: return 'war';
        case 1: return 'declared';
        case 2: return 'none';
        case 7: return 'nonAggression';
        default: return 'unknown';
    }
}

export interface NationRelationRow {
    readonly nationId: number;
    readonly name: string;
    readonly color: string;
    readonly relation: RelationKind;
    readonly counties: readonly string[];
}

export interface OthersAtWar {
    readonly a: { readonly id: number; readonly name: string };
    readonly b: { readonly id: number; readonly name: string };
    readonly relation: 'war' | 'declared';
}

/** 세력 × 세력 표(옛 「외교 현황」 행렬) — 행 세력이 열 세력을 보는 관계. 같은 세력 = self, 서버가 안 준 칸 = null. */
export interface RelationMatrix {
    readonly nations: readonly { readonly id: number; readonly name: string; readonly color: string }[];
    readonly cells: Readonly<Record<number, Readonly<Record<number, RelationKind | 'self' | null>>>>;
}

export type RelationsView =
    | { readonly state: 'stateless' }
    | {
        readonly state: 'ready';
        readonly me: { readonly id: number; readonly name: string };
        readonly rows: readonly NationRelationRow[];
        readonly othersAtWar: readonly OthersAtWar[];
        readonly matrix: RelationMatrix;
    };

export function toRelations(res: DiplomacyConflictResponse): RelationsView {
    const meId = res.myNationID;
    const mine = res.nations.find((n) => n.nation === meId);
    if (!meId || !mine) return { state: 'stateless' };
    const byId = new Map(res.nations.map((n) => [n.nation, n]));
    const rows = res.nations.filter((n) => n.nation !== meId).map((n) => ({
        nationId: n.nation, name: n.name, color: n.color, relation: relationOf(res.diplomacyList[meId]?.[n.nation]), counties: n.cities,
    }));
    const othersAtWar: OthersAtWar[] = [];
    for (const [aKey, row] of Object.entries(res.diplomacyList)) {
        const a = Number(aKey);
        if (a === meId) continue;
        for (const [bKey, code] of Object.entries(row)) {
            const b = Number(bKey);
            if (b === meId || b <= a) continue;
            const kind = relationOf(code);
            const na = byId.get(a); const nb = byId.get(b);
            if ((kind === 'war' || kind === 'declared') && na && nb) {
                othersAtWar.push({ a: { id: a, name: na.name }, b: { id: b, name: nb.name }, relation: kind });
            }
        }
    }
    const cells: Record<number, Record<number, RelationKind | 'self' | null>> = {};
    for (const a of res.nations) {
        cells[a.nation] = {};
        for (const b of res.nations) {
            const code = res.diplomacyList[a.nation]?.[b.nation];
            cells[a.nation][b.nation] = a.nation === b.nation ? 'self' : code === undefined ? null : relationOf(code);
        }
    }
    const matrix: RelationMatrix = { nations: res.nations.map((n) => ({ id: n.nation, name: n.name, color: n.color })), cells };
    return { state: 'ready', me: { id: meId, name: mine.name }, rows, othersAtWar, matrix };
}

/**
 * 표 칸 글자 — 우리 세력이 낀 칸은 관계 이름을 다 보이고, 다른 두 세력 사이는 교전 · 선포만 보인다(서버가 나머지를 가린다 —
 * 옛 화면 neutralStateCharMap 과 같은 규칙). 좁은 표라 짧게 쓴다.
 */
export const RELATION_SHORT: Readonly<Record<RelationKind, string>> = {
    war: '교전', declared: '선포', none: '관계 없음', nonAggression: '불가침', unknown: '모름',
};
export function matrixCellText(kind: RelationKind | 'self' | null, involvesMe: boolean): string {
    if (kind === 'self' || kind === null) return '';
    if (!involvesMe && kind !== 'war' && kind !== 'declared') return '';
    return RELATION_SHORT[kind];
}

/** 제의 5종 — 관계에 맞는 것만 행에 둔다. 원장에서 모두 PLANNED(단추는 「준비 중」). */
export const PROPOSALS = [
    { inputId: 'court.diplomacy', label: '원조', when: (_r: RelationKind) => true, danger: false },
    { inputId: 'court.nonAggression', label: '불가침 제의', when: (r: RelationKind) => r === 'none' || r === 'declared', danger: false },
    { inputId: 'court.offerPeace', label: '종전 제의', when: (r: RelationKind) => r === 'war' || r === 'declared', danger: false },
    { inputId: 'court.breakNonAggression', label: '불가침 파기', when: (r: RelationKind) => r === 'nonAggression', danger: true },
    { inputId: 'court.declareWar', label: '선전포고', when: (r: RelationKind) => r === 'none', danger: true },
] as const;

export function proposalsFor(relation: RelationKind) {
    return PROPOSALS.filter((p) => relation !== 'unknown' && p.when(relation));
}
