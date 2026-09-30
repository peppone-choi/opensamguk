'use client';

import Link from 'next/link';
import { Chip, RECORD_SECTION_LABEL, RECORD_SECTION_ORDER, ReasonTooltip, Seg, type RecordSection } from '@opensamguk/ui';
import { LAST_TURN_TAB_LABEL, type LastTurnGroup, type LastTurnItem, type LastTurnTab } from '@/lib/last-turn-view';
import styles from './lastturn.module.css';

/** 데스크톱 손잡이(44 × 132, 지도 왼쪽 가장자리) — 세로 「지난 순」 + 새 기록 수. 받기 전이면 「—」. */
export function LastTurnHandle({ count, open, onToggle }: { readonly count: number | null; readonly open: boolean; readonly onToggle: () => void }) {
    return (
        <button type="button" className={styles.handle} aria-expanded={open} aria-controls="last-turn-drawer" onClick={onToggle}>
            <span className={styles.handleText}>지난 순</span>
            <span className="os-chip os-chip--bronze">{count ?? '—'}</span>
        </button>
    );
}

/** 모바일 위 줄의 「지난 순 n」 칩 단추 — 하단 시트를 연다. */
export function LastTurnChipButton({ count, onOpen }: { readonly count: number | null; readonly onOpen: () => void }) {
    return (
        <button type="button" className={styles.chipButton} aria-haspopup="dialog" onClick={onOpen}>
            {`지난 순 ${count ?? '—'}`}
        </button>
    );
}

const TABS = (Object.keys(LAST_TURN_TAB_LABEL) as LastTurnTab[]).map((value) => ({ value, label: LAST_TURN_TAB_LABEL[value] }));
const ALL = 'ALL' as const;
const SECTIONS = [{ value: ALL, label: '전체' }, ...RECORD_SECTION_ORDER.map((value) => ({ value, label: RECORD_SECTION_LABEL[value] }))];

export interface LastTurnFiltersProps {
    readonly tab: LastTurnTab;
    readonly onTab: (tab: LastTurnTab) => void;
    readonly section: RecordSection | null;
    readonly onSection: (section: RecordSection | null) => void;
}

/** 탭(내 12순 · 부 · 세력 · 전체) + 분류 거르기(전체 + 5분류). */
export function LastTurnFilters({ tab, onTab, section, onSection }: LastTurnFiltersProps) {
    return (
        <div className={styles.filters}>
            <Seg label="지난 순 범위" options={TABS} value={tab} onChange={onTab} />
            <Seg label="분류" options={SECTIONS} value={section ?? ALL} onChange={(v) => onSection(v === ALL ? null : v)} scroll />
        </div>
    );
}

export interface LastTurnListProps {
    readonly groups: readonly LastTurnGroup[];
    /** 조정 공문 「응답하기」 — 같은 요청 카드(K6)를 그 자리에서 연다. */
    readonly onReply: (dispatchId: string) => void;
    readonly yuedanHref?: string;
    readonly countyHref?: (countyId: number) => string;
    readonly recordsHref?: string;
}

const WHY = '처리되지 않은 입력입니다 — 비용은 들지 않았습니다.';

function Item({ it, onReply, yuedanHref, countyHref }: { readonly it: LastTurnItem } & Omit<LastTurnListProps, 'groups' | 'recordsHref'>) {
    return (
        <li className={styles.item} data-section={it.section ?? undefined}>
            <span className={styles.chips}>
                {it.section ? <Chip>{RECORD_SECTION_LABEL[it.section]}</Chip> : null}
                {it.status === 'invalid' ? <Chip tone="rust">무효</Chip> : null}
                {it.status === 'awaitingReply' ? <Chip tone="bronze">응답 대기</Chip> : null}
            </span>
            <p className={styles.text}>{it.text}</p>
            {it.shortcuts.length > 0 ? (
                <span className={styles.shortcuts}>
                    {it.shortcuts.map((s) => {
                        if (s === 'reply' && it.dispatchId) {
                            const id = it.dispatchId;
                            return <button key={s} type="button" className="os-button os-button--primary" onClick={() => onReply(id)}>응답하기</button>;
                        }
                        if (s === 'yuedan' && yuedanHref) return <Link key={s} href={yuedanHref} className="os-button">월단평 열기</Link>;
                        if (s === 'county' && it.countyId != null && countyHref) return <Link key={s} href={countyHref(it.countyId)} className="os-button">현 보기</Link>;
                        if (s === 'why') {
                            return (
                                <ReasonTooltip key={s} reason={it.text} title={WHY}>
                                    {(describedBy) => <button type="button" className="os-button" aria-haspopup="dialog" aria-describedby={describedBy}>왜?</button>}
                                </ReasonTooltip>
                            );
                        }
                        return null;
                    })}
                </span>
            ) : null}
        </li>
    );
}

/**
 * 12순 목록 — 순 묶음(새것부터), 기록 없는 순은 접은 한 줄. 항목 = 분류 칩 + 상태 칩 / 서버 문장 / 바로가기.
 * 모두 비면 빈 상태 한 줄. 맨 아래 「기록 전체 보기 →」(P-H01, K5).
 */
export function LastTurnList({ groups, onReply, yuedanHref, countyHref, recordsHref }: LastTurnListProps) {
    const empty = groups.every((g) => g.items.length === 0);
    return (
        <div className={styles.list}>
            {empty ? (
                <p className={styles.empty} role="status">최근 12순에 남은 기록이 없습니다 — 첫 명령을 넣으면 여기에 결과가 남습니다.</p>
            ) : (
                <ol className={styles.groups} aria-label="지난 12순">
                    {groups.map((g) => g.items.length === 0 ? (
                        <li key={g.key} className={styles.emptyGroup}>{`${g.when} — 기록 없음`}</li>
                    ) : (
                        <li key={g.key} className={styles.group}>
                            <h4 className={`os-mono ${styles.when}`}>{g.when}</h4>
                            <ul className={styles.items}>
                                {g.items.map((it) => <Item key={it.key} it={it} onReply={onReply} yuedanHref={yuedanHref} countyHref={countyHref} />)}
                            </ul>
                        </li>
                    ))}
                </ol>
            )}
            {recordsHref ? <Link href={recordsHref} className={styles.all}>기록 전체 보기 →</Link> : null}
        </div>
    );
}
