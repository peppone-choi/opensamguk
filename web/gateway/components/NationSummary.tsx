'use client';

import { useEffect, useState } from 'react';
import { EmptyState, Flag, SectionHeader } from '@opensamguk/ui';
import { fetchNationSummary, type NationSummary as NationRow } from '@/lib/notices';

/**
 * 세력 현황(01 로그인 · 02 로비) — 선택 서버의 국가별 성 수·장수 수. game-api 공개 `/api/rankings/kingdoms` 를
 * 게이트웨이가 프록시한다. 국가색은 깃발에만 쓴다(로드맵 규칙).
 */
export default function NationSummary({ serverId, serverName, mine }: { readonly serverId: string; readonly serverName?: string; readonly mine?: number | null }) {
    const [rows, setRows] = useState<NationRow[] | null | undefined>(undefined);

    useEffect(() => {
        let alive = true;
        setRows(undefined);
        fetchNationSummary(serverId).then((list) => {
            if (alive) setRows(list);
        });
        return () => {
            alive = false;
        };
    }, [serverId]);

    return (
        <section className="os-panel os-panel--static nation-summary" aria-label="세력 현황">
            <SectionHeader title={serverName ? `${serverName} · 세력 현황` : '세력 현황'} sub={rows ? `${rows.length}국` : undefined} />
            {rows === undefined && <div className="nation-summary__flag">불러오는 중…</div>}
            {rows === null && <div className="nation-summary__flag nation-summary__flag--error">세력 현황을 불러올 수 없습니다.</div>}
            {rows && rows.length === 0 && <EmptyState title="세력이 없습니다." />}
            {rows && rows.length > 0 && (
                <ul className="nation-summary__list">
                    {rows.map((n) => (
                        <li key={n.nationId} className="nation-summary__row">
                            <Flag color={n.color} size={14} label={n.name} />
                            <span className="nation-summary__name">
                                {n.name}
                                {mine != null && mine === n.nationId && <span className="os-chip os-chip--bronze nation-summary__mine">내 소속</span>}
                            </span>
                            <span className="os-num nation-summary__stat">{n.cityCount}성</span>
                            <span className="os-num nation-summary__stat">{n.genNum}명</span>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
