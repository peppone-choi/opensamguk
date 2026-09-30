'use client';

// 요청 카드(RequestCard) — 보드 v31system request_card() 그대로. K6 · K4 · K8 공용(서신 「요청」 탭 · 조정 「받은 요청」 띠 ·
// 지난 순 서랍 · 임명/봉신 제안). props만 받는 순수 표시 부품이다 — 데이터(읽기 · 응답 보내기)는 lib/requests.ts.
//
// 머리 = 초상 30 × 42 · 종류 칩(청동) · 보낸 사람 · 기한(서버 값만) · 한 줄 무엇 · 거절 결과(적갈 한 줄, compact면 뺌).
// 발 = [거절 | 수락] 44(K3 InputAction — 서버 상태로만 그린다) · 응답 뒤 「수락함 · 거절함」 칩 · 또는 부른 쪽이 준 발(foot).
import type { ReactNode } from 'react';
import { InputAction, Portrait, type InputAvailability } from '@opensamguk/ui';
import styles from './RequestCard.module.css';

/** cancelled = 무효로 취소됨(발령). expired = 기한이 지나 끝남(원군 요청처럼 무응답이 만료인 요청 — K8). */
export type RequestCardState = 'waiting' | 'accepted' | 'refused' | 'cancelled' | 'expired';

export interface RequestCardAnswer {
    /** 원장 inputId — 단추의 data-input-id. */
    readonly inputId: string;
    /** 서버 상태 한 행(availabilityOf). null = 원장에 없는 입력 → 단추를 그리지 않는다. */
    readonly availability: InputAvailability | null;
    readonly onAccept: () => void;
    readonly onRefuse: () => void;
    /** 보내는 중 — 누르기를 무시한다. */
    readonly busy?: boolean;
    readonly acceptLabel?: string;
    readonly refuseLabel?: string;
    /**
     * 서버가 방금 응답을 거절했다 — 누른 단추 쪽 사유 시트를 열린 채로 띄운다(availability는 그 거절로 BLOCKED).
     * seq가 바뀔 때마다 새로 연다.
     */
    readonly rejectedOn?: { readonly side: 'accept' | 'refuse'; readonly seq: number } | null;
}

export interface RequestCardProps {
    /** 종류 칩(예: 「발령」 · 「선양 동의」). */
    readonly kind: string;
    readonly from: { readonly name: string; readonly picture?: string | null; readonly imageServer?: number | null };
    /** 한 줄 무엇. */
    readonly what: ReactNode;
    /** 응답 기한 — 서버 값으로 만든 글자만(예: 「200년 3월 하순까지 · 넘기면 수락」). 없으면 그리지 않는다. */
    readonly due?: ReactNode;
    /** 「거절하면 — …」 뒤에 붙는 한 줄. 서버 규칙이 정한 것만. compact면 그리지 않는다. */
    readonly consequence?: ReactNode;
    readonly state?: RequestCardState;
    /** 좁은 폭(서랍 380) — 거절 결과 줄을 뺀다. */
    readonly compact?: boolean;
    /** 기본 발(거절 | 수락). state가 waiting일 때만 쓴다. */
    readonly answer?: RequestCardAnswer;
    /** 발 바꾸기 — 응답이 둘이 아니거나(원군 요청 넷 · 수량 칸) 응답 입력이 아직 없을 때(한 줄 안내). answer보다 먼저다. */
    readonly foot?: ReactNode;
    readonly className?: string;
}

const DONE: Record<Exclude<RequestCardState, 'waiting'>, { label: string; tone: string }> = {
    accepted: { label: '수락함', tone: 'os-chip--moss' },
    refused: { label: '거절함', tone: 'os-chip--rust' },
    cancelled: { label: '취소됨', tone: '' },
    expired: { label: '기한 지남', tone: '' },
};

export function RequestCard({
    kind, from, what, due, consequence, state = 'waiting', compact = false, answer, foot, className = '',
}: RequestCardProps) {
    let footer: ReactNode = null;
    if (state !== 'waiting') {
        const done = DONE[state];
        footer = <div className={styles.done}><span className={['os-chip', done.tone].filter(Boolean).join(' ')}>{done.label}</span></div>;
    } else if (foot !== undefined) {
        footer = <div className={styles.foot}>{foot}</div>;
    } else if (answer) {
        footer = (
            <div className={styles.answers}>
                <InputAction
                    key={answer.rejectedOn ? `refuse-${answer.rejectedOn.seq}` : 'refuse'}
                    reasonDefaultOpen={answer.rejectedOn?.side === 'refuse'}
                    inputId={answer.inputId}
                    availability={answer.availability}
                    label={answer.refuseLabel ?? '거절'}
                    variant="danger"
                    block
                    busy={answer.busy}
                    reasonTitle={`${kind} — 지금은 응답할 수 없습니다`}
                    onAct={answer.onRefuse}
                    className={styles.answer}
                />
                <InputAction
                    key={answer.rejectedOn ? `accept-${answer.rejectedOn.seq}` : 'accept'}
                    reasonDefaultOpen={answer.rejectedOn?.side === 'accept'}
                    inputId={answer.inputId}
                    availability={answer.availability}
                    label={answer.acceptLabel ?? '수락'}
                    variant="primary"
                    block
                    busy={answer.busy}
                    reasonTitle={`${kind} — 지금은 응답할 수 없습니다`}
                    onAct={answer.onAccept}
                    className={styles.answer}
                />
            </div>
        );
    }

    return (
        <article
            className={[styles.card, compact ? styles.compact : '', className].filter(Boolean).join(' ')}
            aria-label={`${kind} — ${from.name}`}
            data-request-state={state}
        >
            <div className={styles.head}>
                <Portrait picture={from.picture} imageServer={from.imageServer} size="card-36" alt="" className={styles.portrait} />
                <div className={styles.body}>
                    <div className={styles.line}>
                        <span className="os-chip os-chip--bronze">{kind}</span>
                        <span className={styles.who}>{from.name}</span>
                        {due ? <span className={styles.due}>{due}</span> : null}
                    </div>
                    <span className={styles.what}>{what}</span>
                    {consequence && !compact ? <span className={styles.consequence}>거절하면 — {consequence}</span> : null}
                </div>
            </div>
            {footer}
        </article>
    );
}

export default RequestCard;
