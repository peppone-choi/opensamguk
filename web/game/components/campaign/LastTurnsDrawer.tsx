'use client';

import Link from 'next/link';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Chip, Modal, Seg, StatusView } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { useCampaignRead, type LastTurns } from '@/lib/campaign-reads';
import {
    FEED_FILTERS, FEED_SCOPES, allItems, myTurns, nationItems, rangeText, recentCount,
    type FeedFilter, type FeedHrefs, type FeedItem, type FeedScope,
} from '@/lib/last-turns-view';
import { campaignReadNotice } from './GameStates';
import styles from './LastTurnsDrawer.module.css';

export interface LastTurnsDrawerProps {
    readonly mobile: boolean;
    readonly hrefs: FeedHrefs & {
        /** 기록 전체(P-H01, K5). */
        readonly records: string;
    };
    /** 열림 · 닫힘 — 작전실 틀이 지도 보기 단추(--map-viewbar-left)를 서랍 오른쪽으로 옮긴다(K2 합의 10-01). */
    readonly onOpenChange?: (open: boolean) => void;
}

function Item({ item, showWhen }: { readonly item: FeedItem; readonly showWhen: boolean }) {
    return (
        <li className={styles.item}>
            <div className={styles.itemChips}>
                {item.sectionLabel ? <span className={styles.cat} data-section={item.section ?? undefined}><i aria-hidden="true" />{item.sectionLabel}</span> : null}
                {item.status ? <Chip tone={item.status.tone}>{item.status.label}</Chip> : null}
            </div>
            <span className={`os-serif ${styles.title}`}>{showWhen ? `${item.title} · ${item.when}` : item.title}</span>
            <span className={styles.text}>{item.text}</span>
            {item.battleDetailPending ? <Chip tone="info">누가 · 어디 · 리플레이 — 준비 중</Chip> : null}
            {item.action ? <Link href={item.action.href} className={styles.action}>{item.action.label}</Link> : null}
        </li>
    );
}

function Feed({ data, scope, filter, hrefs }: { readonly data: LastTurns; readonly scope: FeedScope; readonly filter: FeedFilter; readonly hrefs: FeedHrefs }) {
    if (scope === 'MINE') {
        const turns = myTurns(data, filter, hrefs);
        if (turns.every((t) => t.items.length === 0)) return <Empty filtered={filter !== 'ALL'} />;
        return (
            <ol className={styles.turns} aria-label="내 12순">
                {turns.map((t) => t.items.length === 0 ? (
                    <li key={t.key} className={styles.emptyTurn}>{`${t.short} — 기록 없음`}</li>
                ) : (
                    <li key={t.key} className={styles.turn}>
                        <span className={`os-mono ${styles.turnHead}`}>{t.label}</span>
                        <ul className={styles.items}>{t.items.map((item) => <Item key={item.key} item={item} showWhen={false} />)}</ul>
                    </li>
                ))}
            </ol>
        );
    }
    const items = scope === 'NATION' ? nationItems(data, filter, hrefs) : allItems(data, filter, hrefs);
    if (items.length === 0) return <Empty filtered={filter !== 'ALL'} />;
    return <ul className={styles.items} aria-label={scope === 'NATION' ? '부 · 세력' : '전체'}>{items.map((item) => <Item key={item.key} item={item} showWhen />)}</ul>;
}

function Empty({ filtered }: { readonly filtered: boolean }) {
    return filtered
        ? <p className={styles.empty}>이 분류에는 최근 12순에 남은 기록이 없습니다.</p>
        : <p className={styles.empty}>최근 12순에 남은 기록이 없습니다 — 첫 명령을 넣으면 여기에 결과가 남습니다.</p>;
}

/** 손잡이 폭 · 서랍 폭(보드 V31K4Drawer 44 · 380) — CSS 와 같은 값. 작전실 틀이 지도 myLocationInset 으로 넘긴다. */
export const DRAWER_HANDLE_WIDTH = 44;
export const DRAWER_WIDTH = 380;

/**
 * 지난 순 서랍(P-W04) — 옛 「지난 순」 패널과 world_log 3탭(MainRecordZone)을 한 벌로 합친다.
 * 데스크톱: 지도 왼쪽 가장자리 손잡이(44×132, 세로 「지난 순」 + 새 기록 수) → 지도를 밀지 않고 덮는 380 서랍(Esc · 닫기).
 * 모바일: 「지난 순 n」 칩 단추 → 하단 시트. 부모는 position: relative 인 지도 상자 안에 둔다.
 */
