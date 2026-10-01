'use client';

// 군단(P-C01) 오른쪽 칸 — 탭 「군단」 · 「세력 작전」, 목록(내 군단 · 보이는 남의 군단) → 고른 군단 카드. 보드 V31K6Corps · MCorps.
// 지도(군단 경로 레이어)는 K2 부품이 왼쪽에 그린다 — 여기서는 고른 군단을 onSelect로 알린다.
// 입력: 출병 · 부대 모으기 = 명령 흐름을 연다(onOpenFlow), 편성 해제(court.releaseCorps) = 확인 뒤 그 자리에서 보냄,
// 방침 바꾸기 · 군단장 바꾸기 = 배치 · 방침 화면(P-T01, K4 — onOpenPolicy). 세력 작전 · 원군 요청은 원장 행이 없어 영역 전체 서버 대기.
// 군단 카드의 「전투」 줄(실시간 전투 잠김)은 서버가 주지 않아 서버 대기(계약판 K6-11)로 그린다.
import { useState } from 'react';
import { ConfirmDialog, InputAction, StatusView } from '@opensamguk/ui';
import { useReasonHelp } from '@/hooks/useHelp';
import { availabilityOf } from '@/lib/input-availability';
import { releaseChoiceFor, type CorpsRow, type DeployOrderView } from '@/lib/corps/corps-model';
import type { CourtActionOptions } from '@/lib/types';
import styles from './Corps.module.css';

export type CorpsLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void; readonly code?: string }
    | { readonly state: 'ready'; readonly rows: readonly CorpsRow[] };

export interface CorpsPanelProps {
    readonly load: CorpsLoad;
    readonly order: DeployOrderView | null;
    /** 편성 해제 조정 옵션(권한 · 후보). null = 아직 못 읽음. */
    readonly releaseOptions: CourtActionOptions | null;
    readonly onOpenFlow: (inputId: 'action.deploy' | 'action.muster') => void;
    readonly onOpenPolicy?: () => void;
    readonly onRelease: (row: CorpsRow, args: Record<string, string | number>) => Promise<{ ok: boolean; code?: string; reason?: string }>;
    readonly onSelect?: (row: CorpsRow | null) => void;
    /** 처음 여는 탭 — 주소 `?tab=operations` 로 「세력 작전」을 바로 연다. */
    readonly initialTab?: 'corps' | 'operations';
}

