'use client';

// 서버 상태(OPEN/PRE_OPEN/CLOSED) — game-api POST /api/admin/server-status. 이전까지 FE 미배선이던 엔드포인트(Phase 3 대조표).
// 202 는 접수일 뿐이므로 「접수됨」으로만 표시하고, 실제 값은 게임 설정(status)을 다시 읽어 보여 준다.
import { useCallback, useEffect, useState } from 'react';
import { Button, Chip, SectionHeader } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { SERVER_STATUSES, type ServerStatus } from '@/lib/constants';

export default function ServerStatusPanel() {
    const [current, setCurrent] = useState<string | null | undefined>(undefined);
    const [choice, setChoice] = useState<ServerStatus>('OPEN');
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState<string | null>(null);
    const [confirming, setConfirming] = useState(false);

    const load = useCallback(async () => {
        try {
            const settings = await api.admin.gameSettings();
            setCurrent(settings.status ?? null);
        } catch (e) {
            setCurrent(null);
            setMessage(e instanceof Error ? e.message : '서버 상태를 불러오지 못했습니다.');
        }
    }, []);
    useEffect(() => {
        void load();
    }, [load]);

    async function apply() {
        setBusy(true);
        setMessage(null);
        try {
            const out = await api.admin.serverStatus(choice);
            setMessage(out.result ? `상태 변경을 접수했습니다(${choice}). 반영 여부는 아래 현재 값으로 확인합니다.` : (out.reason ?? '변경할 수 없습니다.'));
            await load();
        } catch (e) {
            setMessage(e instanceof Error ? e.message : '요청에 실패했습니다.');
        } finally {
            setBusy(false);
            setConfirming(false);
        }
    }

    const busyProps = busy ? ({ disabled: true, reason: '처리 중입니다' } as const) : ({} as const);
    return (
        <section className="os-panel os-panel--static" aria-label="서버 상태">
            <SectionHeader title="서버 상태" sub="위험 등급: 가역 변경 · 접수 ≠ 반영" />
            <div className="server-status__body">
                <p className="server-status__current">
                    현재 상태: {current === undefined ? '불러오는 중…' : current === null ? <span className="text-muted">조회 실패</span> : <Chip tone={current === 'OPEN' ? 'moss' : current === 'CLOSED' ? 'rust' : 'info'}>{current}</Chip>}
                </p>
                <label className="server-status__field">
                    바꿀 상태
                    <select aria-label="바꿀 상태" value={choice} onChange={(e) => setChoice(e.target.value as ServerStatus)} disabled={busy} title={busy ? '처리 중' : undefined}>
                        {SERVER_STATUSES.map((s) => (
                            <option key={s} value={s}>{s}</option>
                        ))}
                    </select>
                </label>
                {!confirming ? (
                    <Button variant="primary" onClick={() => setConfirming(true)} {...busyProps}>상태 변경</Button>
                ) : (
                    <div className="server-status__confirm" role="group" aria-label="상태 변경 확인">
                        <span>서버 상태를 <b>{choice}</b> 로 바꿉니다. 계속할까요?</span>
                        <Button variant="danger" onClick={() => void apply()} {...busyProps}>변경 실행</Button>
                        <Button variant="ghost" onClick={() => setConfirming(false)} {...busyProps}>취소</Button>
                    </div>
                )}
                {message && <p className="server-status__message" role="status">{message}</p>}
            </div>
        </section>
    );
}
