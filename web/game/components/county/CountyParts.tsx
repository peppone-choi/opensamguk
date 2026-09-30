'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, InputAction, type InputAvailability } from '@opensamguk/ui';
import type { Stock } from '@/lib/campaign-reads';
import { COUNTY_RECORDS_EMPTY, type CountyNation, type SpecialtyRow } from '@/lib/county-view';
import { stockChips, type PolicyRow } from '@/lib/territory-view';
import styles from './county.module.css';

export interface CountyHeaderProps {
    readonly name: string;
    readonly commanderyName: string | null;
    /** 소속. 모르면(우리 현이 아니고 서버가 소유를 주지 않음) null — 칩을 그리지 않는다(「무주」 로 짐작하지 않는다). */
    readonly nation: CountyNation | null;
    /** 수도와 끊김(창고 supplied=false). 모르면 null — 칩 없음. */
    readonly isolated: boolean | null;
    /** 내 장수가 지금 이 현에 있다. */
    readonly here: boolean;
    /** 「다른 현 보기」 · 「지도에서 보기」 단추 자리. */
    readonly actions?: ReactNode;
}

/** 머리(보드 V31K4County 88) — 현 이름(명조 24) · 군 · 소속(무주) · 고립 · 지금 여기. 한자 병기는 twin() 규칙 — 여기서 붙이지 않는다. */
export function CountyHeader({ name, commanderyName, nation, isolated, here, actions }: CountyHeaderProps) {
    return (
        <header className={styles.head}>
            <h2 className={`os-serif ${styles.name}`}>{name}</h2>
            <span className={styles.chips}>
                {commanderyName ? <Chip>{commanderyName}</Chip> : null}
                {nation ? (
                    <Chip>
                        {nation.color ? <i className={styles.swatch} style={{ background: nation.color }} aria-hidden="true" /> : null}
                        {nation.label}
                    </Chip>
                ) : null}
                {isolated ? <Chip tone="rust">고립</Chip> : null}
                {here ? <Chip tone="bronze">지금 여기</Chip> : null}
            </span>
            {actions ? <span className={styles.headActions}>{actions}</span> : null}
        </header>
    );
}

/** 특산(월 산출 · 설계값) + 0 인 까닭 한 줄. */
export function CountySpecialties({ rows, zeroReason }: { readonly rows: readonly SpecialtyRow[]; readonly zeroReason: string | null }) {
    if (rows.length === 0) return <p className={styles.muted}>특산이 없는 현입니다.</p>;
    return (
        <div className={styles.block}>
            <ul className={styles.specialties} aria-label="특산">
                {rows.map((s) => (
                    <li key={s.resource} className={styles.specialty}>
                        <span>{s.label}</span>
                        <span className="os-mono">{s.monthly}</span>
                        {s.ledger ? <span className={styles.muted}>{s.ledger}</span> : null}
                    </li>
                ))}
            </ul>
            {zeroReason && rows.some((s) => s.zero) ? <p className={styles.muted}>{zeroReason}</p> : null}
        </div>
    );
}

/** 현 창고 5자원 칩(0 은 뺀다). 창고가 없으면 한 줄. */
export function CountyStock({ stock }: { readonly stock: Stock | null }) {
    if (!stock) return <p className={styles.muted}>이 현에는 창고가 없습니다.</p>;
    const chips = stockChips(stock);
    return (
        <span className={styles.chips} aria-label="현 창고">
            {chips.length === 0 ? <span className={styles.muted}>창고가 비었습니다.</span> : chips.map((c) => <Chip key={c}>{c}</Chip>)}
        </span>
    );
}

export interface CountyGovernanceProps {
    /** 이 현의 방침 줄(countyPolicyRows 에서). 방침 조회에 없는 현(남의 현 · 관할 밖)이면 null. */
    readonly policy: PolicyRow | null;
    readonly policyAvailability: InputAvailability | null;
    readonly onChangePolicy: () => void;
    /** 현령 앉히기 · 바꾸기(placement.assign, 이 현 미리 채움). */
    readonly seatAvailability: InputAvailability | null;
    readonly onSeat: () => void;
}

/** 다스림 칸 — 현령(빈자리 · 부임 중) · 지금 방침(+ 출처 · 대기) · 현령 앉히기 · 방침 바꾸기. 우리 현이 아니면 읽기만. */
export function CountyGovernance({ policy, policyAvailability, onChangePolicy, seatAvailability, onSeat }: CountyGovernanceProps) {
    if (!policy) return <p className={styles.muted}>우리 현이 아니거나 다스릴 권한이 없는 현입니다.</p>;
    return (
        <div className={styles.block}>
            <div className={styles.kv}>
                <span className={styles.muted}>현령</span>
                <span>{policy.seat ?? '빈자리'}</span>
            </div>
            <InputAction inputId="placement.assign" availability={seatAvailability} label={policy.seat === '빈자리' ? '현령 앉히기' : '현령 바꾸기'} variant="ghost" onAct={onSeat} />
            <div className={styles.kv}>
                <span className={styles.muted}>방침</span>
                <span className={styles.chips}>
                    <span>{policy.now ?? '방침 없음'}</span>
                    {policy.source ? <Chip>{policy.source}</Chip> : null}
                    {policy.pending ? <Chip tone="bronze">{`대기 — 다음 턴부터 ${policy.pending}`}</Chip> : null}
                </span>
            </div>
            {policy.lastApplied ? <span className={styles.muted}>{`지난 적용 ${policy.lastApplied}`}</span> : null}
            <InputAction inputId="policy.set" availability={policyAvailability} label="방침 바꾸기" variant="ghost" onAct={onChangePolicy} />
        </div>
    );
}

/** 최근 사건 — 기록 피드 현 거르기(K5)로 가는 고리. 목록 자체는 K5 기록 부품이 그린다. */
export function CountyRecordsLink({ href, empty }: { readonly href: string; readonly empty: boolean }) {
    return (
        <div className={styles.block}>
            {empty ? <p className={styles.muted} role="status">{COUNTY_RECORDS_EMPTY}</p> : null}
            <Link href={href} className={styles.link}>이 현 기록 모두 보기 →</Link>
        </div>
    );
}

export interface HereActionItem {
    readonly inputId: string;
    readonly label: string;
    readonly availability: InputAvailability | null;
    readonly onAct: () => void;
}

/**
 * 여기서 할 수 있는 행동 — 직접 행동(명령 흐름)은 내 장수가 이 현에 서 있을 때만 옵션이 온다(서버 옵션이 「지금 선 현」만 준다).
 * 이 현에 없으면 첩보만 남기고 한 줄로 알린다. 원장 행이 없는 입력(availability null)은 그리지 않는다.
 */
export function HereActions({ items, here, scout }: { readonly items: readonly HereActionItem[]; readonly here: boolean; readonly scout?: HereActionItem }) {
    return (
        <div className={styles.block}>
            {here ? (
                <ul className={styles.actions} aria-label="여기서 할 수 있는 행동">
                    {items.filter((i) => i.availability).map((i) => (
                        <li key={i.inputId}><InputAction inputId={i.inputId} availability={i.availability} label={i.label} variant="ghost" onAct={i.onAct} block /></li>
                    ))}
                </ul>
            ) : <p className={styles.muted}>내정 · 군사 행동은 내 장수가 이 현에 있을 때만 할 수 있습니다.</p>}
            {scout?.availability ? <InputAction inputId={scout.inputId} availability={scout.availability} label={scout.label} onAct={scout.onAct} block /> : null}
        </div>
    );
}
