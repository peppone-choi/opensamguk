'use client';

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api, isIntakeQueued, isIntakeDenied } from '../../../lib/api';
import { INFINITE_DATE, TOAST_DURATION_MS } from '../../../lib/constants';
import { mailboxIdForScope, isMessageDeletable, MAILBOX_PUBLIC, MAILBOX_NATIONAL_BASE, type MailboxScope } from '../../../lib/mailbox';
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

export default function MailboxPage() {
    const [messages, setMessages] = useState<MailboxMessage[]>([]);
    const [scope, setScope] = useState<MailboxScope>('private');
    const [identity, setIdentity] = useState<{ generalId: number | null; nationId: number }>({ generalId: null, nationId: 0 });
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    const [toast, setToast] = useState<string>('');
    // 수락/거절 요청 진행 중인 메시지 id — 중복 클릭(이중 수락) 방지용
    const [pendingId, setPendingId] = useState<number | null>(null);
    // 서신 발송 폼 상태
    const [sendText, setSendText] = useState<string>('');
    // 발송 대상 mailbox id: 9999=전체, 9000+nationId=국가, generalId=개인 (현재 scope 연동)
    const [sendMailbox, setSendMailbox] = useState<number>(MAILBOX_PUBLIC);
    const [sending, setSending] = useState(false);

    const fetchMessages = useCallback(async () => {
        setLoading(true);
        try {
            const mailboxId = mailboxIdForScope(scope, identity);
            if (mailboxId == null) {
                setMessages([]);
                setError(scope === 'national' ? '소속 국가가 없습니다.' : '장수 정보가 없습니다.');
                return;
            }
            const data = await api.mailbox<MailboxMessage[]>(mailboxId);
            setMessages(data);
            setError('');
        } catch {
            setError('메일함을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, [identity, scope]);

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
        fetchMessages();
    }, [fetchMessages]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchMessages());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchMessages]);

    // 서신 발송 — legacy SendMessage.php: POST /api/command/sendMessage?generalId=&turnIdx=0
    // payload: { mailbox, text }. mailbox 라우팅: 9999=전체, 9000+nationId=국가, generalId=개인.
    // isIntakeQueued/isIntakeDenied 가드 적용 — 무조건 성공 토스트 금지(P0-04/06).
    async function handleSend() {
        if (sending) return;
        const text = sendText.trim();
        if (!text) return;
        if (identity.generalId == null) {
            setToast('장수 정보가 없습니다.');
            setTimeout(() => setToast(''), TOAST_DURATION_MS);
            return;
        }
        setSending(true);
        try {
            const out = await api.commands.sendMessage({ mailbox: sendMailbox, text }, identity.generalId);
            if (isIntakeQueued(out)) {
                setToast('서신을 발송했습니다.');
                setSendText('');
                fetchMessages();
            } else if (isIntakeDenied(out)) {
                setToast(out.reason ?? '서신을 보낼 수 없습니다.');
            } else {
                setToast('발송 처리 중 오류가 발생했습니다.');
            }
        } catch {
            setToast('서신 발송에 실패했습니다.');
        } finally {
            setSending(false);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
    }

    // 수락/거절은 game-api의 /api/messages/{id}/accept|decline로 직접 호출한다.
    // NEW CONTRACT: 수락 시 서버가 수락 명령(예: che_불가침수락)을 턴 데몬에 직접 예약(reserve)하므로
    // 클라이언트는 commandKey를 읽어 /api/command를 다시 호출하지 않는다(예전 디스패치 설계 폐기).
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
            await api.messageAccept(msg.id, identity.generalId);
            // 수락은 턴 명령으로 예약된다 — 즉시 적용을 함의하지 않는 표현.
            setToast('수락했습니다.');
        } catch {
            setToast('수락 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
        fetchMessages();
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
            await api.messageDecline(msg.id, identity.generalId);
            setToast('거절했습니다.');
        } catch {
            setToast('거절 요청에 실패했습니다.');
        } finally {
            setPendingId(null);
        }
        setTimeout(() => setToast(''), TOAST_DURATION_MS);
        fetchMessages();
    }

    // 서신 삭제 — legacy MessagePlate.vue tryDelete(248-265행): confirm("삭제하시겠습니까?") →
    // SammoAPI.Message.DeleteMessage({ msgID }).
    // 계약 주의: deleteMessage는 인테이크 명령(CommandWireMapper.intakeCodes)이라 game-api
    // CommandController가 precheck Blocked/Unknown이어도 isForecastReservable→202 reserveAccepted로
    // 재라우팅한다(이 엔드포인트에서 200 BLOCKED는 나오지 않는다). 엔진 MessageHandler.handleDelete의
    // 실제 deny(본인 아님/5분 초과/시스템 외교 등 PHP byte-parity 문자열)는 GET /api/command/result/{requestId}
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
        // confirm 취소 시 네트워크 요청 없이 즉시 반환 — 문구는 레거시와 byte-parity.
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
                    const result = await api.commandResult(requestId);
                    if (result.status === 'PENDING') continue;
                    resolved = true;
                    if (result.ok) {
                        setToast('서신을 삭제했습니다.');
                        fetchMessages();
                    } else {
                        // 엔진 deny 사유(PHP byte-parity) 그대로 노출 — 목록은 유지(삭제되지 않음).
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

    // scope 전환 시 발송 대상 mailbox도 연동 갱신
    useEffect(() => {
        const id = mailboxIdForScope(scope, identity);
        setSendMailbox(id ?? MAILBOX_PUBLIC);
    }, [scope, identity]);

    const messageName = (msg: MailboxMessage) => msg.srcTarget?.name || String(msg.src);
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
                    <select
                        value={sendMailbox}
                        onChange={e => setSendMailbox(Number(e.target.value))}
                        disabled={sending || identity.generalId == null}
                        style={{ fontSize: 'var(--text-sm)', padding: '2px var(--space-xs)', background: 'var(--bg-surface)', color: 'var(--text-primary)', border: '1px solid var(--border)' }}
                    >
                        {/* 개인 수신함(현재 장수 본인)은 항상 표시 */}
                        {identity.generalId != null && identity.generalId > 0 && (
                            <option value={identity.generalId}>개인</option>
                        )}
                        {/* 국가 수신함: nationId > 0 인 경우만 */}
                        {identity.nationId > 0 && (
                            <option value={MAILBOX_NATIONAL_BASE + identity.nationId}>국가</option>
                        )}
                        <option value={MAILBOX_PUBLIC}>전체</option>
                    </select>
                </div>
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'flex-end' }}>
                    <textarea
                        value={sendText}
                        onChange={e => setSendText(e.target.value)}
                        placeholder="메시지를 입력하세요..."
                        rows={3}
                        maxLength={500}
                        disabled={sending || identity.generalId == null}
                        style={{ flex: 1, fontSize: 'var(--text-sm)', padding: 'var(--space-xs)', background: 'var(--bg-surface)', color: 'var(--text-primary)', border: '1px solid var(--border)', resize: 'vertical' }}
                    />
                    <button
                        type="button"
                        onClick={handleSend}
                        disabled={sending || !sendText.trim() || identity.generalId == null}
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
                            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-primary)', marginBottom: 'var(--space-sm)', whiteSpace: 'pre-wrap' }}>{msg.text ?? ''}</p>
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
            </div>
        </Shell>
    );
}
