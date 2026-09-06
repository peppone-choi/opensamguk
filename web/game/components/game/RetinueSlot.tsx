'use client';
// D3-17 조작 대상 바 「휘하」 슬롯 (Phase 4X-A spec v3 §7). 가신 수·부곡 수 배지 + /game/my#retinue 링크.
// 없으면 점선 + 사유(숨기지 않는다). 원천 /api/my-retinue.
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { RetinueResponse } from '@/types/game';

type Props = { readonly generalId: number | null; readonly href: string };

export default function RetinueSlot({ generalId, href }: Props) {
    const [state, setState] = useState<{ kind: 'loading' } | { kind: 'error' } | { kind: 'ok'; retainers: number; bugoks: number }>({ kind: 'loading' });
    useEffect(() => {
        if (generalId == null) { setState({ kind: 'error' }); return; }
        let alive = true;
        // 실패는 빈 상태로 위장하지 않는다 — 점선 + 「불러오지 못했습니다」. 빈 상태는 서약으로 가는 링크다.
        Promise.resolve()
            .then(() => api.myRetinue<RetinueResponse>())
            .then((r) => { if (alive) setState({ kind: 'ok', retainers: r.retainers.length, bugoks: r.bugoks.length }); })
            .catch(() => { if (alive) setState({ kind: 'error' }); });
        return () => { alive = false; };
    }, [generalId]);

    if (state.kind !== 'ok') {
        const reason = state.kind === 'loading' ? '불러오는 중' : '휘하 정보를 불러오지 못했습니다';
        return (
            <button type="button" className="os-button os-button--ghost os-button--sm subject-target-retinue" disabled aria-disabled="true" title={reason} data-reason={reason}>
                휘하
            </button>
        );
    }
    const counts = state;
    if (counts.retainers === 0 && counts.bugoks === 0) {
        return (
            <a className="os-button os-button--ghost os-button--sm subject-target-retinue subject-target-retinue--empty" href={`${href}#retinue`} title="서약하면 여기 나옵니다">
                휘하 없음 · 서약
            </a>
        );
    }
    return (
        <a className="os-button os-button--ghost os-button--sm subject-target-retinue" href={`${href}#retinue`} title="휘하 인물·부곡 보기">
            휘하 <b className="os-num">{counts.retainers}</b>명 · 부곡 <b className="os-num">{counts.bugoks}</b>
        </a>
    );
}
