'use client';

// 서신 쓰기 — 받는 사람(K3 사람 고르기, NPC 포함) · 본문(보이는 글자 500) · 보내기. K6 설계서 §3.8 · §2.3.
// 세력 · 전체 서신은 받는 사람 대신 「우리 세력 모두에게」 · 「천하 모두에게」. 보낼 수 없으면 단추가 막히고 누르면 이유.
// 외교 서신(§3.7)은 외교권자만 쓰고 받는 세력을 고른다 — 외교권자가 아니면 쓰기 칸 대신 「볼 수 없음」 안내.
// 서신은 입력 원장 밖(지금 메일함과 같은 sendMessage 경로)이라 InputAction 대신 보통 단추를 쓴다.
// 받는 사람 목록(`/generals`, 천하 장수 전부)은 받는 사람 칸에 처음 초점이 가거나 누를 때 읽는다 — 서신만 읽으러 온 사람은
// 받지 않는다(쓰기 칸은 처음부터 보인다). 그 전에는 사람 고르기의 「불러오는 중」을 숨기고 안내 한 줄만 둔다(읽지 않는데 불러오는 척하지 않는다).
// 처음 고른 받는 사람이 있으면(인물 카드 「서신」) 이름을 맞춰야 하니 바로 읽는다.
import { useCallback, useEffect, useState } from 'react';
import { PeoplePicker, ReasonTooltip, StatusView, type PersonOption } from '@opensamguk/ui';
import { RichTextEditor } from '@/components/RichTextEditor';
import { api } from '@/lib/api';
import { toDiplomacyWrite, type ContactList, type DiplomacyWrite } from '@/lib/mail/diplomacy';
import type { MailScope } from '@/lib/mail/mail-model';
import { toRecipientOptions } from '@/lib/mail/recipients';
import { MAIL_TEXT_MAX, visibleLength } from '@/lib/mail/text';
import { composeProblem, sendMail, type MailMe, type MailOutcome, type MailRecipient } from '@/lib/mail/use-mail';
import styles from './Mail.module.css';

export interface MailComposeProps {
    readonly me: MailMe;
    readonly scope: MailScope;
    /** 처음 고른 받는 사람(인물 카드 「서신」에서 열 때). */
    readonly initialRecipientId?: number | null;
    readonly onSent?: () => void;
}

type PeopleLoad = { state: 'idle' } | { state: 'loading' } | { state: 'error' } | { state: 'ready'; people: PersonOption[] };
type NationsLoad = { state: 'loading' } | { state: 'error' } | { state: 'ready'; write: DiplomacyWrite };

const SCOPE_TARGET: Record<'national' | 'public', string> = { national: '우리 세력 모두에게', public: '천하 모두에게' };
const PEOPLE_IDLE_HINT = '찾기 칸을 누르면 받을 사람 목록이 나옵니다.';

