'use client';

// 지방 관직 탭 훅(K8-03) — 지방 관직 읽기(lib/api/court-local-offices) · 다시 시도, 치소 현 · 관할 이름(지도 미리보기). 화면은 이 훅만 부른다(D105 층).
// 서버가 아직 경로를 내지 않으면(404) 「서버 대기」다 — 오류로 그리지 않는다. 200 이 오면 값이 저절로 나온다(D124 미리 짓기).
// 지도 미리보기는 관직이 있을 때만 받는다. 못 받으면 이름은 「어느 군국 · 어느 주」다(값을 짓지 않는다).
import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from './api';
import { readCourtLocalOffices, type CourtLocalOffices } from './api/court-local-offices';
import { localOfficesView, type CountyPlace, type LocalOfficesView } from './court-local-offices-view';
import type { MapPreviewCity } from './types';

/** 지도 표시명(「경조윤 장안현」)에서 관할 이름과 겹치는 앞머리를 떼어 현 이름만 — 표시명이 없으면 지도 name. */
function countyPlace(c: MapPreviewCity): CountyPlace {
    const commandery = c.commanderyName ?? null;
    const full = c.displayName ?? c.name;
    const name = commandery && full.startsWith(`${commandery} `) ? full.slice(commandery.length + 1) : full;
    return { name, commandery, region: c.regionName ?? null };
}

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
    const [places, setPlaces] = useState<ReadonlyMap<number, CountyPlace>>(new Map());
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
                const o = r.offices;
                if (o.localOffices.length === 0 && o.appointmentOptions.length === 0 && o.pendingOffers.length === 0) return;
                api.mapPreview(controller.signal)
                    .then((preview) => {
                        if (controller.signal.aborted) return;
                        setPlaces(
                            new Map(
                                (preview?.cities ?? []).map((c) => [c.id, countyPlace(c)] as const),
                            ),
                        );
                    })
                    .catch(() => undefined); // 이름을 못 받으면 「어느 군국 · 어느 주」로 둔다.
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, nonce]);

    const view = useMemo(
        () => (read.state === 'ready' ? localOfficesView(read.offices, (id) => places.get(id) ?? null) : null),
        [read, places],
    );
    if (read.state === 'ready') return { state: 'ready', offices: read.offices, view: view!, retry };
    return { ...read, retry };
}
