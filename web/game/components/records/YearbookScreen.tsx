'use client';

// 연감(P-H02) — K5 설계서 §5.2, 보드 V31K5Yearbook · MYearbook · YearbookEmpty. 계약판 K5-08 모양으로 읽는다.
//  - 해 고르기: 발행된 해만(`/api/yearbook/years`). 아직 안 끝난 해는 사유 단추.
//  - 연말 판도: 세력 · 현 수 · 수도(이름은 공개 지도 미리보기 현 이름표). 무주(nationId 0)는 맨 뒤.
//  - 그해 큰 사건: 공개 사건(알림체 eventSentence), 세력으로 거르기(사건 refs 의 세력 키), 커서 「더 보기」.
//  - 판도 지도(연말 소유 ownership) · 세력별 현 목록(territory[].counties)은 소비 안 K5-WAIT-04 모양 — 서버가 아직 안 주면 서버 대기,
//    발행됐지만 원천이 없으면(absent) 「기록이 없습니다」. 어느 쪽도 지금 소유로 그해 판도를 그리지 않는다.
// 서버 경로가 아직 없으면(404 · 503) 「연감을 준비하고 있습니다」, 그해가 아직 안 나왔으면(YEARBOOK_NOT_PUBLISHED) 「그해가 끝나면 나옵니다」.
// 메뉴는 K5-08 이 올 때까지 옛 연감을 가리킨다(CEO 10-05).

import { useMemo, useState } from 'react';
import { Button, Chip, Flag, Panel, RECORD_SECTION_LABEL, SectionHeader, Seg, StatusView, eventSentence, formatGameDate, useViewportClass } from '@opensamguk/ui';
import CampaignLink from '@/components/campaign/CampaignLink';
import { useYearbook, type YearState } from '@/hooks/useYearbook';
import { useGameSession } from '@/lib/campaign-session';
import { useRecordNames } from '@/lib/records-names';
import type { YearbookTerritory } from '@/lib/yearbook-contract';
import { countiesPart, eventTouchesNation, neighbours, ownershipPart, territoryRows, type YearbookPart } from '@/lib/yearbook-view';
import { formatNumber } from '@/lib/format';
import YearbookMap from './YearbookMap';
import styles from './yearbook.module.css';

const MAP_WAIT = '그해 말 판도 지도(연말 소유)는 서버가 아직 주지 않습니다. 지금 소유로 그해 판도를 그리지 않습니다.';
const COUNTIES_WAIT = '세력별 현 목록은 서버가 아직 주지 않습니다.';
const COUNTIES_ABSENT = '그해 현 목록 기록이 없습니다. 세력마다 현 수만 보입니다.';

function YearBar({ published, unpublished, year, setYear, mobile }: {
    readonly published: readonly number[]; readonly unpublished: readonly number[]; readonly year: number;
    readonly setYear: (y: number) => void; readonly mobile: boolean;
}) {
    const { prev, next } = neighbours(published, year);
    const pending = unpublished.find((y) => y > year) ?? null;
    const prevButton = prev !== null
        ? <Button variant="ghost" onClick={() => setYear(prev)}>{`◀ ${prev}년`}</Button>
        : <Button variant="ghost" disabled reason="첫 연감입니다">◀ 앞 해</Button>;
    const nextButton = next !== null
        ? <Button variant="ghost" onClick={() => setYear(next)}>{`${next}년 ▶`}</Button>
        : <Button variant="ghost" disabled reason="아직 이 해가 끝나지 않았습니다">{pending !== null ? `${pending}년 ▶` : '뒤 해 ▶'}</Button>;
    if (mobile) {
        return (
            <div className={styles.yearBarMobile}>
                {prevButton}
                <b className={styles.yearNow}>{year}년</b>
                {nextButton}
            </div>
        );
    }
    return (
        <div className={styles.yearBar}>
            {prevButton}
            <Seg label="연도" options={published.map((y) => ({ value: y, label: `${y}년` }))} value={year} onChange={setYear} scroll />
            {nextButton}
            <span className={styles.muted}>연감은 한 해가 끝날 때 한 번 나옵니다 · 달마다 일은 기록에서 날짜로 봅니다</span>
        </div>
    );
}

