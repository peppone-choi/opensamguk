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
    /** 같은 방 다시 읽기가 실패하면 받은 글은 두고 refreshError 만 단다(옛 화면 background 실패 규칙). */
    | { readonly kind: 'ready'; readonly view: CouncilView; readonly refreshError: Error | null };

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
            (view) => { if (alive) { shownRoom.current = room; setState({ kind: 'ready', view, refreshError: null }); } },
            (error: unknown) => {
                if (!alive) return;
                const err = error instanceof Error ? error : new Error('회의실을 불러오지 못했습니다.');
                // 첫 읽기 · 방 바꾸기 실패만 오류 화면. 같은 방 다시 읽기(순 갱신 · 새로고침)는 받은 글을 그대로 둔다.
                setState((s) => (s.kind === 'ready' && shownRoom.current === room ? { ...s, refreshError: err } : { kind: 'error', error: err }));
            },
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
                // 실패 · 거절이면 다음 다시 읽기 때 또 남긴다(완료로 치지 않는다).
                else if (!out || out.status === 'rejected') marked.current.delete(a.id);
            }
            if (alive && applied) reload();
        })();
        return () => { alive = false; };
    }, [state, generalId, reload]);

    return { state, reload };
}
