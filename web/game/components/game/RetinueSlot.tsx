'use client';
// D3-17 조작 대상 바 「휘하」 슬롯 (Phase 4X-A spec v3 §7). 가신 수·부곡 수 배지 + /game/my#retinue 링크.
// 없으면 점선 + 사유(숨기지 않는다). 원천 /api/my-retinue.
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { RetinueResponse } from '@/types/game';

type Props = { readonly generalId: number | null; readonly href: string };

export default function RetinueSlot({ generalId, href }: Props) {
    const [counts, setCounts] = useState<{ retainers: number; bugoks: number } | null>(null);
    useEffect(() => {
        if (generalId == null) { setCounts(null); return; }
        let alive = true;
        // 실패(미로그인·테스트의 부분 mock)는 「휘하 없음」 점선으로 남는다 — 숨기지 않는다.
        Promise.resolve()
            .then(() => api.myRetinue<RetinueResponse>())
            .then((r) => { if (alive) setCounts({ retainers: r.retainers.length, bugoks: r.bugoks.length }); })
            .catch(() => { if (alive) setCounts(null); });
        return () => { alive = false; };
    }, [generalId]);

    if (counts == null || (counts.retainers === 0 && counts.bugoks === 0)) {
        return (
            <button type="button" className="os-button os-button--ghost os-button--sm subject-target-retinue" disabled aria-disabled="true" title="서약하면 여기 나옵니다">
                휘하 없음
            </button>
        );
    }
    return (
        <a className="os-button os-button--ghost os-button--sm subject-target-retinue" href={`${href}#retinue`} title="휘하 인물·부곡 보기">
            휘하 <b className="os-num">{counts.retainers}</b>명 · 부곡 <b className="os-num">{counts.bugoks}</b>
        </a>
    );
}
