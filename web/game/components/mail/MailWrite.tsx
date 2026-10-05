'use client';

// 서신 쓰기 칸 — 개인 서신(서신 화면)에서는 「글 서신 | 도움 요청」을 고른다(보드 V31K6HelpRequest · MHelpRequest, D68 · 원장 D111).
// 도움 요청은 글 서신과 따로인 정해진 양식(HelpRequestForm)이다. 세력 · 전체 · 외교 서신과 머리줄 서랍의 「짧은 서신」에는 양식을 넣지 않는다
// (요구 §6.1 — 서랍에서는 「서신에서 쓰기」로 이 화면에 온다).
import { useState } from 'react';
import { HelpRequestForm } from './HelpRequestForm';
import { MailCompose, type MailComposeProps } from './MailCompose';
import styles from './HelpRequest.module.css';

type WriteKind = 'letter' | 'help';
const WRITE_KIND_LABEL: Readonly<Record<WriteKind, string>> = { letter: '글 서신', help: '도움 요청' };

export function MailWrite(props: MailComposeProps) {
    const [kind, setKind] = useState<WriteKind>('letter');
    if (props.scope !== 'private' || props.short) return <MailCompose {...props} />;
    return (
        <div className={styles.write}>
            <div className={styles.seg} role="group" aria-label="서신 종류">
                {(['letter', 'help'] as const).map((k) => (
                    <button key={k} type="button" className={styles.segButton} aria-pressed={kind === k} onClick={() => setKind(k)}>
                        {WRITE_KIND_LABEL[k]}
                    </button>
                ))}
            </div>
            {kind === 'letter' ? <MailCompose {...props} /> : <HelpRequestForm />}
        </div>
    );
}

export default MailWrite;
