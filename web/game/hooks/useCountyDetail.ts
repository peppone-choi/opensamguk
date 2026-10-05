'use client';

// County detail read (contract K4-04 `GET /api/counties/{cityId}`, C10). Always called (D124 build-ahead): while the server
// route is missing (404) or failing, no data arrives and every detail cell stays "server wait" — the screen never errors on it.

import { api } from '@/lib/api';
import { useCampaignRead, type Read } from '@/lib/campaign-reads';
import type { CountyDetailRead } from '@/lib/county-detail';

export function useCountyDetail(cityId: number | null, attempt: number): Read<CountyDetailRead | null> {
    return useCampaignRead(
        (id, signal) => (cityId == null ? Promise.resolve(null) : api.countyDetail(id, cityId, signal)),
        [cityId, attempt],
    );
}
