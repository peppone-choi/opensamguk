'use client';

// 명령 흐름 여닫기를 주소(?do · slot · target)로 한다 — 새로고침 · 뒤로 가기 · 공유에 흐름이 남는다(설계서 §2.1 URL).
// 여는 곳(12순 목록 「+ 예약」 · 채운 순 · 지도 「여기로 명령」 · 인물 「이 사람에게」 · 도움말 「이 명령 하러 가기」)은
// openFlow만 부르고, 작전실은 query.open일 때 <CommandFlow>를 12순 열 자리에 그린다.
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useMemo } from 'react';
import { parseFlowQuery, withFlowQuery, type FlowQuery, type FlowTarget } from './url';

export interface OpenFlow {
    readonly inputId?: string | null;
    /** 0–11. 없으면 흐름이 다음 빈 순을 고른다. */
    readonly slot?: number | null;
    readonly target?: FlowTarget | null;
}

export interface FlowQueryControls {
    readonly query: FlowQuery;
    readonly openFlow: (open?: OpenFlow) => void;
    readonly closeFlow: () => void;
    /** 흐름 안에서 명령 · 순이 바뀔 때 — 기록을 쌓지 않고 바꾼다. */
    readonly syncFlow: (flow: { inputId: string | null; slot: number }) => void;
}

export function useFlowQuery(): FlowQueryControls {
    const router = useRouter();
    const pathname = usePathname();
    const params = useSearchParams();
    const query = useMemo(() => parseFlowQuery(new URLSearchParams(params?.toString() ?? '')), [params]);

    const go = useCallback((next: URLSearchParams, push: boolean) => {
        const qs = next.toString();
        const href = qs ? `${pathname}?${qs}` : pathname;
        if (push) router.push(href, { scroll: false }); else router.replace(href, { scroll: false });
    }, [pathname, router]);

    const current = useCallback(() => new URLSearchParams(params?.toString() ?? ''), [params]);

    const openFlow = useCallback((open: OpenFlow = {}) => {
        const next = withFlowQuery(current(), { inputId: open.inputId ?? null, slot: open.slot ?? 0, target: open.target ?? null });
        // 순을 정하지 않고 열면 slot을 비워 둔다 — 흐름이 12순을 읽고 다음 빈 순을 고른다.
        if (open.slot == null) next.delete('slot');
        if (!next.has('do') && !next.has('slot') && !next.has('target')) next.set('do', '');
        go(next, true);
    }, [current, go]);

    const closeFlow = useCallback(() => go(withFlowQuery(current(), null), false), [current, go]);

    const syncFlow = useCallback((flow: { inputId: string | null; slot: number }) => {
        const was = current();
        const next = withFlowQuery(was, { inputId: flow.inputId, slot: flow.slot, target: parseFlowQuery(was).target });
        if (!flow.inputId) next.set('do', '');
        if (next.toString() !== was.toString()) go(next, false);
    }, [current, go]);

    return { query, openFlow, closeFlow, syncFlow };
}
