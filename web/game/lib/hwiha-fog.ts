// 8방향 군국 이동.
//
// 지도는 군국 하나가 화면을 채우는 배율로 열리고, 화살표로 이웃 군국으로 옮긴다. 군국 표는 서버가
// 서빙하는 지형의 juns 에서 온다(`hwiha-map.ts` buildCommanderies) — 번호가 식별 PNG 와 같다.

import type { HwihaCommanderyCell } from './hwiha-map';

/** 화면의 8방향. 이름은 지도 위 방향 그대로다. */
export const HWIHA_DIRECTIONS = [
    { key: 'N', label: '북', dc: 0, dr: -1 },
    { key: 'NE', label: '북동', dc: 1, dr: -1 },
    { key: 'E', label: '동', dc: 1, dr: 0 },
    { key: 'SE', label: '남동', dc: 1, dr: 1 },
    { key: 'S', label: '남', dc: 0, dr: 1 },
    { key: 'SW', label: '남서', dc: -1, dr: 1 },
    { key: 'W', label: '서', dc: -1, dr: 0 },
    { key: 'NW', label: '북서', dc: -1, dr: -1 },
] as const;

export type HwihaDirection = (typeof HWIHA_DIRECTIONS)[number];

/**
 * [from] 에서 [dir] 쪽에 있는 가장 가까운 군국. 초점 城 이 없는 군국(城 없는 군국)은 옮겨 갈 수
 * 없으므로 후보에서 뺀다.
 *
 * 방향은 치소 칸 사이의 벡터로 본다. 벡터가 그 방향과 이루는 각이 45° 안일 때만 그 방향의
 * 후보로 세고(코사인 ≥ cos45°), 그중 가장 가까운 것을 고른다. 각을 재지 않고 부호만 보면
 * 거의 같은 방향에 있는 군국이 두 방향에 동시에 걸려 화살표가 같은 곳을 가리킨다.
 *
 * 없으면 undefined — 지도 끝이다. 화살표를 숨기지 않고 비활성으로 남긴다(표시 원칙).
 */
export function neighborInDirection(
    commanderies: readonly HwihaCommanderyCell[],
    from: HwihaCommanderyCell,
    dir: HwihaDirection,
): HwihaCommanderyCell | undefined {
    const dirLen = Math.hypot(dir.dc, dir.dr);
    let best: HwihaCommanderyCell | undefined;
    let bestDistance = Infinity;
    for (const candidate of commanderies) {
        if (candidate.no === from.no || candidate.focusCityId == null) continue;
        const dc = candidate.col - from.col;
        const dr = candidate.row - from.row;
        const len = Math.hypot(dc, dr);
        if (len === 0) continue;
        const cosine = (dc * dir.dc + dr * dir.dr) / (len * dirLen);
        if (cosine < Math.SQRT1_2) continue; // 45° 밖
        if (len < bestDistance) {
            bestDistance = len;
            best = candidate;
        }
    }
    return best;
}

/** 城 id 가 속한 군국 — 초점 城 이 같거나, 이름이 같은 군국. */
export function commanderyOfCity(
    commanderies: readonly HwihaCommanderyCell[],
    commanderyName: string | undefined,
): HwihaCommanderyCell | undefined {
    if (!commanderyName) return undefined;
    return commanderies.find((c) => c.name === commanderyName);
}
