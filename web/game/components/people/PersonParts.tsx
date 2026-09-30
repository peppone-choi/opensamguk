'use client';

import Link from 'next/link';
import type { ReactNode } from 'react';
import { Chip, InputAction, Portrait, StatusView, type InputAvailability } from '@opensamguk/ui';
import { RELATION_LABEL, relationActions, type PersonRelation, type StateCell } from '@/lib/person-view';
import styles from './people.module.css';

export interface PersonHeroProps {
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly relation: PersonRelation;
    /** 소속 이름 — null 은 재야. */
    readonly affiliation: string | null;
    /** 세력색 링. 재야 · 모름이면 없음. */
    readonly ringColor?: string | null;
    readonly mobile?: boolean;
    readonly actions: ReactNode;
}

/**
 * 히어로(보드 V31K4Person 왼쪽 360 · V31K4MPerson 위) — 초상 원본 · 이름(명조 24) · 칩(관계 · 소속) · 관계에 맞는 단추.
 * 한자 병기 없음(twin() 규칙). 사람/NPC · 부상 · 나이 칩은 K4-13 전까지 없다.
 */
export function PersonHero({ name, picture, imageServer, relation, affiliation, ringColor, mobile = false, actions }: PersonHeroProps) {
    return (
        <section className={mobile ? styles.heroMobile : styles.hero} aria-label={`${name} 인물 카드`}>
            <div className={styles.heroPortrait}>
                <Portrait picture={picture} imageServer={imageServer} size="hero" alt={`${name} 초상`}
                    ring={ringColor ? { reason: 'context', color: ringColor } : undefined} />
            </div>
            <h2 className={`os-serif ${styles.heroName}`}>{name}</h2>
            <div className={styles.chips}>
                <Chip tone={relation === 'SELF' || relation === 'RETINUE' ? 'bronze' : 'neutral'}>{RELATION_LABEL[relation]}</Chip>
                <Chip>{affiliation ? `${affiliation} 소속` : '재야'}</Chip>
            </div>
            <div className={styles.heroActions}>{actions}</div>
        </section>
    );
}

export interface PersonActionsProps {
    readonly relation: PersonRelation;
    readonly hrefs: {
        readonly myRetinue?: string;
        readonly records?: string;
        readonly letter?: string;
        readonly employ?: string;
        /** 내 부의 사람 장수(K4-18 true) — 배치 대신 조정 발령(P-K01). */
        readonly dispatch?: string;
    };
    /** 내 부 인물 — 배치(placement.assign). */
    readonly placement?: { readonly availability: InputAvailability | null; readonly onAct: () => void };
    /** 재야 — 등용(action.employ) 가능 여부. 가능하면 명령 흐름 주소(hrefs.employ)로 간다. */
    readonly employ?: InputAvailability | null;
    readonly onEmploy?: () => void;
}

/** 관계별 단추(설계서 P-R03 표) — 세로 줄 44. 원장 입력은 InputAction(가능 여부는 서버 값), 화면 이동은 고리. */
export function PersonActions({ relation, hrefs, placement, employ, onEmploy }: PersonActionsProps) {
    return (
        <>
            {relationActions(relation).map((kind) => {
                switch (kind) {
                    case 'myRetinue':
                        return hrefs.myRetinue ? <Link key={kind} href={hrefs.myRetinue} className="os-button os-button--primary os-button--block">내 부로</Link> : null;
                    case 'records':
                        return hrefs.records ? <Link key={kind} href={hrefs.records} className="os-button os-button--block">이 인물의 기록</Link> : null;
                    case 'letter':
                        return hrefs.letter ? <Link key={kind} href={hrefs.letter} className="os-button os-button--block">서신 쓰기</Link> : null;
                    case 'placement':
                        if (placement) return <InputAction key={kind} inputId="placement.assign" availability={placement.availability} label="자리에 배치" onAct={placement.onAct} block />;
                        return hrefs.dispatch ? <Link key={kind} href={hrefs.dispatch} className="os-button os-button--primary os-button--block">발령은 조정에서 →</Link> : null;
                    case 'employ':
                        return employ !== undefined ? (
                            <InputAction key={kind} inputId="action.employ" availability={employ} label="이 사람을 등용 — 명령 목록에 넣기" onAct={() => onEmploy?.()} block />
                        ) : null;
                }
            })}
        </>
    );
}

/** 상태 8칸 — 관계 밖 칸은 「? — 내 부 인물만」(흐리게), 서버 대기 칸은 「준비 중」. */
export function PersonStateGrid({ cells }: { readonly cells: readonly StateCell[] }) {
    return (
        <dl className={styles.stateGrid}>
            {cells.map((c) => (
                <div key={c.key} className={`os-inset ${styles.stateCell}`} data-hidden={c.hidden || undefined}>
                    <dt className={styles.muted}>{c.key}</dt>
                    <dd>{c.value}</dd>
                </div>
            ))}
        </dl>
    );
}

/** 서버 대기 칸 둘 — 계책 기여(K4-13) · 인물 관직 카드(K8). */
export function PersonWaitingPanels() {
    return (
        <>
            <StatusView kind="waiting" title="계책 기여 — 준비 중" body="이 인물이 덱에 넣는 계책 카드는 서버가 아직 주지 않습니다." />
            <StatusView kind="waiting" title="관직 카드 — 준비 중" body="관직 체계가 들어오면 조정 · 지방 관직과 관할이 여기에 보입니다." />
        </>
    );
}
