'use client';

// County detail read (contract K4-04 `GET /api/counties/{cityId}`, C10). Not called until COUNTY_DETAIL_READY flips —
// the route is not on main yet, so calling it would only leave 404s in the console.

import { api } from '@/lib/api';
import { useCampaignRead, type Read } from '@/lib/campaign-reads';
import { COUNTY_DETAIL_READY, type CountyDetailRead } from '@/lib/county-detail';

export function useCountyDetail(cityId: number | null, attempt: number): Read<CountyDetailRead | null> {
    return useCampaignRead(
        (id, signal) => (cityId == null || !COUNTY_DETAIL_READY ? Promise.resolve(null) : api.countyDetail(id, cityId, signal)),
        [cityId, attempt],
    );
}
