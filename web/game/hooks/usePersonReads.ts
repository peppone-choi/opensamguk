'use client';

// Person detail (P-R03) reads. Self is drawn from front-info, so retinue · posts are read only for other people —
// whether someone is in my retinue (and NPC or human) comes from those two reads. The K4-13 detail read is always
// called (D124 build-ahead): while the server route is missing (404) or failing, the screen draws from the reads above.

import { useCallback, useMemo, useState } from 'react';
import { api } from '@/lib/api';
import { useCampaignRead, type Posts, type Read, type Retinue } from '@/lib/campaign-reads';
import type { PersonDetailRead } from '@/lib/person-detail';
import { retinueRows, type RetinueRow } from '@/lib/retinue-view';

interface PersonReads {
    readonly retinue: Read<Retinue | null>;
    readonly posts: Read<Posts | null>;
    readonly detail: Read<PersonDetailRead | null>;
    /** Retinue rows once the retinue read is READY, otherwise null. */
    readonly rows: RetinueRow[] | null;
    /** Read both again (retry button, or after a placement is queued). */
    readonly reload: () => void;
}

export function usePersonReads(isSelf: boolean, targetGeneralId: number | null): PersonReads {
    const [attempt, setAttempt] = useState(0);
    const retinue = useCampaignRead((id, signal) => (isSelf ? Promise.resolve(null) : api.campaignRetinue(id, signal)), [isSelf, attempt]);
    const posts = useCampaignRead((id, signal) => (isSelf ? Promise.resolve(null) : api.campaignPosts(id, signal)), [isSelf, attempt]);
    const detail = useCampaignRead(
        (id, signal) => (targetGeneralId == null ? Promise.resolve(null) : api.personDetail(id, targetGeneralId, signal)),
        [targetGeneralId, attempt],
    );
    const rows = useMemo(
        () => (retinue.data?.status === 'READY' ? retinueRows(retinue.data, posts.data) : null),
        [retinue.data, posts.data],
    );
    const reload = useCallback(() => setAttempt((n) => n + 1), []);
    return { retinue, posts, detail, rows, reload };
}