export function CorpsPanel(props: CorpsPanelProps) {
    const { load, order, releaseOptions, onOpenFlow, onOpenPolicy, onRelease, onSelect, initialTab = 'corps' } = props;
    const [tab, setTab] = useState<'corps' | 'operations'>(initialTab);
    const [picked, setPicked] = useState<string | null>(null);
    const [confirm, setConfirm] = useState<CorpsRow | null>(null);
    const [busy, setBusy] = useState(false);
    const [rejected, setRejected] = useState<{ corpsId: string; seq: number; code?: string; reason?: string } | null>(null);

    const rows = load.state === 'ready' ? load.rows : [];
    const mine = rows.filter((r) => r.own);
    const others = rows.filter((r) => !r.own);
    const current = rows.find((r) => r.corpsId === picked) ?? null;
    const pick = (row: CorpsRow | null) => { setPicked(row?.corpsId ?? null); onSelect?.(row); };
    // 편성 해제 단추 상태 · 사유 시트 도움말(K7 useReasonHelp) — 훅이라 카드가 없을 때도 늘 부른다.
    const releaseAvail = current?.own
        ? releaseAvailability(releaseOptions, current, rejected?.corpsId === current.corpsId ? rejected : null)
        : null;
    const releaseHelp = useReasonHelp(releaseAvail?.code ?? null, current?.own ? 'court.releaseCorps' : null);

    const release = async (row: CorpsRow) => {
        const choice = releaseChoiceFor(releaseOptions, row);
        setConfirm(null);
        if (!choice) return;
        setBusy(true);
        try {
            const out = await onRelease(row, choice.arguments);
            setRejected(out.ok ? null : { corpsId: row.corpsId, seq: Date.now(), ...(out.code ? { code: out.code } : {}), ...(out.reason ? { reason: out.reason } : {}) });
        } finally {
            setBusy(false);
        }
    };

    return (
        <section className={styles.panel} aria-label="군단" data-testid="corps-panel">
            <div className={styles.tabs} role="tablist" aria-label="군단 화면">
                <button type="button" role="tab" aria-selected={tab === 'corps'} className={styles.tab} onClick={() => setTab('corps')}>군단</button>
                <button type="button" role="tab" aria-selected={tab === 'operations'} className={styles.tab} onClick={() => setTab('operations')}>세력 작전</button>
            </div>

            {tab === 'operations' ? (
                <div className={styles.body}>
                    <StatusView kind="waiting" title="세력 작전 준비 중" body="여러 군단을 한 목표로 묶는 입력은 서버가 아직 받지 않습니다. 원군 요청도 같은 곳에 열립니다." />
                </div>
            ) : (
                <div className={styles.body}>
                    <div className={styles.actions}>
                        <button type="button" className="os-button os-button--primary" data-input-id="action.deploy" onClick={() => onOpenFlow('action.deploy')}>출병</button>
                        <button type="button" className="os-button" data-input-id="action.muster" onClick={() => onOpenFlow('action.muster')}>부대 모으기</button>
                        {onOpenPolicy ? <button type="button" className="os-button os-button--ghost" onClick={onOpenPolicy}>방침 바꾸기</button> : null}
                    </div>
                    {order ? (
                        <p className={styles.order} role="status">
                            지금 출병 명령 · 목적지 {order.destination ?? '이름 확인 중'}{order.stop ? ` · ${order.stop}` : ''}
                        </p>
                    ) : null}

                    {load.state === 'loading' ? <StatusView kind="loading" rows={3} /> : null}
                    {load.state === 'error' ? (
                        <StatusView kind="error" title="군단을 불러오지 못했습니다" body="빈 목록이 아닙니다 — 불러오기가 실패했습니다." errorCode={load.code} onRetry={load.onRetry} />
                    ) : null}
                    {load.state === 'ready' && mine.length === 0 ? (
                        <StatusView kind="empty" title="출전한 군단이 없습니다" body="출병하면 여기 나옵니다." />
                    ) : null}

                    {mine.length > 0 ? <CorpsList title="내 군단" rows={mine} picked={picked} onPick={pick} /> : null}
                    {others.length > 0 ? <CorpsList title="보이는 다른 군단" rows={others} picked={picked} onPick={pick} /> : null}

                    {current ? (
                        <article className={styles.card} aria-label={`군단 — ${current.commander.name ?? '장수'}`}>
                            <h3 className={styles.cardHead}>
                                {current.nationColor ? <i className={styles.flag} style={{ background: current.nationColor }} aria-hidden="true" /> : null}
                                {current.commander.name ?? '이름 모름'} 군단
                                <span className="os-chip">{current.own ? '내 군단' : visionLabel(current)}</span>
                            </h3>
                            <dl className={styles.facts}>
                                <dt>있는 곳</dt><dd>{current.where ?? '알 수 없음'}</dd>
                                {current.own ? <><dt>병력</dt><dd>{current.troops != null ? current.troops.toLocaleString('ko-KR') : '알 수 없음'}</dd></> : null}
                                {!current.own && current.band ? <><dt>병력</dt><dd>{current.band}</dd></> : null}
                                <dt>움직임</dt><dd>{current.marching ? `행군 중${current.destination ? ` → ${current.destination}` : ''}` : '머무는 중'}</dd>
                                {current.own ? (
                                    <><dt>방침</dt><dd>{current.policyKnown
                                        ? `${current.policy ?? '방침 없음'}${current.pendingPolicy ? ` · 다음 순부터 ${current.pendingPolicy}` : ''}`
                                        : '불러오지 못했습니다'}</dd></>
                                ) : null}
                                <dt>전투</dt><dd data-contract="K6-11"><span className="os-chip">서버 대기</span> 전투 중인지는 아직 서버가 알려 주지 않습니다</dd>
                            </dl>
                            {current.own ? (
                                <div className={styles.cardActions}>
                                    {onOpenPolicy ? <button type="button" className="os-button" onClick={onOpenPolicy}>군단장 바꾸기</button> : null}
                                    <InputAction
                                        key={rejected?.corpsId === current.corpsId ? `rej-${rejected.seq}` : 'release'}
                                        reasonDefaultOpen={rejected?.corpsId === current.corpsId}
                                        inputId="court.releaseCorps"
                                        availability={releaseAvail}
                                        label="편성 해제"
                                        variant="danger"
                                        busy={busy}
                                        reasonTitle="편성 해제 — 지금은 할 수 없습니다"
                                        onAct={() => setConfirm(current)}
                                        {...releaseHelp}
                                    />
                                    <button type="button" className="os-button os-button--ghost" onClick={() => pick(null)}>닫기</button>
                                </div>
                            ) : null}
                        </article>
                    ) : null}
                </div>
            )}

            <ConfirmDialog
                open={confirm != null}
                title="군단 편성을 풉니다"
                message={`${confirm?.commander.name ?? ''} 군단의 편성을 풉니다.`}
                confirmLabel="편성 해제"
                cancelLabel="그대로 두기"
                danger
                busy={busy}
                onConfirm={() => { if (confirm) void release(confirm); }}
                onCancel={() => setConfirm(null)}
            />
        </section>
    );
}

