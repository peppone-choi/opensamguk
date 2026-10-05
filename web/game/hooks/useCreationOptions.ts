'use client';

// 생성 옵션(P-E02, 계약판 K5-02) — 서버가 아직 없거나(404) 정책이 닫혔으면(503) waiting(「생성 대기」).

import { useCallback, useEffect, useState } from 'react';
import { readCreationOptions } from '@/lib/creation-api';
import type { GeneralCreationOptions } from '@/lib/creation-contract';

export type OptionsState =
    | { readonly kind: 'loading' }
    | { readonly kind: 'waiting'; readonly code: string | null; readonly message: string | null }
    | { readonly kind: 'error'; readonly error: Error }
    | { readonly kind: 'ready'; readonly data: GeneralCreationOptions };

export function useCreationOptions() {
    const [state, setState] = useState<OptionsState>({ kind: 'loading' });
    const [seq, setSeq] = useState(0);
    useEffect(() => {
        const controller = new AbortController();
        setState({ kind: 'loading' });
        readCreationOptions(controller.signal).then(
            (read) => { if (!controller.signal.aborted) setState(read.kind === 'waiting' ? read : { kind: 'ready', data: read.data }); },
            (error: unknown) => {
                if (!controller.signal.aborted) setState({ kind: 'error', error: error instanceof Error ? error : new Error('생성 옵션을 불러오지 못했습니다.') });
            },
        );
        return () => controller.abort();
    }, [seq]);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    return { state, reload };
}
