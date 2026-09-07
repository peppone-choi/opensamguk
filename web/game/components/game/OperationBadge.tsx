'use client';
// 작전실 조작 대상 바 「작전」 배지 (Phase 4X-B spec v4.1 §7): 진행 중 작전 수 + 가장 임박한 기한 → /game/my-nation#operations.
// 없으면 점선 + 사유(숨기지 않는다). 실패는 빈 상태로 위장하지 않는다.
import { useEffect, useState } from 'react';
import { api } from '@/lib/api';
import type { OperationsResponse } from '@/types/game';

type Props = { readonly generalId: number | null; readonly href: string };

export default function OperationBadge({ generalId, href }: Props) {
    const [state, setState] = useState<{ kind: 'loading' } | { kind: 'error' } | { kind: 'ok'; count: number; nearest: { title: string; remainingMonths: number | null } | null }>({ kind: 'loading' });
    useEffect(() => {
        if (generalId == null) { setState({ kind: 'error' }); return; }
        let alive = true;
        Promise.resolve()
            .then(() => api.operations<OperationsResponse>())
            .then((r) => {
                if (!alive) return;
                const open = r.operations.filter((o) => o.status === 'declared' || o.status === 'active');
                const nearest = open.slice().sort((a, b) => (a.remainingMonths ?? 99) - (b.remainingMonths ?? 99))[0] ?? null;
                setState({ kind: 'ok', count: open.length, nearest: nearest ? { title: nearest.title, remainingMonths: nearest.remainingMonths } : null });
            })
            .catch(() => { if (alive) setState({ kind: 'error' }); });
        return () => { alive = false; };
    }, [generalId]);

    if (state.kind !== 'ok') {
        const reason = state.kind === 'loading' ? '불러오는 중' : '작전 정보를 불러오지 못했습니다';
        return <button type="button" className="os-button os-button--ghost os-button--sm subject-target-operation" disabled aria-disabled="true" title={reason} data-reason={reason}>작전</button>;
    }
    if (state.count === 0) {
        return <a className="os-button os-button--ghost os-button--sm subject-target-operation subject-target-operation--empty" href={`${href}#operations`} title="수뇌부가 선언하면 나옵니다">작전 없음</a>;
    }
    return (
        <a className="os-button os-button--ghost os-button--sm subject-target-operation" href={`${href}#operations`} title="작전 진행 보기">
            작전 <b className="os-num">{state.count}</b>{state.nearest && <> · {state.nearest.title}{state.nearest.remainingMonths != null ? ` · ${state.nearest.remainingMonths}개월 남음` : ''}</>}
        </a>
    );
}
