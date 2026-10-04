'use client';

// 회의실 · 기밀실(P-Q01) 읽기 — 방을 바꾸면 다시 읽고, 새 순이 오면 조용히 다시 읽는다(받은 글은 지우지 않는다).
// 기밀실 글을 열면(보이면) 아직 안 읽은 글만 열람 기록을 한 번 남긴다(세션당 글마다 한 번 — 옛 화면과 같은 규칙).

import { useCallback, useEffect, useRef, useState } from 'react';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { markRead, readCouncil } from '@/lib/council-api';
import type { CouncilRoom, CouncilView } from '@/lib/council-model';

export type CouncilState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'error'; readonly error: Error }
    | { readonly kind: 'ready'; readonly view: CouncilView };

export function useCouncil(room: CouncilRoom, generalId: number | null) {
    const [state, setState] = useState<CouncilState>({ kind: 'loading' });
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    useTurnRefresh(reload);
    const shownRoom = useRef<CouncilRoom | null>(null);

    useEffect(() => {
        let alive = true;
        // 방이 바뀌면 앞 방의 글을 보이지 않는다. 같은 방 다시 읽기는 받은 글을 둔 채로.
        if (shownRoom.current !== room) setState({ kind: 'loading' });
        readCouncil(room).then(
            (view) => { if (alive) { shownRoom.current = room; setState({ kind: 'ready', view }); } },
            (error: unknown) => { if (alive) setState({ kind: 'error', error: error instanceof Error ? error : new Error('회의실을 불러오지 못했습니다.') }); },
        );
        return () => { alive = false; };
    }, [room, seq]);

    // 기밀실 열람 기록 — 내 이름이 열람한 사람에 없을 때만, 글마다 세션당 한 번.
    const marked = useRef(new Set<number>());
    useEffect(() => {
        if (state.kind !== 'ready' || state.view.room !== 'SECRET' || generalId === null || !state.view.access.canRead) return;
        const unread = state.view.articles.filter((a) => a.readers && !a.readers.read.some((p) => p.generalId === generalId) && !marked.current.has(a.id));
        if (unread.length === 0) return;
        for (const a of unread) marked.current.add(a.id);
        let alive = true;
        void (async () => {
            let applied = false;
            for (const a of unread) {
                const out = await markRead(generalId, a.id).catch(() => null);
                if (out?.status === 'applied') applied = true;
            }
            if (alive && applied) reload();
        })();
        return () => { alive = false; };
    }, [state, generalId, reload]);

    return { state, reload };
}
