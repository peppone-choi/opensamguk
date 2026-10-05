'use client';

// 황실 court 훅 — lib/api/imperial-court 를 세션 장수로 읽는다(D105 층: 화면 → 이 훅 → 클라이언트).
// 경로가 아직 main 에 없으면(404) · 장수가 없으면 「서버 대기」로 둔다 — 서버가 들어오면 별도 PR 없이 값이 나온다(D124 미리 짓기).
import { useEffect, useState } from 'react';
import { readImperialCourt } from './api/imperial-court';
import type { CourtReadState } from './imperial-court-view';

export function useImperialCourt(generalId: number | null, enabled: boolean): CourtReadState {
    const [value, setValue] = useState<CourtReadState>({ state: 'waiting' });
    useEffect(() => {
        if (!enabled || generalId == null) {
            setValue({ state: 'waiting' });
            return undefined;
        }
        const controller = new AbortController();
        setValue({ state: 'loading' });
        readImperialCourt(generalId, controller.signal)
            .then((r) => {
                if (controller.signal.aborted) return;
                if (r.ok) setValue({ state: 'ready', court: r.court });
                else setValue(r.httpStatus === 404 ? { state: 'waiting' } : { state: 'error' });
            })
            .catch(() => undefined); // 끊은 요청(AbortError)만 여기 온다.
        return () => controller.abort();
    }, [generalId, enabled]);
    return value;
}