function MapSlot({ year, page, currentPin }: { readonly year: number; readonly page: YearState; readonly currentPin: string | null | undefined }) {
    const label = `${year}년 말 판도`;
    if (page.kind === 'loading') return <div className={styles.mapSlot} aria-label={label}><StatusView kind="loading" rows={3} /></div>;
    if (page.kind !== 'ready' && page.kind !== 'waiting') return null;
    const part = page.kind === 'ready' ? ownershipPart(page) : { kind: 'waiting' as const };
    if (part.kind === 'ready') {
        return <div className={`${styles.mapSlot} ${styles.mapLive}`} aria-label={label}><YearbookMap year={year} ownership={part.value} territory={page.kind === 'ready' ? page.territory : []} currentPin={currentPin} /></div>;
    }
    if (part.kind === 'absent') {
        return (
            <div className={styles.mapSlot} aria-label={label}>
                <StatusView kind="empty" title={`${year}년 말 판도 지도 기록이 없습니다`} body="그해 말 소유가 기록되지 않았습니다. 지금 소유로 대신 그리지 않습니다." />
            </div>
        );
    }
    // 연말 소유 스냅샷은 계약판 「요청 보강」 표의 K5-08 행 — 연감 본문(K5-08)이 와도 따로 기다린다
    return (
        <div className={styles.mapSlot} aria-label={label} data-server-wait="K5-08 보강">
            <StatusView kind="waiting" title={`${year}년 말 판도 지도는 준비 중입니다`} body={MAP_WAIT} />
        </div>
    );
}

function CountyLists({ rows, counties }: { readonly rows: readonly YearbookTerritory[]; readonly counties: YearbookPart<ReadonlyMap<number, readonly string[]>> }) {
    if (counties.kind === 'waiting') return <p className={styles.wait}>{COUNTIES_WAIT}</p>;
    if (counties.kind === 'absent') return <p className={styles.wait}>{COUNTIES_ABSENT}</p>;
    return (
        <ul className={styles.counties} aria-label="세력별 현 목록">
            {rows.map((row) => {
                const names = counties.value.get(row.nationId) ?? [];
                return (
                    <li key={row.nationId}>
                        <details>
                            <summary>{`${row.nationId === 0 ? '무주' : row.name} 현 ${formatNumber(names.length)}곳`}</summary>
                            <p className={styles.countyNames}>{names.length > 0 ? names.join(' · ') : '그해 말 소속 현이 없습니다.'}</p>
                        </details>
                    </li>
                );
            })}
        </ul>
    );
}

function TerritoryTable({ rows, cityName, counties }: {
    readonly rows: readonly YearbookTerritory[]; readonly cityName: (id: number) => string | null;
    readonly counties: YearbookPart<ReadonlyMap<number, readonly string[]>>;
}) {
    if (rows.length === 0) return <StatusView kind="empty" title="그해 판도 기록이 없습니다" body="서버가 그해 연말 판도를 주지 않았습니다." />;
    return (
        <>
            <ul className={styles.terr} aria-label="연말 판도">
                {rows.map((row) => {
                    const capital = row.capitalCityId === null ? null : cityName(row.capitalCityId);
                    return (
                        <li key={row.nationId} className={styles.terrRow}>
                            {row.nationId === 0 ? <span className={styles.flagGap} aria-hidden="true" /> : <Flag color={row.color} size={14} label={`${row.name} 깃발`} />}
                            <span className={styles.terrName}>{row.nationId === 0 ? '무주' : row.name}</span>
                            <span className={styles.terrCount}>현 {formatNumber(row.countyCount)}</span>
                            <span>{capital ? <Chip tone="bronze">{`수도 ${capital}`}</Chip> : null}</span>
                        </li>
                    );
                })}
            </ul>
            <CountyLists rows={rows} counties={counties} />
        </>
    );
}

