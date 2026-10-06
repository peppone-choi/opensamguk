'use client';

// County detail read (contract K4-04 `GET /api/counties/{cityId}`, C10 #1351). A 404 means the city is not an administrative
// county (or an older server without the route): the detail cells stay "server wait". Any other failure (403 · 409 · 5xx)
// is a real read failure — the screen shows it with the partial-failure retry line instead of hiding it as "server wait".

import { api } from '@/lib/api';
import { useCampaignRead, type Read } from '@/lib/campaign-reads';
import type { CountyDetailRead } from '@/lib/county-detail';

export function useCountyDetail(cityId: number | null, attempt: number): Read<CountyDetailRead | null> {
    return useCampaignRead(
        (id, signal) => (cityId == null ? Promise.resolve(null) : api.countyDetail(id, cityId, signal)),
        [cityId, attempt],
    );
}
