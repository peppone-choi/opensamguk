'use client';

import Link from 'next/link';
import { useMemo, useState } from 'react';
import { Modal, StatusView, useViewportClass } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PEOPLE_PAGE_LIMIT, usePeopleList, type PeopleDirection, type PeopleScope, type PeopleSort } from '@/lib/directory-reads';
import { useGameSession } from '@/lib/campaign-session';
import { peopleRows } from '@/lib/people-view';
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
        /** 인물 상세(P-R03) — 그 화면 전에는 없다: 모바일 카드는 미리보기 시트를 열고, 미리보기의 「인물 상세 열기」는 그리지 않는다. */
        readonly person?: (generalId: number) => string;
        readonly letter?: (generalId: number) => string;
        /** 「내 부」 가 비었을 때 — 인재탐색 명령 흐름. */
        readonly search: string;
    };
    /** 소재 城 이름 — 화면이 가진 城 표로. 못 풀면 null → 「?」. */
    readonly cityName: (cityId: number) => string | null;
}

/**
 * 인물 일람 화면 본문(P-R02) — 거르기 줄 + 표(데스크톱, 오른쪽 미리보기 360) / 카드(모바일) + 「50명 더 보기」.
 * 정렬 · 초성 찾기는 서버가 한다(#1103 — 키 14 · 방향, 화면 재정렬 금지). 첫 쪽 실패는 빈 것과 다른 모양(다시 시도), 다음 쪽 실패는 받은 목록을 둔다.
 */
export function PeopleScreen({ initialScope, hrefs, cityName }: PeopleScreenProps) {
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [scope, setScope] = useState<PeopleScope>(initialScope);
    const [query, setQuery] = useState('');
    const [order, setOrder] = useState<{ readonly sort: PeopleSort; readonly direction: PeopleDirection }>({ sort: 'ID', direction: 'ASC' });
    const [attempt, setAttempt] = useState(0);
    if (mobile === null) return <StatusView kind="loading" rows={10} />;
    return (
        <PeopleBody key={attempt} mobile={mobile} scope={scope} onScope={setScope} query={query} onQuery={setQuery}
            order={order} onOrder={(sort, direction) => setOrder({ sort, direction })}
            hrefs={hrefs} cityName={cityName} onRetry={() => setAttempt((n) => n + 1)} />
    );
}

function PeopleBody({ mobile, scope, onScope, query, onQuery, order, onOrder, hrefs, cityName, onRetry }: {
    readonly mobile: boolean;
    readonly scope: PeopleScope;
    readonly onScope: (s: PeopleScope) => void;
    readonly query: string;
    readonly onQuery: (q: string) => void;
    readonly order: { readonly sort: PeopleSort; readonly direction: PeopleDirection };
    readonly onOrder: (sort: PeopleSort, direction: PeopleDirection) => void;
    readonly hrefs: PeopleScreenProps['hrefs'];
    readonly cityName: PeopleScreenProps['cityName'];
    readonly onRetry: () => void;
}) {
    const { generalId } = useGameSession();
    const list = usePeopleList({ scope, q: query, limit: PEOPLE_PAGE_LIMIT, sort: order.sort, direction: order.direction });
    const rows = useMemo(() => peopleRows(list.people, generalId), [list.people, generalId]);
    const [selected, setSelected] = useState<number | null>(null);
    const picked = rows.find((r) => r.generalId === selected) ?? null;
    const current = picked ?? rows[0] ?? null;
    // 인물 상세(P-R03) 고리 — 나 · 내 부 인물만(그 밖은 인물 상세 읽기 K4-13 전이라 상세가 「아직 볼 수 없습니다」뿐이다).
    const detailOf = (r: { readonly generalId: number; readonly detailable: boolean }) => (r.detailable ? hrefs.person?.(r.generalId) : undefined);
    const notice = campaignReadNotice({ loading: list.loading, error: null }, list.status ?? undefined);

    const bar = <PeopleFilterBar scope={scope} onScopeChange={onScope} query={query} onQueryChange={onQuery}
        sort={order.sort} direction={order.direction} onSortChange={onOrder} loaded={rows.length} hasMore={list.hasMore} total={list.total} mobile={mobile} />;
    const more = <PeopleMore hasMore={list.hasMore} loading={list.loading} moreError={list.moreError} onMore={list.loadMore} />;

    let body;
    if (list.loading && rows.length === 0) body = <StatusView kind="loading" rows={10} />;
    else if (list.error) body = <StatusView kind="error" title="인물 목록을 불러오지 못했습니다" errorCode={list.errorCode ?? undefined} onRetry={onRetry} />;
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
        // 모바일 카드는 늘 미리보기 시트를 연다 — 소속 · 소재 · 5능력 · 적성 · 결속은 일람 응답에 있다(나 · 내 부가 아니어도 정보를 잃지 않는다, #1265 리뷰).
        body = <><PeopleCards rows={rows} onSelect={(r) => setSelected(r.generalId)} cityName={cityName} />{more}</>;
    } else {
        body = (
            <div className={styles.peopleColumns}>
                <div className={styles.peopleMain}>
                    <PeopleTable rows={rows} selectedId={current?.generalId ?? null} onSelect={(r) => setSelected(r.generalId)} cityName={cityName} />
                    {more}
                </div>
                {current ? (
                    <aside className={`os-panel ${styles.peopleAside}`} aria-label="미리보기">
                        <PersonPreview row={current} detailHref={detailOf(current)}
                            letterHref={hrefs.letter?.(current.generalId)} cityName={cityName} />
                    </aside>
                ) : null}
            </div>
        );
    }

    return (
        <div className={mobile ? styles.peopleScreenMobile : styles.peopleScreen}>
            {bar}
            {body}
            {/* 모바일: 카드를 누르면 미리보기를 하단 시트로 연다(닫기 44). 나 · 내 부면 시트 안에 「인물 상세 열기」. */}
            {mobile && picked ? (
                <Modal ariaLabel={`${picked.name} 미리보기`} onClose={() => setSelected(null)} overlayClassName={styles.sheetBottom}>
                    <div className={styles.sheetHead}>
                        <h3 className={styles.sheetTitle}>인물 미리보기</h3>
                        <button type="button" className="os-button os-button--sm" onClick={() => setSelected(null)}>닫기</button>
                    </div>
                    <PersonPreview row={picked} detailHref={detailOf(picked)} cityName={cityName} letterHref={hrefs.letter?.(picked.generalId)} />
                </Modal>
            ) : null}
        </div>
    );
}
