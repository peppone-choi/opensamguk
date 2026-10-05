'use client';

// 작전실 지도 「보급선」 층의 읽기 훅(`/api/warehouses`).

import { useMemo } from 'react';
import { api } from './api';
import { useCampaignRead } from './campaign-reads';
import { supplyLinesOf, type SupplyLine } from './supply-lines';

export interface SupplyLinesRead {
    /** null = 서버가 연결을 아직 주지 않음(서버 대기 · K4-06), 읽는 중, 또는 읽기 실패. */
    readonly lines: readonly SupplyLine[] | null;
    /** 읽기 실패 — 레이어 판은 「서버 대기」 대신 「불러오지 못함」. */
    readonly failed: boolean;
}

export function useSupplyLines(refreshKey?: unknown): SupplyLinesRead {
    const read = useCampaignRead((id, signal) => api.warehouses(id, signal), [refreshKey]);
    return useMemo(() => ({ lines: supplyLinesOf(read.data), failed: read.error != null }), [read.data, read.error]);
}
