// 전장의 안개와 8방향 군국 이동.
//
// 안개는 **군국 단위**다. 격자는 768×669 = 513,792 칸이라 사람마다 칸을 기억하면 터지지만,
// 군국은 173 개뿐이라 1인당 들고 있어도 가볍다. 지도도 군국 하나가 화면을 채우는 배율로 열린다.

import { HWIHA_COMMANDERIES, type HwihaCommandery } from './hwiha-commanderies';

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

const BY_NO = new Map(HWIHA_COMMANDERIES.map((c) => [c.no, c]));

export function commanderyOf(no: number): HwihaCommandery | undefined {
    return BY_NO.get(no);
}

/**
 * [from] 에서 [dir] 쪽에 있는 가장 가까운 군국.
 *
 * 방향은 치소 칸 사이의 벡터로 본다. 벡터가 그 방향과 이루는 각이 45° 안일 때만 그 방향의
 * 후보로 세고(코사인 ≥ cos45°), 그중 가장 가까운 것을 고른다. 각을 재지 않고 부호만 보면
 * 거의 같은 방향에 있는 군국이 두 방향에 동시에 걸려 화살표가 같은 곳을 가리킨다.
 *
 * 없으면 undefined — 지도 끝이다. 화살표를 숨기지 않고 비활성으로 남긴다(표시 원칙).
 */
export function neighborInDirection(
    from: HwihaCommandery,
    dir: HwihaDirection,
): HwihaCommandery | undefined {
    const dirLen = Math.hypot(dir.dc, dir.dr);
    let best: HwihaCommandery | undefined;
    let bestDistance = Infinity;
    for (const candidate of HWIHA_COMMANDERIES) {
        if (candidate.no === from.no) continue;
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
