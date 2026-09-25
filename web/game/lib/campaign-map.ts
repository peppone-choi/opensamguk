'use client';

import { useWorldMap, worldTerrainUrl, worldProvincesUrl, WORLD_MAP_CODE, type WorldMapState } from '@opensamguk/ui';
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

export function useCampaignWorldMap(refreshKey: unknown = 0, works?: Works | null, sieges?: Sieges | null): MapState {
    return useWorldMap({ loadPreview: api.mapPreview, refreshKey, works, sieges });
}
