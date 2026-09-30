'use client';

// 서신 읽기 · 쓰기 · 지우기 — P-Q02 서신 화면과 서신 서랍(머리줄)이 같이 쓴다. K6 설계서 §3.8.
// 상태: 로딩 · 빈 · 볼 수 없음(401 · 403 — 빈 목록과 다른 모양) · 오류(다시 시도) · 이전 서신(불러오는 중 · 실패 · 더 없음).
// 턴이 끝나면 다시 읽는다(useTurnRefresh). 쓰기 · 지우기는 지금 메일함과 같은 입력(sendMessage · deleteMessage)이다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from '../api';
import { submitCommandAndAwaitResult } from '../commandSubmit';
import { latestReceivedId, mailboxIdOf, toMailItems, type MailItem, type MailScope, type RecentMailEnvelope } from './mail-model';
import { isBlank, MAIL_TEXT_MAX, visibleLength } from './text';

export type MailLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'denied' }
    | { readonly state: 'error'; readonly message: string }
    /** 재야의 세력 탭처럼 서신함이 없다. */
    | { readonly state: 'none'; readonly message: string }
    | { readonly state: 'ready'; readonly items: readonly MailItem[] };

export type OlderState = 'idle' | 'loading' | 'error' | 'end';

export interface MailMe { readonly generalId: number; readonly nationId: number }

const isDenied = (e: unknown) => e instanceof Error && /^40[13]\b/.test(e.message);

export function useMailbox(me: MailMe | null, scope: MailScope, refreshKey = 0) {
    const [load, setLoad] = useState<MailLoad>({ state: 'loading' });
    const [older, setOlder] = useState<OlderState>('idle');
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    const view = useRef('');
    useTurnRefresh(reload);

    const generalId = me?.generalId ?? null;
    const nationId = me?.nationId ?? 0;
    useEffect(() => {
        if (generalId == null) return undefined;
        const key = `${scope}:${generalId}:${nationId}:${seq}`;
        view.current = key;
        const who = { generalId, nationId };
        if (mailboxIdOf(scope, who) == null) {
            setLoad({ state: 'none', message: '소속 세력이 없어 세력 서신함이 없습니다' });
            return undefined;
        }
        setLoad((l) => (l.state === 'ready' ? l : { state: 'loading' }));
        setOlder('idle');
        api.mailboxRecent<RecentMailEnvelope>()
            .then((env) => {
                if (view.current !== key) return;
                const items = toMailItems(env, scope, who);
                setLoad({ state: 'ready', items });
                const latest = scope === 'private' ? latestReceivedId(items) : null;
                if (latest != null) void api.commands.readLatestMessage({ type: 'private', msgID: latest }, generalId).catch(() => {});
            })
            .catch((e: unknown) => {
                if (view.current !== key) return;
                setLoad(isDenied(e) ? { state: 'denied' } : { state: 'error', message: '서신을 불러오지 못했습니다' });
            });
        return undefined;
    }, [generalId, nationId, scope, seq, refreshKey]);

    const loadOlder = useCallback(async () => {
        if (generalId == null || load.state !== 'ready' || older === 'loading' || older === 'end') return;
        const to = load.items.reduce((min, it) => Math.min(min, it.id), Number.MAX_SAFE_INTEGER);
        if (to === Number.MAX_SAFE_INTEGER) { setOlder('end'); return; }
        const key = view.current;
        setOlder('loading');
        try {
            const env = await api.mailboxOld<RecentMailEnvelope>(to, scope);
            if (view.current !== key) return;
            const more = toMailItems(env, scope, { generalId, nationId }).filter((it) => it.id < to);
            if (more.length === 0) { setOlder('end'); return; }
            setLoad({ state: 'ready', items: [...load.items, ...more] });
            setOlder('idle');
        } catch {
            if (view.current === key) setOlder('error');
        }
    }, [generalId, nationId, load, older, scope]);

    return { load, older, loadOlder, reload };
}

export type MailOutcome = { readonly kind: 'ok' | 'error' | 'info'; readonly text: string; readonly code?: string };

/** 보내기 전 검사 — 걸리면 보낼 수 없는 이유(쉬운 말), 없으면 null. */
export function composeProblem(html: string, scope: MailScope, recipientId: number | null): string | null {
    if (scope === 'private' && recipientId == null) return '받는 사람을 고르세요';
    if (isBlank(html)) return '보낼 글을 쓰세요';
    if (visibleLength(html) > MAIL_TEXT_MAX) return `${MAIL_TEXT_MAX}자까지 쓸 수 있습니다`;
    return null;
}

/** 서신 보내기 — 개인 서신은 서버가 돌려준 받는 사람이 고른 사람과 같은지 확인한다(지금 메일함과 같음). */
export async function sendMail(me: MailMe, scope: MailScope, recipient: { generalId: number; name: string } | null, html: string): Promise<MailOutcome> {
    const problem = composeProblem(html, scope, recipient?.generalId ?? null);
    if (problem) return { kind: 'error', text: problem };
    const mailbox = scope === 'private' ? recipient!.generalId : mailboxIdOf(scope, me);
    if (mailbox == null) return { kind: 'error', text: '소속 세력이 없어 세력 서신을 보낼 수 없습니다' };
    const out = await submitCommandAndAwaitResult(() => api.commands.sendMessage({ mailbox, text: html.trim() }, me.generalId));
    if (out.status === 'rejected') return { kind: 'error', text: out.reason ?? '서버가 이 서신을 받지 않았습니다', ...(out.code ? { code: out.code } : {}) };
    if (out.status === 'pending') return { kind: 'info', text: '처리가 늦어지고 있습니다 — 잠시 뒤 서신함을 확인해 주세요' };
    if (out.status === 'applied' && scope === 'private') {
        const r = out.result.result as { recipientId?: unknown; recipientName?: unknown };
        if (r.recipientId !== recipient!.generalId) return { kind: 'info', text: '보냈지만 받는 사람을 확인하지 못했습니다 — 서신함을 확인해 주세요' };
        return { kind: 'ok', text: `${recipient!.name}에게 보냈습니다` };
    }
    return { kind: 'ok', text: '보냈습니다' };
}

export async function deleteMail(me: MailMe, item: MailItem): Promise<MailOutcome> {
    const out = await submitCommandAndAwaitResult(() => api.commands.deleteMessage({ msgID: item.id }, me.generalId));
    if (out.status === 'rejected') return { kind: 'error', text: out.reason ?? '이 서신을 지울 수 없습니다', ...(out.code ? { code: out.code } : {}) };
    if (out.status === 'pending') return { kind: 'info', text: '지우기를 받았습니다 — 곧 목록에서 사라집니다' };
    return { kind: 'ok', text: '서신을 지웠습니다' };
}
