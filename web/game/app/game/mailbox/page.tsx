'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { countHtmlCodePoints, RichTextEditor } from '../../../components/RichTextEditor';
import { SafeHtml } from '../../../components/SafeHtml';
import { api, isIntakeQueued, isIntakeDenied, pollCommandResult } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import { INFINITE_DATE, TOAST_DURATION_MS } from '../../../lib/constants';
import { mailboxIdForScope, isMessageDeletable, MAILBOX_PUBLIC, MAILBOX_NATIONAL_BASE, type MailboxScope } from '../../../lib/mailbox';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { FrontInfoResponse } from '../../../lib/types';
import type { MailboxMessage } from '../../../types/game';

const TYPE_LABEL: Record<string, string> = {
    private: '개인',
    public: '전체',
    national: '국가',
    diplomacy: '외교',
};

const TYPE_VARIANT: Record<string, 'gold' | 'jade' | 'muted' | 'crimson'> = {
    private: 'muted',
    public: 'jade',
    national: 'gold',
    diplomacy: 'crimson',
};

// 인테이크 202 후 엔진 실행 결과(GET /api/command/result/{requestId}) 폴링 파라미터 —
// select-pool/page.tsx 관례와 동일(20회 × 300ms, PENDING이면 재시도).
const MESSAGE_RESULT_POLL_ATTEMPTS = 20;
const MESSAGE_RESULT_POLL_INTERVAL_MS = 300;
const MESSAGE_TEXT_MAX_CODE_POINTS = 500;

type MessageArrayItem = {
    id: number | null;
    msgType: MailboxScope;
    src: { id: number; name: string; nation_id: number; nation: string; color: string; icon?: string | null } | null;
    dest: { id: number; name: string; nation_id: number; nation: string; color: string; icon?: string | null } | null;
    text: string;
    option: Record<string, unknown> | null;
    time: string;
};

type MailboxEnvelope = {
    private: MessageArrayItem[];
    public: MessageArrayItem[];
    national: MessageArrayItem[];
    diplomacy: MessageArrayItem[];
    sequence: number;
};

function fromArrayItem(item: MessageArrayItem, mailbox: number): MailboxMessage {
    const target = (value: MessageArrayItem['src']) => value == null ? null : {
        id: value.id, name: value.name, nationId: value.nation_id, nation: value.nation,
        color: value.color, icon: value.icon ?? null,
    };
    return {
        id: item.id,
        mailbox,
        type: item.msgType,
        src: item.src?.id ?? 0,
        dest: item.dest?.id ?? 0,
        time: item.time,
        validUntil: INFINITE_DATE,
        message: '',
        text: item.text,
        srcTarget: target(item.src),
        destTarget: target(item.dest),
        option: item.option,
    };
}