export function MailCompose({ me, scope, initialRecipientId = null, onSent }: MailComposeProps) {
    const [peopleWanted, setPeopleWanted] = useState(initialRecipientId !== null);
    const [people, setPeople] = useState<PeopleLoad>(initialRecipientId !== null ? { state: 'loading' } : { state: 'idle' });
    const [peopleSeq, setPeopleSeq] = useState(0);
    const wantPeople = useCallback(() => setPeopleWanted(true), []);
    const [recipientId, setRecipientId] = useState<number | null>(initialRecipientId);
    const [nations, setNations] = useState<NationsLoad>({ state: 'loading' });
    const [nationsSeq, setNationsSeq] = useState(0);
    const [nationId, setNationId] = useState<number | null>(null);
    const [html, setHtml] = useState('');
    const [sending, setSending] = useState(false);
    const [outcome, setOutcome] = useState<MailOutcome | null>(null);

    const { generalId, nationId: myNationId } = me;
    useEffect(() => {
        if (scope !== 'private' || !peopleWanted) return undefined;
        let alive = true;
        setPeople({ state: 'loading' });
        api.generalsList()
            .then((list) => { if (alive) setPeople({ state: 'ready', people: toRecipientOptions(list, { generalId, nationId: myNationId }) }); })
            .catch(() => { if (alive) setPeople({ state: 'error' }); });
        return () => { alive = false; };
    }, [scope, generalId, myNationId, peopleSeq, peopleWanted]);

    useEffect(() => {
        if (scope !== 'diplomacy') return undefined;
        let alive = true;
        setNations({ state: 'loading' });
        api.contacts<ContactList>()
            .then((list) => { if (alive) setNations({ state: 'ready', write: toDiplomacyWrite(list, { generalId, nationId: myNationId }) }); })
            .catch(() => { if (alive) setNations({ state: 'error' }); });
        return () => { alive = false; };
    }, [scope, generalId, myNationId, nationsSeq]);

    const person = people.state === 'ready' ? people.people.find((p) => p.generalId === recipientId) ?? null : null;
    const nation = nations.state === 'ready' ? nations.write.targets.find((n) => n.nationId === nationId) ?? null : null;
    const recipient: MailRecipient | null = scope === 'private'
        ? (person ? { kind: 'general', generalId: person.generalId, name: person.name } : null)
        : scope === 'diplomacy'
            ? (nation ? { kind: 'nation', nationId: nation.nationId, name: nation.name } : null)
            : null;
    const problem = composeProblem(html, scope, recipient);

    const send = useCallback(async () => {
        if (sending || problem) return;
        setSending(true); setOutcome(null);
        try {
            const out = await sendMail(me, scope, recipient, html);
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

    const title = scope === 'diplomacy' ? '외교 서신 쓰기' : '서신 쓰기';
    if (scope === 'diplomacy' && nations.state !== 'ready') {
        return (
            <section className={styles.compose} aria-label={title}>
                <h3 className={styles.composeTitle}>{title}</h3>
                {nations.state === 'loading' ? <StatusView kind="loading" rows={2} />
                    : <StatusView kind="error" title="보낼 세력을 불러오지 못했습니다" onRetry={() => setNationsSeq((n) => n + 1)} />}
            </section>
        );
    }
    if (scope === 'diplomacy' && nations.state === 'ready' && !nations.write.canWrite) {
        return (
            <section className={styles.compose} aria-label={title}>
                <h3 className={styles.composeTitle}>{title}</h3>
                <StatusView kind="denied" title="외교 서신은 군주 · 외교권자만 씁니다" howTo="군주나 외교권을 받은 사람에게 부탁하세요." />
            </section>
        );
    }

    return (
        <section className={styles.compose} aria-label={title}>
            <h3 className={styles.composeTitle}>{title}</h3>
            {scope === 'diplomacy' && nations.state === 'ready' ? (
                <div className={styles.recipient}>
                    <p className={styles.label}>받는 세력{nation ? <strong> — {nation.name}</strong> : null}</p>
                    {nations.write.targets.length === 0 ? (
                        <StatusView kind="empty" title="보낼 다른 세력이 없습니다" body="천하에 다른 세력이 생기면 외교 서신을 보낼 수 있습니다." />
                    ) : (
                        <div role="listbox" aria-label="받는 세력" className={styles.nations}>
                            {nations.write.targets.map((n) => (
                                <button key={n.nationId} type="button" role="option" aria-selected={n.nationId === nationId}
                                    className={styles.nationOption} onClick={() => setNationId(n.nationId)}>
                                    <span className={styles.mark} aria-hidden="true" />
                                    <span>{n.name}</span>
                                </button>
                            ))}
                        </div>
                    )}
                </div>
            ) : scope === 'private' ? (
                <div className={styles.recipient}>
                    <p className={styles.label}>받는 사람{person ? <strong> — {person.name}</strong> : null}</p>
                    <div className={styles.people} data-idle={people.state === 'idle' || undefined} onFocusCapture={wantPeople} onPointerDownCapture={wantPeople}>
                        <PeoplePicker
                            load={people.state === 'ready' ? { state: 'ready', people: people.people }
                                : people.state === 'error' ? { state: 'error', onRetry: () => setPeopleSeq((n) => n + 1) }
                                : { state: 'loading' }}
                            selected={recipientId}
                            onChange={setRecipientId}
                            label="받는 사람"
                        />
                        {people.state === 'idle' ? <p className={styles.muted}>{PEOPLE_IDLE_HINT}</p> : null}
                    </div>
                </div>
            ) : (
                <p className={styles.label}>받는 사람 — <strong>{SCOPE_TARGET[scope as 'national' | 'public']}</strong></p>
            )}
            <RichTextEditor value={html} onChange={setHtml} maxTextLength={MAIL_TEXT_MAX} countLength={visibleLength} ariaLabel="서신 내용" />
            {outcome ? <p className={styles.outcome} data-kind={outcome.kind} role={outcome.kind === 'error' ? 'alert' : 'status'}>{outcome.text}</p> : null}
            {problem ? <ReasonTooltip reason={problem} title="아직 보낼 수 없습니다" block>{button}</ReasonTooltip> : button}
        </section>
    );
}

export default MailCompose;
