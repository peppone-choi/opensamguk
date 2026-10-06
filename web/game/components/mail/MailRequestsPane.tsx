'use client';

// 서신 「요청」 탭 — 받은 요청(발령 응답 · 정치 동의, IncomingRequests) · 보낸 도움 요청(D68, SentHelpRequests).
// 서신 화면에서는 「받은 것 | 보낸 것」을 고른다(보드 V31K6MHelpStatus). 머리줄 서신 서랍은 받은 요청만 + 「조정에서 모두 보기」.
// 보낸 도움 요청 읽기 API 가 아직 없어 「보낸 것」은 서버 대기다(계약판 H01).
import Link from 'next/link';
import { useState } from 'react';
import { IncomingRequests } from '@/components/requests/IncomingRequests';
import type { UseRequests } from '@/lib/requests';
import { SentHelpRequests } from './HelpRequestCard';
import help from './HelpRequest.module.css';
import styles from './Mail.module.css';

export interface MailRequestsPaneProps {
    readonly generalId: number;
    readonly requests?: UseRequests;
    readonly variant: 'page' | 'drawer' | 'header';
    readonly courtHref?: string;
}

export function MailRequestsPane({ generalId, requests, variant, courtHref }: MailRequestsPaneProps) {
    const [box, setBox] = useState<'in' | 'out'>('in');
    const page = variant === 'page';
    return (
        <div className={styles.requests}>
            {page ? (
                <div className={`${help.seg} ${help.boxSeg}`} role="group" aria-label="요청 — 받은 것 · 보낸 것">
                    <button type="button" className={help.segButton} aria-pressed={box === 'in'} onClick={() => setBox('in')}>받은 것</button>
                    <button type="button" className={help.segButton} aria-pressed={box === 'out'} onClick={() => setBox('out')}>보낸 것</button>
                </div>
            ) : null}
            {page && box === 'out' ? <SentHelpRequests items={null} /> : <IncomingRequests generalId={generalId} source={requests} compact={!page} />}
            {variant === 'header' && courtHref ? <Link href={courtHref} className={styles.courtLink}>조정에서 모두 보기 →</Link> : null}
        </div>
    );
}

export default MailRequestsPane;
