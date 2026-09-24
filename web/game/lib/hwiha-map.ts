'use client';

import { useWorldMap, worldTerrainUrl, worldProvincesUrl, WORLD_MAP_CODE, type WorldMapState } from '@opensamguk/ui';
import { api } from './api';
import type { MapPreviewResponse } from './types';
import type { HwihaSieges, HwihaWorks } from './hwiha-reads';

export {
    buildWorldCities as buildHwihaCities, buildMarkerPositions, buildCommanderies,
    buildProvinceCenters, buildLegend, type HwihaCommanderyCell, type HwihaLegendEntry,
} from '@opensamguk/ui';
export const HWIHA_MAP_CODE = WORLD_MAP_CODE;
export const HWIHA_PROVINCES_URL = worldProvincesUrl();
export const hwihaTerrainUrl = worldTerrainUrl;
export type HwihaMapState = WorldMapState<MapPreviewResponse>;

export function useHwihaWorldMap(refreshKey: unknown = 0, works?: HwihaWorks | null, sieges?: HwihaSieges | null): HwihaMapState {
    return useWorldMap({ loadPreview: api.mapPreview, refreshKey, works, sieges });
}
