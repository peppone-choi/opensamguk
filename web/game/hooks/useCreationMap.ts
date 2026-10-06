'use client';

// 본관 현 지도(P-E02) — 공개 지도 미리보기(`/api/map/preview`)만 읽는다. 옛 지도판 몫(지형 · 州 색인 · 省 그림)은 청하지 않는다.
// 새 지도(탑다운)는 서버 bakeId 가 있을 때만 그린다(작전실 · 기록과 같은 규칙).
// bakeId 가 없거나 미리보기를 못 읽으면 'none' — 화면은 목록으로만 고른다(지도를 지어내지 않는다).

import { useEffect, useState } from 'react';
import { topdownSourceFor, type TopdownSource } from '@opensamguk/ui/map/topdown';
import { api } from '@/lib/api';
import type { MapPreviewResponse } from '@/lib/types';

export type CreationMap =
    | { readonly kind: 'loading' }
    | { readonly kind: 'none' }
    | { readonly kind: 'ready'; readonly source: TopdownSource; readonly preview: MapPreviewResponse };

export function useCreationMap(): CreationMap {
    const [map, setMap] = useState<CreationMap>({ kind: 'loading' });
    useEffect(() => {
        const controller = new AbortController();
        api.mapPreview(controller.signal).then(
            (preview) => {
                if (controller.signal.aborted) return;
                const source = topdownSourceFor(preview.topdownBakeId);
                setMap(source ? { kind: 'ready', source, preview } : { kind: 'none' });
            },
            () => { if (!controller.signal.aborted) setMap({ kind: 'none' }); },
        );
        return () => controller.abort();
    }, []);
    return map;
}
