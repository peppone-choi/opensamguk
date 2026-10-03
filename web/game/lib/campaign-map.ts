'use client';

import { useWorldMap, worldTerrainUrl, worldProvincesUrl, WORLD_MAP_CODE, type WorldMapState } from '@opensamguk/ui';
import { topdownScreensEnabled, topdownSourceFor } from '@opensamguk/ui/map/topdown';
import { api } from './api';
import type { MapPreviewResponse } from './types';
import type { Sieges, Works } from './campaign-reads';

export {
    buildWorldCities as buildCampaignCities, buildMarkerPositions, buildCommanderies,
    buildProvinceCenters, buildLegend, type CommanderyCell, type LegendEntry,
} from '@opensamguk/ui';
export const CAMPAIGN_MAP_CODE = WORLD_MAP_CODE;
export const CAMPAIGN_PROVINCES_URL = worldProvincesUrl();
export const campaignTerrainUrl = worldTerrainUrl;
export type MapState = WorldMapState<MapPreviewResponse>;

/**
 * 작전실 · 기록 지도가 이 미리보기를 새 지도(탑다운)로 그리는가 — 화면과 같은 규칙(교체 스위치 + 서버 bakeId).
 * 그때는 옛 지도판 몫(지형 · 州 색인 · 省 PNG, 운영 24.7MB)을 청하지 않고 미리보기에서 멈춘다(`kind: 'preview'`).
 */
export function drawnByTopdown(preview: Pick<MapPreviewResponse, 'topdownBakeId'>): boolean {
    return topdownScreensEnabled() && topdownSourceFor(preview.topdownBakeId) != null;
}

export function useCampaignWorldMap(refreshKey: unknown = 0, works?: Works | null, sieges?: Sieges | null): MapState {
    return useWorldMap({ loadPreview: api.mapPreview, refreshKey, works, sieges, previewOnly: drawnByTopdown });
}
