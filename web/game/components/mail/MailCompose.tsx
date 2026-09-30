'use client';

// 서신 쓰기 — 받는 사람(K3 사람 고르기, NPC 포함) · 본문(보이는 글자 500) · 보내기. K6 설계서 §3.8 · §2.3.
// 세력 · 전체 서신은 받는 사람 대신 「우리 세력 모두에게」 · 「천하 모두에게」. 보낼 수 없으면 단추가 막히고 누르면 이유.
// 서신은 입력 원장 밖(지금 메일함과 같은 sendMessage 경로)이라 InputAction 대신 보통 단추를 쓴다.
import { useCallback, useEffect, useState } from 'react';
import { PeoplePicker, ReasonTooltip, type PersonOption } from '@opensamguk/ui';
import { RichTextEditor } from '@/components/RichTextEditor';
import { api } from '@/lib/api';
import type { MailScope } from '@/lib/mail/mail-model';
import { toRecipientOptions } from '@/lib/mail/recipients';
import { MAIL_TEXT_MAX, visibleLength } from '@/lib/mail/text';
import { composeProblem, sendMail, type MailMe, type MailOutcome } from '@/lib/mail/use-mail';
import styles from './Mail.module.css';

export interface MailComposeProps {
    readonly me: MailMe;
    readonly scope: MailScope;
    /** 처음 고른 받는 사람(인물 카드 「서신」에서 열 때). */
    readonly initialRecipientId?: number | null;
    readonly onSent?: () => void;
}

type PeopleLoad = { state: 'loading' } | { state: 'error' } | { state: 'ready'; people: PersonOption[] };

const SCOPE_TARGET: Record<Exclude<MailScope, 'private'>, string> = { national: '우리 세력 모두에게', public: '천하 모두에게' };

export function MailCompose({ me, scope, initialRecipientId = null, onSent }: MailComposeProps) {
    const [people, setPeople] = useState<PeopleLoad>({ state: 'loading' });
    const [peopleSeq, setPeopleSeq] = useState(0);
    const [recipientId, setRecipientId] = useState<number | null>(initialRecipientId);
    const [html, setHtml] = useState('');
    const [sending, setSending] = useState(false);
    const [outcome, setOutcome] = useState<MailOutcome | null>(null);

    const { generalId, nationId } = me;
    useEffect(() => {
        if (scope !== 'private') return undefined;
        let alive = true;
        setPeople({ state: 'loading' });
        api.generalsList()
            .then((list) => { if (alive) setPeople({ state: 'ready', people: toRecipientOptions(list, { generalId, nationId }) }); })
            .catch(() => { if (alive) setPeople({ state: 'error' }); });
        return () => { alive = false; };
    }, [scope, generalId, nationId, peopleSeq]);

    const recipient = people.state === 'ready' ? people.people.find((p) => p.generalId === recipientId) ?? null : null;
    const problem = composeProblem(html, scope, scope === 'private' ? recipient?.generalId ?? null : null);

    const send = useCallback(async () => {
        if (sending || problem) return;
        setSending(true); setOutcome(null);
        try {
            const out = await sendMail(me, scope, recipient ? { generalId: recipient.generalId, name: recipient.name } : null, html);
            setOutcome(out);
            if (out.kind === 'ok') { setHtml(''); onSent?.(); }
        } catch {
            setOutcome({ kind: 'error', text: '서신을 보내지 못했습니다 — 잠시 뒤 다시 해 보세요' });
        } finally {
            setSending(false);
        }
    }, [html, me, onSent, problem, recipient, scope, sending]);

    const button = (
        <button
            type="button"
            className={`os-button os-button--primary os-button--block ${styles.send}`}
            aria-disabled={problem ? true : undefined}
            aria-busy={sending || undefined}
            onClick={() => void send()}
        >
            {sending ? '보내는 중…' : '보내기'}
        </button>
    );

    return (
        <section className={styles.compose} aria-label="서신 쓰기">
            <h3 className={styles.composeTitle}>서신 쓰기</h3>
            {scope === 'private' ? (
                <div className={styles.recipient}>
                    <p className={styles.label}>받는 사람{recipient ? <strong> — {recipient.name}</strong> : null}</p>
                    <PeoplePicker
                        load={people.state === 'ready' ? { state: 'ready', people: people.people }
                            : people.state === 'error' ? { state: 'error', onRetry: () => setPeopleSeq((n) => n + 1) }
                            : { state: 'loading' }}
                        selected={recipientId}
                        onChange={setRecipientId}
                        label="받는 사람"
                    />
                </div>
            ) : (
                <p className={styles.label}>받는 사람 — <strong>{SCOPE_TARGET[scope]}</strong></p>
            )}
            <RichTextEditor value={html} onChange={setHtml} maxTextLength={MAIL_TEXT_MAX} countLength={visibleLength} ariaLabel="서신 내용" />
            {outcome ? <p className={styles.outcome} data-kind={outcome.kind} role={outcome.kind === 'error' ? 'alert' : 'status'}>{outcome.text}</p> : null}
            {problem ? <ReasonTooltip reason={problem} title="아직 보낼 수 없습니다" block>{button}</ReasonTooltip> : button}
        </section>
    );
}

export default MailCompose;