export function LastTurnsDrawer({ mobile, hrefs, onOpenChange }: LastTurnsDrawerProps) {
    const [attempt, setAttempt] = useState(0);
    const read = useCampaignRead((id, s) => api.campaignLastTurns(id, s), [attempt]);
    const [open, setOpen] = useState(false);
    const [scope, setScope] = useState<FeedScope>('MINE');
    const [filter, setFilter] = useState<FeedFilter>('ALL');
    const handle = useRef<HTMLButtonElement | null>(null);
    const close = useRef<HTMLButtonElement | null>(null);
    const drawer = useRef<HTMLElement | null>(null);
    const ready = read.data?.status === 'READY' ? read.data : null;
    const count = ready ? recentCount(ready) : null;
    const range = ready ? rangeText(ready) : null;

    // 데스크톱 서랍은 모달이 아니다(지도를 덮을 뿐) — Esc 로 닫고 손잡이로 초점을 돌린다(손잡이는 닫힌 뒤 다시 그려진다). 모바일 시트는 Modal 이 맡는다.
    const shut = () => { setOpen(false); requestAnimationFrame(() => handle.current?.focus()); };
    useEffect(() => {
        if (!open || mobile) return;
        close.current?.focus();
        // 전역 Esc 를 다 가로채지 않는다 — 다른 판(지도 레이어 · 명령 흐름 등)이 이미 처리했거나,
        // 초점이 서랍 밖 입력(서신 글 등)에 있으면 그 자리의 Esc 다(#1218 리뷰).
        const onKey = (e: KeyboardEvent) => {
            if (e.key !== 'Escape' || e.defaultPrevented) return;
            const at = document.activeElement;
            if (at && at !== document.body && !drawer.current?.contains(at)) return;
            setOpen(false);
            requestAnimationFrame(() => handle.current?.focus());
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [open, mobile]);

    useEffect(() => { onOpenChange?.(open); }, [open, onOpenChange]);
    // 열린 채 사라지면(장수 정보가 빠지는 등) 틀이 덮은 폭을 계속 쥐지 않게 닫힘을 알린다(#1218 리뷰).
    useEffect(() => () => onOpenChange?.(false), [onOpenChange]);

    const notice = campaignReadNotice({ loading: false, error: null }, read.data?.status);
    const body = useMemo(() => {
        if (read.error) return <StatusView kind="error" title="지난 순을 불러오지 못했습니다" errorCode={read.errorCode ?? undefined} onRetry={() => setAttempt((n) => n + 1)} />;
        if (notice) return <StatusView kind="waiting" title={notice} />;
        if (!ready) return <StatusView kind="loading" rows={5} />;
        return <Feed data={ready} scope={scope} filter={filter} hrefs={hrefs} />;
    }, [read.error, read.errorCode, notice, ready, scope, filter, hrefs]);

    const inner = (
        <>
            <Seg label="범위" options={FEED_SCOPES} value={scope} onChange={setScope} className={styles.scopes} />
            <div className={styles.filters} role="group" aria-label="분류">
                {FEED_FILTERS.map((f) => (
                    <button key={f.value} type="button" className={`os-button os-button--sm ${styles.filter}`} aria-pressed={filter === f.value} onClick={() => setFilter(f.value)}>{f.label}</button>
                ))}
            </div>
            <div className={styles.feed}>{body}</div>
            <Link href={hrefs.records} className={styles.all}>기록 전체 보기 →</Link>
        </>
    );
    const label = count == null ? '지난 순' : `지난 순 — 새 기록 ${count}`;
    const toggle = () => setOpen((o) => !o);

    if (mobile) {
        return (
            <>
                <button ref={handle} type="button" className={styles.chipHandle} aria-label={label} aria-expanded={open} onClick={toggle}>
                    지난 순 <Chip tone="bronze">{count == null ? '—' : String(count)}</Chip>
                </button>
                {open ? (
                    <Modal ariaLabel="지난 순" onClose={() => setOpen(false)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.sheet}>
                            <div className={styles.head}>
                                <h3 className={`os-serif ${styles.headTitle}`}>지난 순</h3>
                                {range ? <span className={`os-mono ${styles.range}`}>{range}</span> : null}
                                <button type="button" className={`os-button os-button--sm ${styles.closeBtn}`} onClick={() => setOpen(false)}>닫기</button>
                            </div>
                            {inner}
                        </div>
                    </Modal>
                ) : null}
            </>
        );
    }
    return (
        <>
            {open ? null : (
                <button ref={handle} type="button" className={styles.handle} aria-label={label} aria-expanded={false} onClick={toggle}>
                    <span className={styles.handleText}>지난 순</span>
                    <Chip tone="bronze">{count == null ? '—' : String(count)}</Chip>
                </button>
            )}
            {open ? (
                <section ref={drawer} className={styles.drawer} aria-label="지난 순">
                    <div className={styles.head}>
                        <h3 className={`os-serif ${styles.headTitle}`}>지난 순</h3>
                        {range ? <span className={`os-mono ${styles.range}`}>{range}</span> : null}
                        <button ref={close} type="button" className={`os-button os-button--sm ${styles.closeBtn}`} aria-label="서랍 닫기(Esc)"
                            onClick={shut}>닫기</button>
                    </div>
                    {inner}
                </section>
            ) : null}
        </>
    );
}