function CorpsList({ title, rows, picked, onPick }: { title: string; rows: readonly CorpsRow[]; picked: string | null; onPick: (r: CorpsRow) => void }) {
    return (
        <section className={styles.group} aria-label={title}>
            <h3 className={styles.groupHead}>{title} <span className={styles.muted}>{rows.length}</span></h3>
            <ul className={styles.rows}>
                {rows.map((r) => (
                    <li key={r.corpsId}>
                        <button type="button" className={styles.row} aria-current={r.corpsId === picked || undefined} onClick={() => onPick(r)} data-corps-id={r.corpsId}>
                            {r.nationColor ? <i className={styles.flag} style={{ background: r.nationColor }} aria-hidden="true" /> : <i className={styles.flag} aria-hidden="true" />}
                            <span className={styles.rowName}>{r.commander.name ?? '이름 모름'}</span>
                            <span className={styles.rowSub}>
                                {r.where ?? '있는 곳 모름'}
                                {r.own ? (r.troops != null ? ` · ${r.troops.toLocaleString('ko-KR')}명` : '') : r.band ? ` · ${r.band}` : ''}
                                {r.marching ? ' · 행군 중' : ''}
                            </span>
                            {!r.own ? <span className={styles.rowVision}>{visionLabel(r)}</span> : null}
                        </button>
                    </li>
                ))}
            </ul>
        </section>
    );
}

function visionLabel(r: CorpsRow): string {
    if (r.vision === 'FULL') return '지금 보임';
    return r.ageTurns != null ? `${r.ageTurns}순 전 첩보` : '첩보';
}

/** 편성 해제 단추 상태 — 원장(UI_READY) + 조정 옵션(권한 · 이 군단이 후보인지) + 마지막 서버 거절. */
function releaseAvailability(options: CourtActionOptions | null, row: CorpsRow, rejected: { code?: string; reason?: string } | null) {
    if (rejected) return availabilityOf('court.releaseCorps', { rejected });
    if (!options) return availabilityOf('court.releaseCorps', { options: 'loading' });
    if (!options.available) return availabilityOf('court.releaseCorps', { options: { available: false, code: options.code, reason: options.reason } });
    const choice = releaseChoiceFor(options, row);
    if (!choice) return availabilityOf('court.releaseCorps', { options: { available: false, reason: '이 군단은 편성 해제 대상이 아닙니다' } });
    return availabilityOf('court.releaseCorps', { options: { available: choice.available, code: choice.code, reason: choice.reason } });
}

export default CorpsPanel;
