'use client';

import { useWorldMap, type WorldMapState } from '@opensamguk/ui';
import { api } from './api';
import type { MapPreviewResponse } from './types';

export { buildWorldCities as buildCampaignCities, buildLegend, type CommanderyCell, type LegendEntry } from '@opensamguk/ui';
export type MapState = WorldMapState<MapPreviewResponse>;

/** 작전실 · 기록 지도의 미리보기(지도는 미리보기 + bake 만 받는다 — 옛 지도판 몫은 지웠다, M2-9). */
export function useCampaignWorldMap(refreshKey: unknown = 0): MapState {
    return useWorldMap({ loadPreview: api.mapPreview, refreshKey });
}
