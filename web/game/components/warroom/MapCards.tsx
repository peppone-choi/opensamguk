'use client';

import Link from 'next/link';
import { Chip, InputAction, type InputAvailability } from '@opensamguk/ui';
import type { CountyNation } from '@/lib/county-view';
import { VISIBILITY_LABEL } from '@/lib/commandery-view';
import styles from './warroom.module.css';

export interface SelectedCountyCardProps {
    readonly name: string;
    readonly commanderyName: string | null;
    readonly nation: CountyNation;
    /** 시야 — FULL · INTEL(n순 전) · FOG. 모르면 null. */
    readonly visibility: string | null;
    /** INTEL 일 때 「n순 전」. */
    readonly intelAge?: number | null;
    readonly isolated: boolean | null;
    /** 특산 칩 글자(「철 월 12」). */
    readonly specialties: readonly string[];
    readonly detailHref: string;
    readonly scout: InputAvailability | null;
    readonly onScout: () => void;
    /** 「여기로 명령」 — 명령 흐름 `?target=county:<id>`. */
    readonly commandHref: string;
    readonly onClose: () => void;
}

/**
 * 선택 카드(보드 V31K4WarRoom 떠 있는 320) — 현 이름 · 군 · 소속(무주) · 시야 · 고립 · 특산 · 단추 셋(현 상세 · 첩보 · 여기로 명령, 44).
 * 호버 전용 정보 금지 — 지도에서 누르면 이 카드. 7지표 · 주둔 · 등급 · 귀환 성은 K4-04 · U-04 전까지 「준비 중」 한 줄.
 */
export function SelectedCountyCard(p: SelectedCountyCardProps) {
    return (
        <section className={styles.card} aria-label={`${p.name} 선택`}>
            <header className={styles.cardHead}>
                <h3 className={`os-serif ${styles.cardTitle}`}>{p.name}</h3>
                <button type="button" className={styles.close} aria-label="선택 카드 닫기" onClick={p.onClose}>×</button>
            </header>
            <span className={styles.chips}>
                {p.commanderyName ? <Chip>{p.commanderyName}</Chip> : null}
                <Chip>{p.nation.label}</Chip>
                {p.visibility ? (
                    <Chip tone={p.visibility === 'FULL' ? 'moss' : 'info'}>
                        {p.visibility === 'INTEL' && p.intelAge != null ? `첩보 · ${p.intelAge}순 전` : VISIBILITY_LABEL[p.visibility] ?? '알 수 없음'}
                    </Chip>
                ) : null}
                {p.isolated ? <Chip tone="rust">고립</Chip> : null}
            </span>
            {p.specialties.length > 0 ? <span className={styles.chips}>{p.specialties.map((s) => <Chip key={s} tone="bronze">{s}</Chip>)}</span> : null}
            <p className={styles.muted} data-waiting="county-indicators">현 형편 · 주둔 — 준비 중</p>
            <div className={styles.cardActions}>
                <Link href={p.detailHref} className="os-button os-button--block">현 상세</Link>
                <InputAction inputId="action.scout" availability={p.scout} label="첩보" variant="ghost" onAct={p.onScout} block />
                <Link href={p.commandHref} className="os-button os-button--primary os-button--block">여기로 명령</Link>
            </div>
        </section>
    );
}

export interface MyLocationCardProps {
    /** 지금 선 성(front-info.city). null = 성 밖. */
    readonly cityName: string | null;
    readonly detailHref: string | null;
    readonly commandHref: string;
    readonly onClose: () => void;
}

/**
 * 내 위치 카드(내 초상 핀을 누르면) — 지금 자리 · 성 밖이면 「도시 행동 불가」 + 이유 · 현 상세 · 여기로 명령.
 * 귀환 성 · 이동 중 경로(「n순 뒤 도착」)는 U-04(me.location) 전까지 짓지 않는다.
 */
export function MyLocationCard({ cityName, detailHref, commandHref, onClose }: MyLocationCardProps) {
    return (
        <section className={styles.card} aria-label="내 위치">
            <header className={styles.cardHead}>
                <h3 className={`os-serif ${styles.cardTitle}`}>{cityName ?? '성 밖'}</h3>
                <button type="button" className={styles.close} aria-label="내 위치 카드 닫기" onClick={onClose}>×</button>
            </header>
            {cityName ? <Chip tone="bronze">지금 여기</Chip> : (
                <p className={styles.warn}>성 밖 — 도시 행동을 할 수 없습니다. 내정 · 징병은 성 안에서만 됩니다.</p>
            )}
            <div className={styles.cardActions}>
                {detailHref ? <Link href={detailHref} className="os-button os-button--block">현 상세</Link> : null}
                <Link href={commandHref} className="os-button os-button--primary os-button--block">여기로 명령</Link>
            </div>
        </section>
    );
}