export default function YearbookScreen() {
    const { years, year, setYear, page, loadMore, reloadYears, reloadPage } = useYearbook();
    const session = useGameSession();
    const general = session.frontInfo?.general;
    const names = useRecordNames(session.generalId, general?.name ?? null);
    const viewer = useMemo(() => ({ generalId: session.generalId }), [session.generalId]);
    const mobile = useViewportClass() === 'mobile';
    const [nationFilter, setNationFilter] = useState<number | 'ALL'>('ALL');

    if (years.kind === 'loading') return <StatusView kind="loading" rows={4} />;
    if (years.kind === 'waiting') {
        return (
            <Panel className={styles.panel}>
                <div data-server-wait="K5-08">
                    <StatusView kind="waiting" title="연감을 준비하고 있습니다"
                        body="한 해가 끝날 때 그해 공개된 큰 사건과 연말 판도를 한 장으로 묶어 보여 줍니다. 서버가 아직 연감을 주지 않습니다." />
                </div>
                <div className={styles.foot}><CampaignLink slug="records" className="os-button os-button--ghost">기록으로</CampaignLink></div>
            </Panel>
        );
    }
    if (years.kind === 'error') return <StatusView kind="error" title="연감 목록을 불러오지 못했습니다" body={years.error.message} onRetry={reloadYears} />;
    if (years.published.length === 0 || year === null) {
        const current = session.frontInfo?.global.year;
        return (
            <Panel className={styles.panel}>
                <StatusView kind="empty" title={current ? `첫 연감은 ${current}년이 끝나면 나옵니다` : '첫 연감은 한 해가 끝나면 나옵니다'}
                    body="한 해가 끝날 때 그해 공개된 큰 사건과 연말 판도를 한 장으로 묶습니다. 그동안의 일은 기록에서 볼 수 있습니다."
                    actions={<CampaignLink slug="records" className="os-button os-button--primary">기록으로</CampaignLink>} />
            </Panel>
        );
    }

    const unpublished = years.years.filter((y) => !y.published).map((y) => y.year);
    const bar = <YearBar published={years.published} unpublished={unpublished} year={year} setYear={(y) => { setNationFilter('ALL'); setYear(y); }} mobile={mobile} />;
    const map = <MapSlot year={year} page={page} currentPin={names.ready ? names.mapPin : undefined} />;

    let body;
    if (page.kind === 'loading') body = <StatusView kind="loading" rows={4} />;
    else if (page.kind === 'waiting') body = <div data-server-wait="K5-08"><StatusView kind="waiting" title="이 해의 연감을 준비하고 있습니다" body="서버가 아직 이 해의 연감을 주지 않습니다." /></div>;
    else if (page.kind === 'error') body = <StatusView kind="error" title="연감을 불러오지 못했습니다" body={page.error.message} onRetry={reloadPage} />;
    else if (page.kind === 'not-published') {
        body = (
            <Panel className={`${styles.panel} ${styles.right}`}>
                <StatusView kind="empty" title={`${year}년 연감은 그해가 끝나면 나옵니다`}
                    body="그해가 끝날 때 그해 공개된 큰 사건과 연말 판도를 한 장으로 묶습니다. 그동안의 일은 기록에서 볼 수 있습니다."
                    actions={<CampaignLink slug="records" className="os-button os-button--ghost">기록으로</CampaignLink>} />
            </Panel>
        );
    }
    else {
        const rows = territoryRows(page.territory);
        const nations = rows.filter((r) => r.nationId !== 0);
        const events = nationFilter === 'ALL' ? page.events : page.events.filter((e) => eventTouchesNation(e, nationFilter));
        const terr = (
            <Panel className={styles.panel} aria-label="연말 판도">
                <SectionHeader title="연말 판도" sub={`세력 ${formatNumber(nations.length)} · 소유 현 수 · 수도`} />
                <TerritoryTable rows={rows} cityName={(id) => names.city(id) ?? null} counties={countiesPart(page)} />
            </Panel>
        );
        const ev = (
            <Panel className={`${styles.panel} ${styles.events}`} aria-label="그해 큰 사건">
                <SectionHeader title="그해 큰 사건" sub="공개 사건만" />
                {page.revised ? <p className={styles.wait} role="status">연감이 그사이 고쳐져 이 해를 처음부터 다시 불러왔습니다.</p> : null}
                {nations.length > 0 ? (
                    <div className={styles.filter}>
                        <Seg<number | 'ALL'> label="세력으로 거르기" options={[{ value: 'ALL', label: '전체' }, ...nations.map((n) => ({ value: n.nationId, label: n.name }))]}
                            value={nationFilter} onChange={setNationFilter} scroll />
                    </div>
                ) : null}
                {events.length === 0
                    ? <StatusView kind="empty" title={nationFilter === 'ALL' ? '그해 공개된 큰 사건이 없습니다' : '이 세력과 관계된 큰 사건이 없습니다'} body="거르기를 바꾸거나 다른 해를 보세요." />
                    : (
                        <ul className={styles.evList} aria-label="그해 큰 사건 목록">
                            {events.map((event) => (
                                <li key={event.id} className={styles.evRow}>
                                    <Chip tone="info">{RECORD_SECTION_LABEL.WORLD}</Chip>
                                    <span className={styles.evDate}>{formatGameDate(event.occurredAt)}</span>
                                    <span className={styles.evText}>{eventSentence(event, names, viewer) ?? '기록을 표시할 수 없습니다.'}</span>
                                </li>
                            ))}
                        </ul>
                    )}
                {page.moreError ? <p className={styles.alert} role="alert">{page.moreError.message}</p> : null}
                {page.nextCursor !== null ? (
                    <div className={styles.foot}>
                        {page.loadingMore
                            ? <Button variant="ghost" block disabled reason="불러오는 중입니다">더 보기</Button>
                            : <Button variant="ghost" block onClick={loadMore}>더 보기</Button>}
                    </div>
                ) : null}
            </Panel>
        );
        body = mobile ? <>{terr}{ev}</> : <div className={styles.right}>{terr}{ev}</div>;
    }

    if (mobile) {
        return <div className={styles.mobile}>{bar}{map}{body}</div>;
    }
    return (
        <div className={styles.screen}>
            {bar}
            <div className={styles.columns}>{map}{body}</div>
        </div>
    );
}
