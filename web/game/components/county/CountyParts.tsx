'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, Gauge, SectionHeader, StatusView, withParticle, type InputAvailability } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { CAMPAIGN_RESOURCE_LABELS, type County, type CountyPolicy, type CountyWorks } from '@/lib/campaign-reads';
import { specialtyText, type CountyHead, type CountyStock, type CountyVision, type IndicatorRow } from '@/lib/county-view';
import styles from './county.module.css';

const SOURCE_LABEL: Readonly<Record<string, string>> = { COMMANDERY: '군 방침', COUNTY: '현 방침', DEFAULT: '기본' };

export function Section({ title, sub, label, children, className }: {
    readonly title: string;
    readonly sub?: string;
    readonly label?: string;
    readonly className?: string;
    readonly children: ReactNode;
}) {
    return (
        <section className={`os-panel ${className ?? ''}`} aria-label={label ?? title}>
            <SectionHeader title={title} sub={sub} />
            {children}
        </section>
    );
}

/** 서버 대기 A — 그 칸을 주는 읽기가 아직 없다(값을 짓지 않는다). */
export function ServerWaiting({ title, body }: { readonly title: string; readonly body: string }) {
    return <StatusView kind="waiting" title={title} body={body} />;
}

/** 머리 칩 줄(보드 county_head) — 군 · 소속 · 수도 · 치소 · 고립 · 지금 여기 · 시야. 한자 병기는 하지 않는다(같은 읽기가 함께 나올 때만, 3.1.4). */
export function HeadChips({ head, vision }: { readonly head: CountyHead; readonly vision: CountyVision }) {
    return (
        <div className={styles.chips}>
            {head.commanderyName ? <Chip>{head.commanderyName}</Chip> : null}
            <span className={styles.nation}>
                {head.ownerColor ? <i aria-hidden="true" style={{ background: head.ownerColor }} /> : null}
                {head.ownerName}
            </span>
            {head.isCapital ? <Chip tone="bronze">수도</Chip> : null}
            {head.isSeat ? <Chip>군 치소</Chip> : null}
            {head.isolated ? <Chip tone="rust">고립</Chip> : null}
            {head.here ? <Chip tone="info">지금 여기</Chip> : null}
            {vision.tier === 'INTEL' ? <Chip tone="info">{vision.ageTurns == null ? '첩보' : `첩보 ${vision.ageTurns}순 전`}</Chip> : null}
            {vision.tier === 'FOG' ? <Chip>안 보임</Chip> : null}
        </div>
    );
}

/** 형편 7지표 — 지금 값이 없으면 서버 대기(내 장수가 선 현만 front-info 가 준다). */
export function Indicators({ rows }: { readonly rows: readonly IndicatorRow[] | null }) {
    if (!rows) {
        return <ServerWaiting title="형편 7지표 — 서버 대기" body="지금은 내 장수가 선 현의 값만 받습니다. 다른 현의 호구 · 전답 · 시장 · 치안 · 민심 · 방비 · 성벽은 현 상세 읽기가 오면 보입니다." />;
    }
    return (
        <div className={styles.gauges} role="group" aria-label="형편 7지표">
            {rows.map((r) => <Gauge key={r.label} label={r.label} value={r.value} max={r.max} tone={r.tone} />)}
        </div>
    );
}

/** 특산 — `/api/county/{id}` 그대로. 실패는 「없음」과 다른 줄. */
export function Specialties({ county, failed }: { readonly county: County | null; readonly failed: boolean }) {
    let body: ReactNode;
    if (failed) body = <span className={styles.errText}>특산을 불러오지 못했습니다.</span>;
    else if (!county) body = <span className={styles.muted}>불러오는 중</span>;
    else if (county.status !== 'READY') body = <span className={styles.muted}>지금은 특산을 볼 수 없습니다.</span>;
    else if (county.specialties.length === 0) body = <span className={styles.muted}>특산 없음</span>;
    else body = county.specialties.map((s) => <Chip key={s.resource}>{specialtyText(s)}</Chip>);
    return (
        <div className={styles.row}>
            <span className={styles.rowLabel}>특산</span>
            <div className={styles.chips}>{body}</div>
        </div>
    );
}

/** 이 현 창고 — 다섯 자원 칩. 남의 현은 안 보임, 우리 현인데 창고가 없으면 그렇게. */
export function StockRow({ stock }: { readonly stock: CountyStock }) {
    let body: ReactNode;
    if (stock.kind === 'hidden') body = <span className={styles.hidden}>안 보임 — 우리 현이 아닙니다</span>;
    else if (stock.kind === 'none') body = <span className={styles.muted}>이 현에는 창고가 없습니다.</span>;
    else if (stock.kind === 'unknown') body = <span className={styles.muted}>창고를 확인하지 못했습니다.</span>;
    else {
        body = (
            <>
                {CAMPAIGN_RESOURCE_LABELS.map((r) => <Chip key={r.key}>{`${r.label} ${stock.stock[r.key].toLocaleString('ko-KR')}`}</Chip>)}
                {stock.supplied ? null : <Chip tone="rust">수도와 끊김</Chip>}
            </>
        );
    }
    return (
        <div className={styles.row}>
            <span className={styles.rowLabel}>이 현 창고</span>
            <div className={styles.chips}>{body}</div>
        </div>
    );
}

