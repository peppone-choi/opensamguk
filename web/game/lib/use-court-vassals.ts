'use client';

// 봉신 탭 훅 — 봉신 저장 조건(lib/api/court-vassals) 읽기 · 다시 시도, 봉토 현 이름 · 봉토 지도(지도 미리보기). 화면은 이 훅만 부른다(D105 층).
// 지도 미리보기는 계약이 있을 때만 받는다. 못 받으면 현 이름은 「어느 현」이고 봉토 지도는 그리지 않는다(값을 짓지 않는다).
import { useCallback, useEffect, useState } from 'react';
import { api } from './api';
import { readCourtVassals, type CourtVassals } from './api/court-vassals';
import { vassalsView, type VassalsView } from './court-vassals-view';
import type { MapPreviewResponse } from './types';

export type CourtVassalsState =
    | { readonly state: 'loading' }
    | { readonly state: 'ready'; readonly vassals: CourtVassals; readonly view: VassalsView }
    | { readonly state: 'error'; readonly httpStatus: number | null };

export function useCourtVassals(generalId: number | null): CourtVassalsState & {
    readonly retry: () => void;
    readonly countyName: (countyId: number) => string | null;
    /** 봉토 지도용 미리보기(세력색 · bakeId). 아직 · 못 받으면 null. */
    readonly preview: MapPreviewResponse | null;
} {
    const [value, setValue] = useState<CourtVassalsState>({ state: 'loading' });
    const [names, setNames] = useState<ReadonlyMap<number, string>>(new Map());
    const [preview, setPreview] = useState<MapPreviewResponse | null>(null);
    const [nonce, setNonce] = useState(0);
    const retry = useCallback(() => setNonce((n) => n + 1), []);

    useEffect(() => {
        if (generalId == null) return undefined;
        const controller = new AbortController();
        setValue({ state: 'loading' });
        readCourtVassals(generalId, controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                if (!r.ok) {
                    setValue({ state: 'error', httpStatus: r.httpStatus });
                    return;
                }
                setValue({ state: 'ready', vassals: r.vassals, view: vassalsView(r.vassals) });
                if (r.vassals.contracts.length === 0) return;
                api.mapPreview(controller.signal)
                    .then((preview) => {
                        if (controller.signal.aborted) return;
                        setPreview(preview ?? null);
                        setNames(new Map((preview?.cities ?? []).map((c) => [c.id, c.displayName ?? c.name] as const)));
                    })
                    .catch(() => undefined); // 이름을 못 받으면 「어느 현」으로 둔다.
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, nonce]);

    const countyName = useCallback((id: number) => names.get(id) ?? null, [names]);
    return { ...value, retry, countyName, preview };
}
