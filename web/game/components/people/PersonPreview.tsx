'use client';

import Link from 'next/link';
import { Chip, Portrait } from '@opensamguk/ui';
import { locationText, type PeopleRow } from '@/lib/people-view';
import { AptitudeCells, StatCells } from '../retinue/StatCells';
import { Affiliation, NameChips } from './PeopleTable';
import styles from './people.module.css';

export interface PersonPreviewProps {
    readonly row: PeopleRow;
    /** 인물 상세(P-R03) 주소 — 그 화면 전에는 없다(「인물 상세 열기」를 그리지 않는다). */
    readonly detailHref?: string;
    /** 서신 쓰기(P-Q02, K6) 주소 — 받는 사람 미리 채움. 없으면 그리지 않는다. */
    readonly letterHref?: string;
    readonly cityName: (cityId: number) => string | null;
}

/**
 * 오른쪽 미리보기(보드 V31K4People prev, 폭 360) — 초상 · 소속 · 소재 · 5능력 · 적성 넷 · 결속 · 서신 쓰기 · 상세 열기.
 * 이 화면은 입력을 보내지 않는다(설계서 P-R02) — 등용 · 배치 · 발령은 상세나 각 흐름으로 넘긴다.
 */
export function PersonPreview({ row, detailHref, letterHref, cityName }: PersonPreviewProps) {
    const where = locationText(row, cityName);
    return (
        <article className={styles.preview} aria-label={`${row.name} 미리보기`}>
            <div className={styles.previewHead}>
                <Portrait picture={row.picture} imageServer={row.imageServer} size="card-56" alt={`${row.name} 초상`} />
                <div className={styles.previewText}>
                    <h3 className={`os-serif ${styles.previewName}`}>{row.name}</h3>
                    <div className={styles.chips}>
                        <NameChips row={row} />
                        <Affiliation value={row.affiliation} />
                    </div>
                    <span className={styles.muted}>{`소재 ${where}`}</span>
                </div>
            </div>
            <div className={styles.previewBlock}><StatCells stats={row.stats} missing="?" /></div>
            <div className={styles.previewBlock}><AptitudeCells aptitudes={row.aptitudes} missing="?" /></div>
            <div className={`${styles.previewBlock} ${styles.chips}`}>
                {row.bonds == null ? <span className={styles.muted}>결속 ?</span>
                    : row.bonds.length === 0 ? <span className={styles.muted}>결속 없음</span>
                        : row.bonds.map((b) => <Chip key={b} tone="bronze">{b}</Chip>)}
            </div>
            <div className={styles.previewActions}>
                {letterHref ? <Link href={letterHref} className="os-button os-button--block">서신 쓰기</Link> : null}
                {detailHref ? <Link href={detailHref} className="os-button os-button--primary os-button--block">인물 상세 열기</Link> : null}
            </div>
        </article>
    );
}
