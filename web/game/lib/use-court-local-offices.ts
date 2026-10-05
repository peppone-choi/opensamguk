'use client';

// 지방 관직 탭 훅(K8-03) — 지방 관직 읽기(lib/api/court-local-offices) · 다시 시도, 치소 현 이름(지도 미리보기). 화면은 이 훅만 부른다(D105 층).
// 경로가 없으면(404, 배포 전) 「서버 대기」다 — 오류로 그리지 않는다. 200 이면 서버가 준 상태 그대로(NOT_SEEDED · UNAVAILABLE · READY).
// 지도 미리보기는 행이 있을 때만, 치소 현 이름(서버 seatCountyName 이 없을 때, 같은 현 id)에만 쓴다 — 관할 이름은 짐작하지 않는다.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { readCourtLocalOffices, type CourtLocalOffices } from './api/court-local-offices';
import { localOfficesView, type LocalOfficesView } from './court-local-offices-view';

export type CourtLocalOfficesState =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'ready'; readonly offices: CourtLocalOffices; readonly view: LocalOfficesView }
    | { readonly state: 'error'; readonly httpStatus: number | null };

type Read =
    | { readonly state: 'loading' }
    | { readonly state: 'waiting' }
    | { readonly state: 'ready'; readonly offices: CourtLocalOffices }
    | { readonly state: 'error'; readonly httpStatus: number | null };

export function useCourtLocalOffices(generalId: number | null): CourtLocalOfficesState & { readonly retry: () => void } {
    const [read, setRead] = useState<Read>({ state: 'loading' });
    const [names, setNames] = useState<ReadonlyMap<number, string>>(new Map());
    const [nonce, setNonce] = useState(0);
    const retry = useCallback(() => setNonce((n) => n + 1), []);

    useEffect(() => {
        if (generalId == null) return undefined;
        const controller = new AbortController();
        setRead({ state: 'loading' });
        readCourtLocalOffices(generalId, controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                if (!r.ok) {
                    setRead(r.httpStatus === 404 ? { state: 'waiting' } : { state: 'error', httpStatus: r.httpStatus });
                    return;
                }
                setRead({ state: 'ready', offices: r.offices });
                if ((r.offices.localOffices ?? []).length === 0) return;
                api.mapPreview(controller.signal)
                    .then((preview) => {
                        if (controller.signal.aborted) return;
                        setNames(new Map((preview?.cities ?? []).map((c) => [c.id, c.displayName ?? c.name] as const)));
                    })
                    .catch(() => undefined); // 이름을 못 받으면 치소 표시를 빼 둔다.
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, nonce]);

    const view = useMemo(
        () => (read.state === 'ready' ? localOfficesView(read.offices, (id) => names.get(id) ?? null) : null),
        [read, names],
    );
    if (read.state === 'ready') return { state: 'ready', offices: read.offices, view: view!, retry };
    return { ...read, retry };
}
