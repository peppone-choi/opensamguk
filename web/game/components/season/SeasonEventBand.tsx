'use client';

// 현 상세 계절 사건 띠(P-K07, K8 부품 · 자리는 K4 CountyScreen) — 보드 V31K4County · CountyIntel 의 season_band.
// 그 현에 계절 사건이 있을 때만 그린다(CEO 10-05 「값이 있을 때만」, K4 동의).
// 읽기가 없거나(서버 경로 전) 셈하지 못했거나 사건이 없으면 null — 모든 현 상세에 「서버 대기」 띠를 늘 띄우지 않는다.
// 줄마다 사건 이름 · 방향(▼ 민심 등, 수치 없음). 대응(구휼 등) 안내는 대응 입력이 원장에 정해지면 붙인다.

import { Icon } from '@opensamguk/ui';
import { countyEvents, effectMarks, effectMarkText, seasonEventLabel, type SeasonEventsState } from '@/lib/season-events';
import styles from './season.module.css';

export interface SeasonEventBandProps {
    readonly countyId: number | null | undefined;
    /** 계절 사건 상태. 서버 읽기(계약판 K8-08 · K8-EV)가 붙기 전에는 넘기지 않는다. */
    readonly events?: SeasonEventsState;
}

export default function SeasonEventBand({ countyId, events }: SeasonEventBandProps) {
    const here = countyEvents(events, countyId);
    if (here.length === 0) return null;
    return (
        <div className={styles.band} role="status" aria-label="이 현의 계절 사건">
            {here.map((o, i) => (
                <span key={`${o.kind}-${i}`} className={styles.bandRow}>
                    <Icon name="season" size={16} className={styles.bandGlyph} />
                    <b className={styles.eventKind}>{seasonEventLabel(o.kind)}</b>
                    <span className={styles.eventMarks}>
                        {effectMarks(o.effect).map((m) => (
                            <span key={m.label} className={styles.mark}>{effectMarkText(m)}</span>
                        ))}
                    </span>
                </span>
            ))}
        </div>
    );
}