/** 능력이 움직이는 것(설계 §8.2) — 현령 카드 보조 줄. */
const ABILITY_NOTES: readonly (readonly [string, string])[] = [
    ['정치', '세수 · 개간'], ['매력', '민심 · 유민'], ['통솔', '치안 · 둔전병'], ['지력', '공사 속도'], ['향당', '본관이 이 현인 인물이면 보너스'],
];

/** 다스림 — 현령 · 방침(우리 현만 서버가 준다). 바꾸기는 영지 화면의 시트에서 한다. */
export function Governance({ policy, mine, placement, policySet, onPlacement, onPolicy, courtHref }: {
    readonly policy: CountyPolicy | null;
    readonly mine: boolean;
    readonly placement: InputAvailability | null;
    readonly policySet: InputAvailability | null;
    readonly onPlacement: () => void;
    readonly onPolicy: () => void;
    readonly courtHref: string;
}) {
    const seat = policy?.seat ?? null;
    return (
        <div className={styles.stack}>
            <div className={styles.seat}>
                <span className={`os-serif ${styles.seatName}`}>{!mine ? '현령 — 안 보임' : seat ? `현령 — ${seat.name}` : '현령 — 빈자리'}</span>
                {mine && !seat ? <Chip tone="rust">빈자리</Chip> : null}
                {mine && seat && !seat.placed ? <Chip tone="info">부임 대기</Chip> : null}
            </div>
            {mine && !seat ? <p className={styles.note}>빈자리면 기본 방침으로 스스로 돌아갑니다. 현령 능력 보정은 없습니다.</p> : null}
            <div className={styles.actions}>
                <HelpedInputAction inputId="placement.assign" availability={placement} label="현령 앉히기 — 배치" variant="ghost" onAct={onPlacement} />
                {mine ? <Link href={courtHref} className={styles.link}>발령은 조정 →</Link> : null}
            </div>
            {mine ? (
                <ul className={styles.bullets}>
                    {ABILITY_NOTES.map(([k, v]) => <li key={k}><b>{k}</b> — {v}</li>)}
                </ul>
            ) : null}
            <div className={styles.row}>
                <span className={styles.rowLabel}>방침</span>
                <span className="os-serif">{policy?.effective?.label ?? (mine ? '—' : '안 보임')}</span>
                {policy?.effective ? <Chip>{SOURCE_LABEL[policy.effective.source] ?? '방침'}</Chip> : null}
                {policy?.pending?.label ? <Chip tone="info">{`다음 순 ${policy.pending.label}`}</Chip> : null}
                <span className={styles.push}>
                    <HelpedInputAction inputId="policy.set" availability={policySet} label="바꾸기" variant="ghost" onAct={onPolicy} />
                </span>
            </div>
        </div>
    );
}

/** 공사 — 이 현의 진행 · 완공 · 새 공사(우리 현만). 새 공사는 영지 공사 칸에서 고른다. */
export function WorksBlock({ works, mine, start, onStart }: {
    readonly works: CountyWorks | null;
    readonly mine: boolean;
    readonly start: InputAvailability | null;
    readonly onStart: () => void;
}) {
    const active = works?.active ?? null;
    return (
        <div className={styles.stack}>
            {!mine ? <p className={styles.hidden}>안 보임 — 우리 현이 아닙니다</p> : null}
            {mine && !works ? <p className={styles.muted}>이 현의 공사를 확인하지 못했습니다.</p> : null}
            {active ? (
                <div className={styles.progress}>
                    <span><span className="os-serif">{active.label}</span> <span className={styles.muted}>{`${active.percent}% · ${active.remainingPhases}순 남음`}</span></span>
                    <div className={styles.bar} data-stopped={active.stopReasonText ? '' : undefined}><i style={{ width: `${Math.max(0, Math.min(100, active.percent))}%` }} /></div>
                    {active.stopReasonText ? <span className={styles.errText}>{active.stopReasonText}</span> : null}
                </div>
            ) : mine && works ? <p className={styles.muted}>진행 중인 공사가 없습니다.</p> : null}
            {works && works.completed.length > 0 ? (
                <div className={styles.chips}>
                    <span className={styles.rowLabel}>완공</span>
                    {works.completed.map((c, i) => <Chip key={`${c.work}-${i}`} tone="moss">{c.label}</Chip>)}
                </div>
            ) : null}
            <HelpedInputAction inputId="work.start" availability={start} label="새 공사 — 영지 공사 칸에서" variant="ghost" onAct={onStart} />
        </div>
    );
}

/** 여기서 할 일 · 다시 보기 — 직접 행동은 명령 흐름으로(대상 현 · 군을 미리 채움). */
export function HereActions({ here, generalName, hereHref, scout, onScout }: {
    readonly here: boolean;
    readonly generalName: string;
    readonly hereHref: string;
    readonly scout: InputAvailability | null;
    readonly onScout: (() => void) | null;
}) {
    return (
        <div className={styles.stack}>
            {here ? null : <p className={styles.note}>{`${withParticle(generalName, '은/는')} 지금 이 현에 없습니다. 내정 · 징병 같은 직접 행동은 이 현에 서 있을 때만 됩니다.`}</p>}
            <Link href={hereHref} className="os-button os-button--primary os-button--block">{here ? '여기서 할 일 — 명령 목록에 넣기' : '여기로 명령'}</Link>
            {onScout ? <HelpedInputAction inputId="action.scout" availability={scout} label="다시 첩보 — 명령 목록에 넣기" block onAct={onScout} /> : null}
        </div>
    );
}
