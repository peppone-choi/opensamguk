'use client';

import { useState } from 'react';

/**
 * 화면 문맥의 수명 번호 — key(탭 서버 · 장수 · 주소 쿼리)가 바뀔 때마다 새 번호를 낸다. 같은 주소로 돌아와도(A→B→A)
 * 옛 번호를 다시 쓰지 않아, 옛 문맥에서 연 편집 · 늦게 온 결과가 되살아나지 않는다.
 * 렌더 중에 바꾼다 — 옛 문맥의 편집이 새 문맥과 함께 한 번도 커밋되지 않게(useCampaignRead 의 범위 교체와 같은 방식).
 */
export function useContextLifetime(key: string): number {
    const [life, setLife] = useState({ key, gen: 0 });
    if (life.key !== key) {
        const next = life.gen + 1;
        setLife({ key, gen: next });
        return next;
    }
    return life.gen;
}
