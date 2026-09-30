// 도로 · 보루 공사의 인자 후보(K0 2026-10-01 결정 (b)) — road-forts 의 접경 · 길목을 K3 TargetCandidate 로 바꾼다. React 없음.
//
// 화면 글자는 구역 한글 이름만: 「○○ ↔ △△ 접경」 · 「○○ ↔ △△ 접경 길목 n」. 내부 edgeId · 노선 slug · 좌표는 targetId 에만
// 들고(제출용) 화면에 내지 않는다. 이름을 못 풀면 후보에서 빼지 않고 「이름 모를 구역」. 불가도 사유와 함께 보인다.
// 지도에서 고르기(PickBar)는 K2 지도 고르기가 오면 같은 후보에 붙는다. 서버 후보 API(U-01)가 오면 그것으로 바꾼다.
// 후보 규칙은 옛 DomesticPanels 그대로: 도로 = 이 현에 닿는 접경(buildable · 아직 도로 없음이 가능),
// 보루 = 도로가 난 접경의 이 현 쪽 길목 칸(이미 보루가 있으면 불가).

import type { TargetCandidate } from '@opensamguk/ui';
import type { CountyWorks, RoadForts } from './campaign-reads';

export const UNKNOWN_PROVINCE = '이름 모를 구역';

type Namer = (provinceId: string) => string | null;

function edgeName(gate: RoadForts['gates'][number], county: Pick<CountyWorks, 'provinceIds'>, name: Namer): string {
    // 이 현 쪽을 앞에.
    const [here, there] = county.provinceIds.includes(gate.toProvinceId) && !county.provinceIds.includes(gate.fromProvinceId)
        ? [gate.toProvinceId, gate.fromProvinceId] : [gate.fromProvinceId, gate.toProvinceId];
    return `${name(here) ?? UNKNOWN_PROVINCE} ↔ ${name(there) ?? UNKNOWN_PROVINCE} 접경`;
}

/**
 * 이름을 못 풀어 같은 이름이 된 후보끼리 가를 수 있게, 겹치는 이름에만 순번을 붙인다(「… 접경 1」 · 「… 접경 2」).
 * 방향(북 · 동 · 남 · 서)은 구역 중심 좌표가 있어야 해서 지금은 쓰지 않는다. 내부 id 는 여전히 싣지 않는다.
 */
function numberDuplicates(list: TargetCandidate[]): TargetCandidate[] {
    const total = new Map<string, number>();
    for (const c of list) total.set(c.name, (total.get(c.name) ?? 0) + 1);
    const seen = new Map<string, number>();
    return list.map((c) => {
        if ((total.get(c.name) ?? 0) < 2) return c;
        const n = (seen.get(c.name) ?? 0) + 1;
        seen.set(c.name, n);
        return { ...c, name: `${c.name} ${n}` };
    });
}

export function roadCandidates(roads: RoadForts, county: Pick<CountyWorks, 'provinceIds'>, name: Namer): TargetCandidate[] {
    return numberDuplicates(roads.gates
        .filter((g) => county.provinceIds.includes(g.fromProvinceId) || county.provinceIds.includes(g.toProvinceId))
        .map((g) => {
            const reason = g.active ? '이미 도로가 난 접경입니다.' : !g.buildable ? '도로를 낼 수 없는 접경입니다.' : undefined;
            return { targetKind: 'place' as const, targetId: `road|${g.edgeId}`, name: edgeName(g, county, name), available: reason == null, reason };
        }));
}

export function fortCandidates(roads: RoadForts, county: Pick<CountyWorks, 'provinceIds'>, name: Namer): TargetCandidate[] {
    return numberDuplicates(roads.gates.filter((g) => g.active).flatMap((g) => {
        const cells = g.fortCells.filter((c) => county.provinceIds.includes(c.provinceId));
        return cells.map((c, i) => {
            const taken = roads.forts.some((f) => f.row === c.row && f.col === c.col);
            return {
                targetKind: 'place' as const,
                targetId: `fort|${g.edgeId}|${c.row}|${c.col}`,
                name: cells.length > 1 ? `${edgeName(g, county, name)} 길목 ${i + 1}` : `${edgeName(g, county, name)} 길목`,
                available: !taken,
                reason: taken ? '이미 보루가 있는 길목입니다.' : undefined,
            };
        });
    }));
}

/** 고른 후보 → 공사 입력의 더할 인자(`{edgeId}` · `{edgeId, row, col}`). 모르는 모양이면 null. */
export function candidateBody(targetId: string | null): Readonly<Record<string, unknown>> | null {
    if (!targetId) return null;
    const [kind, edgeId, row, col] = targetId.split('|');
    if (kind === 'road' && edgeId) return { edgeId };
    if (kind === 'fort' && edgeId && row != null && col != null) return { edgeId, row: Number(row), col: Number(col) };
    return null;
}
