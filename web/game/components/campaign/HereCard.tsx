'use client';

import Link from 'next/link';
import { useState } from 'react';
import { Chip, Modal } from '@opensamguk/ui';
import type { FrontCityInfo } from '@/lib/types';
import CountyPanel from './CountyPanel';
import styles from './WarRoomPage.module.css';

export interface HereCardProps {
    readonly city: FrontCityInfo | null;
    readonly mobile: boolean;
    /** 현 상세(P-T02) 주소. */
    readonly countyHref: (cityId: number) => string;
    /** 「여기로 명령」 — 이 현을 대상으로 명령 흐름. */
    readonly onCommandHere: (cityId: number) => void;
}

/**
 * 내 위치 — 알약(데스크톱 지도 왼쪽 위 · 모바일 지도 아래 선택 알약) → 카드(데스크톱 떠 있음 320) · 하단 시트(모바일).
 * 보드 V31K4WarRoom 선택 카드의 첫 쓰임(내가 선 현). 내용은 작전실 縣 카드(CountyPanel — 7지표 · 특산 D40)와 같고 「현 상세 · 여기로 명령」을 붙인다.
 * 지도에서 다른 城을 고르는 선택 카드 · 城 목록 · 검색은 다음 단계(K2 focusCity).
 */
export function HereCard({ city, mobile, countyHref, onCommandHere }: HereCardProps) {
    const [open, setOpen] = useState(false);
    const label = city ? city.name : '성 밖';
    const body = (
        <div className={styles.hereBody}>
            <CountyPanel city={city} />
            {city ? (
                <div className={styles.hereActions}>
                    <Link href={countyHref(city.id)} className="os-button">현 상세</Link>
                    <button type="button" className="os-button os-button--primary" onClick={() => { setOpen(false); onCommandHere(city.id); }}>여기로 명령</button>
                </div>
            ) : null}
        </div>
    );
    const pill = (
        <button type="button" className={mobile ? styles.herePillMobile : styles.herePill} aria-expanded={open} aria-label={`내 위치 — ${label}`}
            onClick={() => setOpen((o) => !o)}>
            <span className={`os-serif ${styles.hereName}`}>{label}</span>
            <Chip tone="bronze">내 위치</Chip>
        </button>
    );
    if (mobile) {
        return (
            <>
                {pill}
                {open ? (
                    <Modal ariaLabel={`내 위치 — ${label}`} onClose={() => setOpen(false)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.sheet}>
                            <div className={styles.turnsHead}>
                                <h2 className={styles.turnsTitle}>내 위치</h2>
                                <button type="button" className={`os-button os-button--sm ${styles.push}`} onClick={() => setOpen(false)}>닫기</button>
                            </div>
                            {body}
                        </div>
                    </Modal>
                ) : null}
            </>
        );
    }
    return (
        <>
            {pill}
            {open ? (
                <section className={styles.hereCard} aria-label={`내 위치 — ${label}`}>
                    <div className={styles.turnsHead}>
                        <h2 className={styles.turnsTitle}>내 위치</h2>
                        <button type="button" className={`os-button os-button--sm ${styles.push}`} onClick={() => setOpen(false)}>닫기</button>
                    </div>
                    {body}
                </section>
            ) : null}
        </>
    );
}
