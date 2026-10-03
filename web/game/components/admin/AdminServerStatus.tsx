'use client';

// 서버 상태(P-A03 SS1–SS9, GS13–GS14) — 지금 상태 · 연월 · 마지막 턴은 GET /api/admin/game-settings, 바꾸기는
// POST /api/admin/server-status(202 는 접수일 뿐 — 반영은 다시 읽어 위 「지금 상태」로 확인한다).
// 턴 진행 · 따라잡기 · 게임 설정(턴 길이 · 시작 시각 · 상한)은 운영 콘솔이 맡는다(§9 Q-A1) — 고리만 둔다.

import { useCallback, useState } from 'react';
import { Button, Chip, ConfirmDialog, KV, Panel, SectionHeader, Seg, StatusView } from '@opensamguk/ui';
import { api, type AdminGameSettingsResponse } from '@/lib/api';
import { SERVER_STATUSES, type ServerStatus } from '@/lib/constants';
import { httpStatusOf } from '@/lib/records-reads';
import { useAdminRead } from './GameAdminScreen';
import styles from './game-admin.module.css';

const DASH = '—';

export const SERVER_STATUS_LABEL: Readonly<Record<ServerStatus, string>> = {
    OPEN: '열림',
    PRE_OPEN: '준비 중',
    CLOSED: '닫힘(점검)',
};
const STATUS_TONE: Readonly<Record<ServerStatus, 'moss' | 'info' | 'rust'>> = { OPEN: 'moss', PRE_OPEN: 'info', CLOSED: 'rust' };

const isServerStatus = (v: string | null | undefined): v is ServerStatus => SERVER_STATUSES.some((s) => s === v);

function dateLine(s: AdminGameSettingsResponse): string {
    const when = s.year == null || s.month == null ? null : `${s.year}년 ${s.month}월${s.turnPhaseText ? ` ${s.turnPhaseText}` : ''}`;
    const scenario = s.scenarioText ?? s.scenarioCode;
    return [when, scenario].filter(Boolean).join(' · ') || DASH;
}

type Notice = { readonly tone: 'ok' | 'err'; readonly text: string } | null;

export default function AdminServerStatus() {
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    const read = useCallback((signal: AbortSignal) => api.admin.gameSettings(signal), []);
    const load = useAdminRead<AdminGameSettingsResponse>(read, seq);
    const [choice, setChoice] = useState<ServerStatus>('OPEN');
    const [confirming, setConfirming] = useState(false);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<Notice>(null);

    if (load.state === 'loading') return <StatusView kind="loading" rows={3} />;
    if (load.state === 'error') {
        if (httpStatusOf(load.error) === 403) {
            return <StatusView kind="denied" title="관리자 권한이 필요합니다." howTo="운영자 계정으로 다시 로그인하면 볼 수 있습니다." />;
        }
        return <StatusView kind="error" title="서버 상태를 지금 읽을 수 없습니다" body="잠시 뒤 다시 해 보세요." onRetry={reload} />;
    }
    const s = load.data;
    const current = s.status ?? null;

    async function apply() {
        setBusy(true);
        setNotice(null);
        try {
            const out = await api.admin.serverStatus(choice);
            setNotice(out.result
                ? { tone: 'ok', text: `바꾸기를 접수했습니다(${SERVER_STATUS_LABEL[choice]}). 반영 여부는 위 지금 상태로 확인합니다.` }
                : { tone: 'err', text: out.reason ?? '바꿀 수 없습니다.' });
            reload();
        } catch (e) {
            setNotice({ tone: 'err', text: e instanceof Error && e.message ? e.message : '요청에 실패했습니다.' });
        } finally {
            setBusy(false);
            setConfirming(false);
        }
    }

    const same = choice === current;
    return (
        <div className={styles.stack}>
            <Panel className={styles.panel} aria-label="서버 상태">
                <SectionHeader title="서버 상태" sub="되돌릴 수 있는 바꾸기" />
                <KV
                    className={styles.facts}
                    items={[
                        {
                            k: '지금 상태',
                            v: isServerStatus(current)
                                ? <Chip tone={STATUS_TONE[current]}>{SERVER_STATUS_LABEL[current]}</Chip>
                                : current ?? DASH,
                        },
                        { k: '게임 날짜', v: dateLine(s) },
                        { k: '마지막 턴', v: s.turntime ?? DASH },
                    ]}
                />
                <p className={styles.muted}>바꾸기를 접수해도 바로 반영되지는 않습니다. 반영되면 위 「지금 상태」가 바뀝니다.</p>
            </Panel>
            <Panel className={styles.panel} aria-label="상태 바꾸기">
                <SectionHeader title="상태 바꾸기" />
                <div className={styles.statusRow}>
                    <Seg<ServerStatus> label="바꿀 상태" options={SERVER_STATUSES.map((v) => ({ value: v, label: SERVER_STATUS_LABEL[v] }))} value={choice} onChange={setChoice} />
                    {busy
                        ? <Button variant="primary" disabled reason="처리 중입니다">상태 바꾸기</Button>
                        : same
                            ? <Button variant="primary" disabled reason="지금 상태와 같습니다">상태 바꾸기</Button>
                            : <Button variant="primary" onClick={() => setConfirming(true)}>상태 바꾸기</Button>}
                </div>
                {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
            </Panel>
            <div className={styles.consoleRow}>
                <p className={styles.consoleNote}>턴 진행 · 따라잡기 · 게임 설정(한 순 길이 · 시작 시각 · 사람 장수 상한)은 운영 콘솔에서 봅니다.</p>
                <a className={`os-button os-button--ghost ${styles.consoleLink}`} href="/admin">운영 콘솔</a>
            </div>
            <ConfirmDialog
                open={confirming}
                title="서버 상태 바꾸기"
                message={`서버 상태를 「${SERVER_STATUS_LABEL[choice]}」(으)로 바꿉니다. 접수 뒤 반영되면 지금 상태가 바뀝니다.`}
                confirmLabel="바꾸기"
                danger={choice === 'CLOSED'}
                busy={busy}
                onConfirm={() => void apply()}
                onCancel={() => setConfirming(false)}
            />
        </div>
    );
}
