'use client';

// 보낸 도움 요청(D68) — 보드 V31K6MHelpStatus(요청 탭 › 보낸 것 카드) · V31K6HelpRequest(가운데 상태 칸), 원장 D111 승인.
// 카드는 받은 요청과 같은 요청 카드(RequestCard)에 발만 바꾼다: 종류 칩 「도움 요청」 · 받는 사람 · 기한 · 청한 것 · 상태 칩 + 한 줄.
// 응답 단추는 없다(보낸 쪽). 「단계 보기」를 펴면 여섯 단계 중 지금 단계를 표시한다 — 서버가 준 단계만, 「수락」 ≠ 도움 옴.
// 보낸 요청 읽기 API 가 아직 없어(계약판 H01) 목록은 「서버 대기」다. 카드 · 단계는 시험 고정 자료로만 그려 본다.
import Link from 'next/link';
import { useId, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import { RequestCard } from '@/components/requests/RequestCard';
import {
    HELP_STATE_LABEL, HELP_STATE_TONE, HELP_STATES, SERVER_WAIT, helpStateLine, helpWhat, type HelpRequestView, type HelpState,
} from '@/lib/mail/help-request';
import styles from './HelpRequest.module.css';

const chipClass = (s: HelpState) => ['os-chip', HELP_STATE_TONE[s] ? `os-chip--${HELP_STATE_TONE[s]}` : ''].filter(Boolean).join(' ');

/** 보낸 도움 요청 목록 — items 가 null 이면 서버가 아직 주지 않는 것(「서버 대기」, 빈 목록과 다르다). */
export function SentHelpRequests({ items }: { readonly items: readonly HelpRequestView[] | null }) {
    if (items == null) {
        return (
            <p className={styles.waitRow} data-server-wait="H01 · 보낸 요청 읽기">
                <span className="os-chip os-chip--info">{SERVER_WAIT}</span> 보낸 도움 요청은 서버가 아직 주지 않습니다. 서버가 열리면 판단 대기 · 수락 · 출발함 같은 단계가 여기 보입니다.
            </p>
        );
    }
    if (items.length === 0) return <StatusView kind="empty" title="보낸 도움 요청이 없습니다" body="서신 쓰기에서 「도움 요청」을 골라 청할 수 있습니다." />;
    return (
        <ul className={styles.cards} aria-label="보낸 도움 요청">
            {items.map((v) => <li key={v.id}><HelpRequestCard item={v} /></li>)}
        </ul>
    );
}

export function HelpRequestCard({ item }: { readonly item: HelpRequestView }) {
    const [open, setOpen] = useState(false);
    const ladder = useId();
    return (
        <RequestCard
            kind="도움 요청"
            from={{ name: item.to }}
            what={helpWhat(item)}
            due={item.deadline ? `기한 ${item.deadline}` : undefined}
            foot={(
                <div className={styles.foot}>
                    <p className={styles.stateLine}>
                        <span className={chipClass(item.state)}>{HELP_STATE_LABEL[item.state]}</span>
                        <span>{helpStateLine(item)}</span>
                    </p>
                    <div className={styles.footActions}>
                        {item.state === 'departed' && item.eventHref ? <Link href={item.eventHref} className="os-button os-button--ghost">사건 보기</Link> : null}
                        <button type="button" className="os-button os-button--ghost" aria-expanded={open} aria-controls={ladder} onClick={() => setOpen((o) => !o)}>
                            {open ? '단계 접기' : '단계 보기'}
                        </button>
                    </div>
                    {open ? <HelpStateLadder id={ladder} state={item.state} /> : null}
                </div>
            )}
        />
    );
}

/** 여섯 단계 — 지금 단계에 aria-current="step". 대기 · 거절 · 기한 지남은 갈래이므로 차례가 아니라 목록이다. */
export function HelpStateLadder({ id, state }: { readonly id?: string; readonly state: HelpState }) {
    return (
        <div id={id} className={styles.ladder}>
            <ol aria-label="도움 요청 단계 — 서버가 준 단계만">
                {HELP_STATES.map((s) => (
                    <li key={s} aria-current={s === state ? 'step' : undefined} className={s === state ? styles.now : undefined}>
                        <span className={chipClass(s)}>{HELP_STATE_LABEL[s]}</span>
                    </li>
                ))}
            </ol>
            <p className={styles.note}>수락만으로 도움이 온 것이 아닙니다. 실제 출발 · 이전 사건이 오면 「출발함」으로 바뀌고, 도착하면 사건 기록으로 보입니다.</p>
        </div>
    );
}

export default SentHelpRequests;
