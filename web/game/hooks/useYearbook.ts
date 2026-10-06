'use client';

// 연감(P-H02, 계약판 K5-08) — 발행된 해 목록 → 고른 해의 연감(판도 · 그해 큰 사건, 커서로 더 보기).
// 서버 경로가 아직 없으면(404 · 503) waiting — 화면은 「연감을 준비하고 있습니다」.

import { useCallback, useEffect, useRef, useState } from 'react';
import type { GameEvent } from '@opensamguk/ui';
import { readYearbook, readYearbookYears } from '@/lib/yearbook-api';
import type { YearbookTerritory, YearbookYear } from '@/lib/yearbook-contract';
import { publishedYears } from '@/lib/yearbook-view';

export type YearsState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'waiting' }
    | { readonly kind: 'error'; readonly error: Error }
    | { readonly kind: 'ready'; readonly years: readonly YearbookYear[]; readonly published: readonly number[] };

export type YearState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'waiting' }
    | { readonly kind: 'error'; readonly error: Error }
    | {
        readonly kind: 'ready';
        readonly territory: readonly YearbookTerritory[];
        readonly events: readonly GameEvent[];
        readonly nextCursor: string | null;
        readonly loadingMore: boolean;
        readonly moreError: Error | null;
    };

const asError = (e: unknown, fallback: string) => (e instanceof Error ? e : new Error(fallback));

export function useYearbook() {
    const [years, setYears] = useState<YearsState>({ kind: 'loading' });
    const [yearsSeq, setYearsSeq] = useState(0);
    const [year, setYear] = useState<number | null>(null);
    const [page, setPage] = useState<YearState>({ kind: 'loading' });
    const [pageSeq, setPageSeq] = useState(0);
    const generation = useRef(0);

    useEffect(() => {
        const controller = new AbortController();
        setYears({ kind: 'loading' });
        readYearbookYears(controller.signal).then(
            (read) => {
                if (controller.signal.aborted) return;
                if (read.kind === 'waiting') { setYears({ kind: 'waiting' }); return; }
                const published = publishedYears(read.data);
                setYears({ kind: 'ready', years: read.data, published });
                // 처음엔 가장 최근에 나온 해.
                setYear((current) => (current !== null && published.includes(current) ? current : published.at(-1) ?? null));
            },
            (error: unknown) => { if (!controller.signal.aborted) setYears({ kind: 'error', error: asError(error, '연감 목록을 불러오지 못했습니다.') }); },
        );
        return () => controller.abort();
    }, [yearsSeq]);

    useEffect(() => {
        if (year === null) return;
        const controller = new AbortController();
        const mine = ++generation.current;
        setPage({ kind: 'loading' });
        readYearbook(year, null, controller.signal).then(
            (read) => {
                if (mine !== generation.current) return;
                setPage(read.kind === 'waiting'
                    ? { kind: 'waiting' }
                    : { kind: 'ready', territory: read.data.territory, events: read.data.events, nextCursor: read.data.nextCursor, loadingMore: false, moreError: null });
            },
            (error: unknown) => { if (mine === generation.current && !controller.signal.aborted) setPage({ kind: 'error', error: asError(error, '연감을 불러오지 못했습니다.') }); },
        );
        return () => controller.abort();
    }, [year, pageSeq]);

    const latest = useRef(page);
    latest.current = page;
    const loadMore = useCallback(() => {
        const current = latest.current;
        if (year === null || current.kind !== 'ready' || current.nextCursor === null || current.loadingMore) return;
        const mine = generation.current;
        setPage({ ...current, loadingMore: true, moreError: null });
        readYearbook(year, current.nextCursor).then(
            (read) => {
                if (mine !== generation.current) return;
                setPage((s) => {
                    if (s.kind !== 'ready') return s;
                    if (read.kind === 'waiting') return { kind: 'waiting' };
                    const seen = new Set(s.events.map((e) => e.id));
                    return { ...s, events: [...s.events, ...read.data.events.filter((e) => !seen.has(e.id))], nextCursor: read.data.nextCursor, loadingMore: false };
                });
            },
            (error: unknown) => {
                if (mine !== generation.current) return;
                setPage((s) => (s.kind === 'ready' ? { ...s, loadingMore: false, moreError: asError(error, '사건을 더 불러오지 못했습니다.') } : s));
            },
        );
    }, [year]);

    return {
        years,
        year,
        setYear,
        page,
        loadMore,
        reloadYears: useCallback(() => setYearsSeq((n) => n + 1), []),
        reloadPage: useCallback(() => setPageSeq((n) => n + 1), []),
    };
}
