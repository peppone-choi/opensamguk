'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PEOPLE_PAGE_LIMIT, usePeopleList, type PeopleScope } from '@/lib/directory-reads';
import { useGameSession } from '@/lib/campaign-session';
import { peopleRows } from '@/lib/people-view';
import { useIsMobile } from '@/lib/use-viewport';
import { PeopleCards } from './PeopleCards';
import { PeopleFilterBar } from './PeopleFilterBar';
import { PeopleMore } from './PeopleMore';
import { PeopleTable } from './PeopleTable';
import { PersonPreview } from './PersonPreview';
import styles from './people.module.css';

export interface PeopleScreenProps {
    /** 옛 주소 `?scope=NATION` 등(scopeFromQuery 로 읽어 넘긴다). */
    readonly initialScope: PeopleScope;
    readonly hrefs: {
        readonly person: (generalId: number) => string;
        readonly letter?: (generalId: number) => string;
        /** 「내 부」 가 비었을 때 — 인재탐색 명령 흐름. */
        readonly search: string;
    };
    /** 소재 城 이름 — 화면이 가진 城 표로. 못 풀면 null → 「?」. */
    readonly cityName: (cityId: number) => string | null;
}

/**
 * 인물 일람 화면 본문(P-R02) — 거르기 줄 + 표(데스크톱, 오른쪽 미리보기 360) / 카드(모바일) + 「50명 더 보기」.
 * 서버 ID 순 그대로(화면 재정렬 금지 — K4-20). 첫 쪽 실패는 빈 것과 다른 모양(다시 시도), 다음 쪽 실패는 받은 목록을 둔다.
 */
export function PeopleScreen({ initialScope, hrefs, cityName }: PeopleScreenProps) {
    const mobile = useIsMobile();
    const [scope, setScope] = useState<PeopleScope>(initialScope);
    const [query, setQuery] = useState('');
    const [attempt, setAttempt] = useState(0);
    if (mobile === null) return <StatusView kind="loading" rows={10} />;
    return (
        <PeopleBody key={attempt} mobile={mobile} scope={scope} onScope={setScope} query={query} onQuery={setQuery}
            hrefs={hrefs} cityName={cityName} onRetry={() => setAttempt((n) => n + 1)} />
    );
}

function PeopleBody({ mobile, scope, onScope, query, onQuery, hrefs, cityName, onRetry }: {
    readonly mobile: boolean;
    readonly scope: PeopleScope;
    readonly onScope: (s: PeopleScope) => void;
    readonly query: string;
    readonly onQuery: (q: string) => void;
    readonly hrefs: PeopleScreenProps['hrefs'];
    readonly cityName: PeopleScreenProps['cityName'];
    readonly onRetry: () => void;
}) {
    const { generalId } = useGameSession();
    const list = usePeopleList({ scope, q: query, limit: PEOPLE_PAGE_LIMIT });
    const rows = useMemo(() => peopleRows(list.people, generalId), [list.people, generalId]);
    const [selected, setSelected] = useState<number | null>(null);
    const current = rows.find((r) => r.generalId === selected) ?? rows[0] ?? null;
    const notice = campaignReadNotice({ loading: list.loading, error: null }, list.status ?? undefined);

    const bar = <PeopleFilterBar scope={scope} onScopeChange={onScope} query={query} onQueryChange={onQuery} loaded={rows.length} hasMore={list.hasMore} mobile={mobile} />;
    const more = <PeopleMore hasMore={list.hasMore} loading={list.loading} moreError={list.moreError} onMore={list.loadMore} />;

    let body;
    if (list.loading && rows.length === 0) body = <StatusView kind="loading" rows={10} />;
    else if (list.error) body = <StatusView kind="error" title="인물 목록을 불러오지 못했습니다" errorCode={list.error.split(':')[0]} onRetry={onRetry} />;
    else if (notice) body = <StatusView kind="waiting" title={notice} />;
    else if (rows.length === 0) {
        body = query.trim()
            ? <StatusView kind="empty" title="조건에 맞는 인물이 없습니다" body="찾는 이름을 바꾸거나 지워 보세요."
                actions={<button type="button" className="os-button" onClick={() => onQuery('')}>찾기 지우기</button>} />
            : scope === 'RETINUE'
                ? <StatusView kind="empty" title="아직 거느린 인물이 없습니다" body="인재탐색 · 등용은 명령 목록에서 합니다."
                    actions={<Link href={hrefs.search} className="os-button os-button--primary">인재탐색 — 명령 목록에 넣기</Link>} />
                : <StatusView kind="empty" title="이 세계에는 아직 인물이 없습니다" body="인물이 생기면 여기에 보입니다." />;
    } else if (mobile) {
        body = <><PeopleCards rows={rows} detailHref={hrefs.person} cityName={cityName} />{more}</>;
    } else {
        body = (
            <div className={styles.peopleColumns}>
                <div className={styles.peopleMain}>
                    <PeopleTable rows={rows} selectedId={current?.generalId ?? null} onSelect={(r) => setSelected(r.generalId)} cityName={cityName} />
                    {more}
                </div>
                {current ? (
                    <aside className={`os-panel ${styles.peopleAside}`} aria-label="미리보기">
                        <PersonPreview row={current} detailHref={hrefs.person(current.generalId)}
                            letterHref={hrefs.letter?.(current.generalId)} cityName={cityName} />
                    </aside>
                ) : null}
            </div>
        );
    }

    return (
        <div className={mobile ? styles.peopleScreenMobile : styles.peopleScreen}>
            <h1 className={styles.srOnly}>인물 일람</h1>
            {bar}
            {body}
        </div>
    );
}