export default function MailboxPage() {
    const [messages, setMessages] = useState<MailboxMessage[]>([]);
    const [scope, setScope] = useState<MailboxScope>('private');
    const [identity, setIdentity] = useState<{ generalId: number | null; nationId: number }>({ generalId: null, nationId: 0 });
    const mailboxViewKey = `${scope}:${identity.generalId ?? 'none'}:${identity.nationId}`;
    const mailboxViewKeyRef = useRef(mailboxViewKey);
    mailboxViewKeyRef.current = mailboxViewKey;
    const messageRequestRef = useRef(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // 수락/거절 요청 진행 중인 메시지 id — 중복 클릭(이중 수락) 방지용
    const [pendingId, setPendingId] = useState<number | null>(null);
    // 서신 발송 폼 상태
    const [sendText, setSendText] = useState<string>('');
    // 개인 수신자는 메일함 읽기 scope와 분리한 명시적 상태다.
    const [recipientSearch, setRecipientSearch] = useState('');
    const [privateRecipientId, setPrivateRecipientId] = useState<number | null>(null);
    const [diplomacyMailbox, setDiplomacyMailbox] = useState<number | null>(null);
    const [sending, setSending] = useState(false);
    const [contacts, setContacts] = useState<Array<{ mailbox: number; name: string; general: Array<[number, string, number]> }>>([]);

    const fetchMessages = useCallback(async () => {
        const requestedViewKey = `${scope}:${identity.generalId ?? 'none'}:${identity.nationId}`;
        // 비동기 발송 결과가 뒤늦게 이전 탭의 callback을 호출해도 현재 목록을 덮지 않는다.
        if (mailboxViewKeyRef.current !== requestedViewKey) return;
        const requestId = ++messageRequestRef.current;
        const isCurrentView = () =>
            mailboxViewKeyRef.current === requestedViewKey && messageRequestRef.current === requestId;
        setLoading(true);
        try {
            const mailboxId = mailboxIdForScope(scope, identity);
            if (mailboxId == null) {
                if (isCurrentView()) {
                    setMessages([]);
                    setError(scope === 'national' ? '소속 국가가 없습니다.' : '장수 정보가 없습니다.');
                }
                return;
            }
            const data = await api.mailboxRecent<MailboxEnvelope>();
            if (!isCurrentView()) return;
            const section = data[scope];
            setMessages(section.map((item) => fromArrayItem(item, mailboxId)));
            if ((scope === 'private' || scope === 'diplomacy') && identity.generalId != null) {
                const latest = section.reduce((max, item) => Math.max(max, item.id ?? 0), 0);
                if (latest > 0) void api.commands.readLatestMessage({ type: scope, msgID: latest }, identity.generalId);
            }
            setError('');
        } catch {
            if (isCurrentView()) setError('메일함을 불러올 수 없습니다.');
        } finally {
            if (isCurrentView()) setLoading(false);
        }
    }, [identity, scope]);

    const loadOlder = async () => {
        const mailboxId = mailboxIdForScope(scope, identity);
        const to = messages.reduce((min, message) => Math.min(min, message.id ?? min), Number.MAX_SAFE_INTEGER);
        if (mailboxId == null || to === Number.MAX_SAFE_INTEGER) return;
        const data = await api.mailboxOld<MailboxEnvelope>(to, scope);
        setMessages((current) => [...current, ...data[scope].map((item) => fromArrayItem(item, mailboxId))]);
    };

    useEffect(() => {
        let on = true;
        api.frontInfo()
            .then((info: FrontInfoResponse) => {
                if (!on) return;
                setIdentity({
                    generalId: info.general?.generalId ?? null,
                    nationId: info.general?.nationId ?? 0,
                });
            })
            .catch(() => {
                if (on) setIdentity({ generalId: null, nationId: 0 });
            });
        return () => {
            on = false;
        };
    }, []);

    useEffect(() => {
        api.contacts<{ nation: Array<{ mailbox: number; name: string; general: Array<[number, string, number]> }> }>()
            .then((result) => setContacts(result.nation))
            .catch(() => setContacts([]));
    }, []);

    useEffect(() => {
        fetchMessages();
    }, [fetchMessages]);

    // 기존에도 자체 SSE로 턴 완료 시 fetchMessages를 호출해왔다(개인/외교함 최신 열람 처리 포함) —
    // 여기서는 Shell 공용 SSE 구독으로 갈아탈 뿐, 읽음 처리 동작은 그대로 유지한다(OPENSAM-196).
    useTurnRefresh(() => {
        fetchMessages();
    });

    async function handleSend() {
        if (sending) return;
        const text = sendText.trim();
        if (!text) return;
        if (countHtmlCodePoints(text) > MESSAGE_TEXT_MAX_CODE_POINTS) {
            setToast(`서신 내용은 ${MESSAGE_TEXT_MAX_CODE_POINTS}자 이내로 입력하세요.`);
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        const generalId = identity.generalId;
        if (generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        const sendScope = scope;
        const mailbox = sendMailbox;
        const intendedRecipient = selectedPrivateRecipient;
        if (mailbox == null) {
            setToast(sendScope === 'private' ? '수신 장수를 선택하세요.' : '발송 대상을 선택하세요.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        if (sendScope === 'private' && intendedRecipient == null) {
            setToast('수신 장수를 다시 선택하세요.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        if (sendScope === 'private' && mailbox === generalId) {
            setToast('자기 자신에게는 개인 서신을 보낼 수 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        setSending(true);
        try {
            const out = await submitCommandAndAwaitResult(() =>
                api.commands.sendMessage({ mailbox, text }, generalId));
            if (out.status === 'applied') {
                const recipientId = out.result.result.recipientId;
                const recipientName = out.result.result.recipientName;
                const msgType = out.result.result.msgType;
                if (sendScope === 'private') {
                    if (
                        intendedRecipient == null
                        || msgType !== 'private'
                        || recipientId !== intendedRecipient.id
                        || recipientName !== intendedRecipient.name
                    ) {
                        setToast('서신은 처리되었지만 수신자 확인에 실패했습니다.');
                    } else {
                        setToast(`${recipientName} (${recipientId})에게 서신을 발송했습니다.`);
                        setSendText('');
                        fetchMessages();
                    }
                } else {
                    setToast('서신을 발송했습니다.');
                    setSendText('');
                    fetchMessages();
                }
            } else if (out.status === 'rejected') {
                setToast(out.reason ?? '서신을 보낼 수 없습니다.');
            } else {
                setToast(out.reason);
            }
        } catch {
            setToast('서신 발송에 실패했습니다.');
        } finally {
            setSending(false);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    // 수락/거절은 game-api의 /api/messages/{id}/accept|decline로 직접 호출한다.
    // 수락·거절은 typed immediate daemon command로 접수된다. 데몬이 검증·실행·flush한 뒤
    // result channel에 확정 결과를 공개하므로, 클라이언트는 202 접수만으로 성공 처리하지 않는다.
    async function handleAgree(msg: MailboxMessage) {
        // 요청 진행 중이면 재진입 금지 — 빠른 더블클릭에 의한 이중 수락 차단
        if (pendingId !== null) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        if (msg.id == null) return;
        setPendingId(msg.id);
        try {
            const accepted = await api.messageAccept(msg.id, identity.generalId);
            const result = await pollCommandResult(accepted.requestId);
            if (result == null) {
                setToast('수락 요청을 접수했습니다.');
            } else if (!result.ok) {
                setToast(result.reason ?? '수락할 수 없습니다.');
            } else {
                setToast('수락했습니다.');
                fetchMessages();
            }
        } catch {
            setToast('수락 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    async function handleDecline(msg: MailboxMessage) {
        // 요청 진행 중이면 재진입 금지 — 빠른 더블클릭에 의한 이중 거절 차단
        if (pendingId !== null) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        if (msg.id == null) return;
        setPendingId(msg.id);
        try {
            const accepted = await api.messageDecline(msg.id, identity.generalId);
            const result = await pollCommandResult(accepted.requestId);
            if (result == null) {
                setToast('거절 요청을 접수했습니다.');
            } else if (!result.ok) {
                setToast(result.reason ?? '거절할 수 없습니다.');
            } else {
                setToast('거절했습니다.');
                fetchMessages();
            }
        } catch {
            setToast('거절 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    // 서신 삭제 — legacy MessagePlate.vue tryDelete(248-265행): confirm("삭제하시겠습니까?") →
    // SammoAPI.Message.DeleteMessage({ msgID }).
    // 계약 주의: deleteMessage는 인테이크 명령(CommandWireMapper.intakeCodes)이라 game-api
    // CommandController가 precheck Blocked/Unknown이어도 isForecastReservable→202 reserveAccepted로
    // 재라우팅한다(이 엔드포인트에서 200 BLOCKED는 나오지 않는다). 엔진 MessageHandler.handleDelete의
    // 실제 deny(본인 아님/5분 초과/시스템 외교 등 PHP 동결 회귀 문자열)는 GET /api/command/result/{requestId}
    // (RESOLVED + 톱레벨 ok/reason) 채널로만 온다. 따라서 202 수신 후 select-pool과 동일하게
    // api.commandResult를 폴링해 성공/거부/미해결을 구분한다(성공 위장 금지).
    async function handleDelete(msg: MailboxMessage) {
        // 요청 진행 중이면 재진입 금지 — 빠른 더블클릭에 의한 이중 삭제 차단
        if (pendingId !== null) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        if (msg.id == null) return;
        // confirm 취소 시 네트워크 요청 없이 즉시 반환 — 문구는 레거시와 동결 회귀.
        if (!window.confirm('삭제하시겠습니까?')) return;
        // pendingId는 폴링 완료까지 유지(이중 제출·재조회 경합 차단).
        setPendingId(msg.id);
        try {
            const out = await api.commands.deleteMessage({ msgID: msg.id }, identity.generalId);
            if (isIntakeDenied(out)) {
                // 방어 분기: 인테이크 재라우팅(isForecastReservable)상 200 BLOCKED는 정상 도달 불가하나,
                // 계약이 바뀔 경우를 대비해 서버 reason을 그대로 노출한다(날조 금지).
                setToast(out.reason ?? '서신을 삭제할 수 없습니다.');
            } else if (isIntakeQueued(out) && out.requestId != null) {
                const requestId = out.requestId;
                let resolved = false;
                for (let attempt = 0; attempt < MESSAGE_RESULT_POLL_ATTEMPTS; attempt += 1) {
                    await new Promise(resolve => setTimeout(resolve, MESSAGE_RESULT_POLL_INTERVAL_MS));
                    let result;
                    try {
                        result = await api.commandResult(requestId);
                    } catch {
                        // 일시 조회 실패 — 접수(202)는 이미 확정이므로 실패로 오표시하지 않고
                        // 남은 시도 동안 재시도한다. 전부 실패하면 아래 미해결(접수) 경로로 수렴.
                        continue;
                    }
                    if (result.status === 'PENDING') continue;
                    resolved = true;
                    if (result.ok) {
                        setToast('서신을 삭제했습니다.');
                        fetchMessages();
                    } else {
                        // 엔진 deny 사유(PHP 동결 회귀) 그대로 노출 — 목록은 유지(삭제되지 않음).
                        setToast(result.reason ?? '서신을 삭제할 수 없습니다.');
                    }
                    break;
                }
                if (!resolved) {
                    // 20회 내 미해결 — 접수만 확정(엔진이 다음 틱에 처리). 최신 상태 재조회.
                    setToast('서신 삭제 요청을 접수했습니다.');
                    fetchMessages();
                }
            } else {
                // requestId 없는 202(예상 밖) — 접수만 표시하고 재조회.
                setToast('서신 삭제 요청을 접수했습니다.');
                fetchMessages();
            }
        } catch {
            setToast('서신 삭제에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    const messageName = (msg: MailboxMessage) => msg.srcTarget?.name || String(msg.src);
    const sendTextTooLong = countHtmlCodePoints(sendText.trim()) > MESSAGE_TEXT_MAX_CODE_POINTS;
    const privateRecipients = contacts.flatMap(group => group.general.map(([id, name]) => ({
        id,
        name,
        nation: group.name,
    }))).filter(recipient => recipient.id !== identity.generalId);
    const normalizedSearch = recipientSearch.trim().toLocaleLowerCase('ko-KR');
    const filteredPrivateRecipients = privateRecipients.filter(recipient =>
        normalizedSearch.length === 0
        || recipient.name.toLocaleLowerCase('ko-KR').includes(normalizedSearch)
        || String(recipient.id).includes(normalizedSearch)
        || recipient.nation.toLocaleLowerCase('ko-KR').includes(normalizedSearch));
    const selectedPrivateRecipient = privateRecipients.find(recipient => recipient.id === privateRecipientId) ?? null;
    const canSendDiplomacy = contacts.some(group => group.general.some(([id, , flags]) =>
        id === identity.generalId && (flags & 4) === 4));
    const sendMailbox = scope === 'private'
        ? privateRecipientId
        : scope === 'public'
            ? MAILBOX_PUBLIC
            : scope === 'national'
                ? (identity.nationId > 0 ? MAILBOX_NATIONAL_BASE + identity.nationId : null)
                : diplomacyMailbox;
    const hasValidSendTarget = sendMailbox != null
        && (scope !== 'private' || (selectedPrivateRecipient != null && sendMailbox !== identity.generalId))
        && (scope !== 'diplomacy' || canSendDiplomacy);
    const messageTime = (value: string) => {
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return value;
        return date.toLocaleString('ko-KR', {
            year: 'numeric',
            month: 'numeric',
            day: 'numeric',
            hour: '2-digit',
            minute: '2-digit',
        });
    };
    const showValidUntil = (value: string) => value && !value.startsWith(INFINITE_DATE);

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>메일함</h1>

            <div style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}>
                <div style={{ display: 'flex', gap: 'var(--space-xs)', flexWrap: 'wrap' }}>
                    {([
                        ['private', '개인'],
                        ['national', '국가'],
                        ['public', '전체'],
                        ['diplomacy', '외교'],
                    ] as const).map(([value, label]) => (
                        <button
                            key={value}
                            type="button"
                            onClick={() => setScope(value)}
                            style={{
                                background: scope === value ? 'var(--gold)' : 'transparent',
                                color: scope === value ? 'var(--bg-base)' : 'var(--text-secondary)',
                            }}
                        >
                            {label}
                        </button>
                    ))}
                </div>
                <button onClick={fetchMessages}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {toast && (
                <div className="toast" style={{ position: 'fixed', top: 'var(--space-md)', right: 'var(--space-md)', zIndex: 200 }}>
                    {toast}
                </div>
            )}

            {/* 서신 발송 폼 — legacy MessagePanel.vue sendMessage(): { mailbox, text } */}
            <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                <p style={{ fontSize: 'var(--text-sm)', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>서신 발송</p>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)', flexWrap: 'wrap', alignItems: 'center' }}>
                    <label style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>대상</label>
                    {scope === 'private' && (
                        <>
                            <input
                                aria-label="수신 장수 검색"
                                type="search"
                                value={recipientSearch}
                                onChange={event => setRecipientSearch(event.target.value)}
                                placeholder="장수 이름·ID·국가 검색"
                                disabled={sending || identity.generalId == null}
                            />
                            <select
                                aria-label="개인 수신 장수"
                                value={privateRecipientId ?? ''}
                                onChange={event => {
                                    setPrivateRecipientId(event.target.value ? Number(event.target.value) : null);
                                    if (event.target.value) setRecipientSearch('');
                                }}
                                disabled={sending || identity.generalId == null}
                                style={{ fontSize: 'var(--text-sm)', padding: '2px var(--space-xs)', background: 'var(--bg-surface)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
                            >
                                <option value="">수신 장수를 선택하세요</option>
                                {filteredPrivateRecipients.map(recipient => (
                                    <option key={recipient.id} value={recipient.id}>{recipient.name}</option>
                                ))}
                            </select>
                        </>
                    )}
                    {scope === 'national' && <span>자국 서신함</span>}
                    {scope === 'public' && <span>전체 서신함</span>}
                    {scope === 'diplomacy' && !canSendDiplomacy && <span>외교 권한이 없습니다.</span>}
                    {scope === 'diplomacy' && canSendDiplomacy && (
                        <select
                            aria-label="외교 수신 국가"
                            value={diplomacyMailbox ?? ''}
                            onChange={event => setDiplomacyMailbox(event.target.value ? Number(event.target.value) : null)}
                            disabled={sending || identity.generalId == null}
                        >
                            <option value="">수신 국가를 선택하세요</option>
                            {contacts.filter(group => group.mailbox !== MAILBOX_NATIONAL_BASE + identity.nationId && group.mailbox > MAILBOX_NATIONAL_BASE).map(group => (
                                <option key={group.mailbox} value={group.mailbox}>{group.name}</option>
                            ))}
                        </select>
                    )}
                </div>
                {scope === 'private' && selectedPrivateRecipient && (
                    <p style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginBottom: 'var(--space-xs)' }}>
                        수신자: {selectedPrivateRecipient.name} ({selectedPrivateRecipient.id})
                    </p>
                )}
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'flex-end' }}>
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <RichTextEditor
                            ariaLabel="서신 내용"
                            disabled={sending || identity.generalId == null}
                            maxTextLength={MESSAGE_TEXT_MAX_CODE_POINTS}
                            onChange={setSendText}
                            value={sendText}
                        />
                    </div>
                    <button
                        type="button"
                        onClick={handleSend}
                        disabled={sending || !sendText.trim() || sendTextTooLong || identity.generalId == null || !hasValidSendTarget}
                        style={{ whiteSpace: 'nowrap' }}
                    >
                        {sending ? '발송 중...' : '발송'}
                    </button>
                </div>
            </GameCard>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-sm)' }}>
                {messages.length === 0 && !loading && (
                    <p style={{ color: 'var(--text-muted)' }}>메시지가 없습니다.</p>
                )}
                {messages.map(msg => {
                    const isDiplomacy = msg.type === 'diplomacy';
                    const hasAction = !!msg.option?.action;
                    const variant = TYPE_VARIANT[msg.type] ?? 'muted';
                    // 삭제 버튼은 발신자 본인 + 5분 이내에만 노출(legacy MessagePlate.vue testDeletable).
                    const deletable = isMessageDeletable(msg, identity.generalId);
                    return (
                        <GameCard key={msg.id ?? `${msg.mailbox}-${msg.time}`}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 'var(--space-sm)', marginBottom: 'var(--space-xs)', flexWrap: 'wrap' }}>
                                <StatusBadge variant={variant}>{TYPE_LABEL[msg.type] ?? msg.type}</StatusBadge>
                                <span style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>{messageName(msg)}</span>
                                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>{messageTime(msg.time)}</span>
                                {showValidUntil(msg.validUntil) && (
                                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>~{messageTime(msg.validUntil)}</span>
                                )}
                                {/* 삭제 — 발신자 본인이 보낸 5분 이내 메시지만. 진행 중엔 비활성(이중 제출 방지). */}
                                {deletable && (
                                    <button
                                        type="button"
                                        onClick={() => handleDelete(msg)}
                                        disabled={pendingId !== null}
                                        style={{ marginLeft: 'auto', fontSize: 'var(--text-xs)' }}
                                    >
                                        삭제
                                    </button>
                                )}
                            </div>
                            <div style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)', marginBottom: 'var(--space-sm)', whiteSpace: 'pre-wrap' }}>
                                <SafeHtml html={msg.text ?? ''} />
                            </div>
                            {isDiplomacy && hasAction && (
                                <div style={{ display: 'flex', gap: 'var(--space-sm)' }}>
                                    {/* 요청 진행 중에는 수락/거절 모두 비활성화 — 이중 제출 방지 */}
                                    <button onClick={() => handleAgree(msg)} disabled={pendingId !== null || identity.generalId == null}>수락</button>
                                    <button onClick={() => handleDecline(msg)} disabled={pendingId !== null || identity.generalId == null}>거절</button>
                                </div>
                            )}
                        </GameCard>
                    );
                })}
                {messages.length > 0 ? <button type="button" onClick={() => void loadOlder()}>이전 메시지</button> : null}
            </div>
        </Shell>
    );
}
