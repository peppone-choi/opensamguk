'use client';

// 역사 인물 후보 목록(P-E03, 계약판 K5-03) — 거르기가 바뀌면 첫 쪽부터, 「더 보기」로 커서를 잇는다.
// 서버가 아직 없거나(404) 정책이 닫혔으면(503) waiting — 화면은 「생성 대기」를 보인다.

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api';
import { readHistoricalPage, type HistoricalQuery } from '@/lib/creation-api';
import type { HistoricalCreationPerson } from '@/lib/creation-contract';
import type { NationRef } from '@/lib/historical-view';

export type CandidatesState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'waiting'; readonly code: string | null; readonly message: string | null }
    | { readonly kind: 'error'; readonly error: Error }
    | {
        readonly kind: 'ready';
        readonly worldId: number;
        readonly people: readonly HistoricalCreationPerson[];
        readonly nextCursor: string | null;
        readonly loadingMore: boolean;
        readonly moreError: Error | null;
    };

/** 이름 찾기 입력을 멈춘 뒤 서버에 묻는 시간(ms). */
export const SEARCH_DEBOUNCE_MS = 300;

const asError = (e: unknown) => (e instanceof Error ? e : new Error('역사 인물 목록을 불러오지 못했습니다.'));

export function useHistoricalCandidates(query: HistoricalQuery) {
    const [state, setState] = useState<CandidatesState>({ kind: 'loading' });
    const [seq, setSeq] = useState(0);
    const generation = useRef(0);
    const { q, nation, status } = query;

    useEffect(() => {
        const controller = new AbortController();
        const mine = ++generation.current;
        setState({ kind: 'loading' });
        const timer = setTimeout(() => {
            readHistoricalPage({ q, nation, status }, null, controller.signal).then(
                (read) => {
                    if (mine !== generation.current) return;
                    setState(read.kind === 'waiting'
                        ? read
                        : { kind: 'ready', worldId: read.data.worldId, people: read.data.people, nextCursor: read.data.nextCursor, loadingMore: false, moreError: null });
                },
                (error: unknown) => { if (mine === generation.current && !controller.signal.aborted) setState({ kind: 'error', error: asError(error) }); },
            );
        }, q.trim() ? SEARCH_DEBOUNCE_MS : 0);
        return () => { clearTimeout(timer); controller.abort(); };
    }, [q, nation, status, seq]);

    const latest = useRef(state);
    latest.current = state;
    const loadMore = useCallback(() => {
        const current = latest.current;
        if (current.kind !== 'ready' || current.nextCursor === null || current.loadingMore) return;
        const mine = generation.current;
        setState({ ...current, loadingMore: true, moreError: null });
        readHistoricalPage({ q, nation, status }, current.nextCursor).then(
            (read) => {
                if (mine !== generation.current) return;
                setState((s) => {
                    if (s.kind !== 'ready') return s;
                    if (read.kind === 'waiting') return { kind: 'waiting', code: read.code, message: read.message };
                    // 커서는 등록순 이후만 준다 — 이미 받은 사람은 다시 넣지 않는다.
                    const seen = new Set(s.people.map((p) => p.historicalGeneralId));
                    return { ...s, people: [...s.people, ...read.data.people.filter((p) => !seen.has(p.historicalGeneralId))], nextCursor: read.data.nextCursor, loadingMore: false, moreError: null };
                });
            },
            (error: unknown) => {
                if (mine !== generation.current) return;
                setState((s) => (s.kind === 'ready' ? { ...s, loadingMore: false, moreError: asError(error) } : s));
            },
        );
    }, [q, nation, status]);

    const reload = useCallback(() => setSeq((n) => n + 1), []);
    return { state, loadMore, reload };
}

/**
 * 세력 이름 · 색 — 역사 후보는 세력 id 만 준다(#1137). 공개 지도 미리보기(`/api/map/preview`)의 세력표로 이름을 붙인다.
 * 읽지 못하면 빈 표 — 카드는 「소속 세력 확인 중」으로 두고 지어내지 않는다.
 */
export function useNationRefs(): ReadonlyMap<number, NationRef> {
    const [nations, setNations] = useState<ReadonlyMap<number, NationRef>>(() => new Map());
    useEffect(() => {
        const controller = new AbortController();
        api.mapPreview(controller.signal).then(
            (preview) => {
                if (controller.signal.aborted) return;
                setNations(new Map((preview.nations ?? []).filter((n) => n.id > 0).map((n) => [n.id, { id: n.id, name: n.name, color: n.color }])));
            },
            () => undefined,
        );
        return () => controller.abort();
    }, []);
    return nations;
}
