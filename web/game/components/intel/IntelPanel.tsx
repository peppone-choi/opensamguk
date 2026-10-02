'use client';

// 시야 · 첩보(P-C06) 오른쪽 목록 360 — 보드 V31K6Intel · MIntel. 지도(郡 보기 · 시야 레이어)는 K2 부품.
// 군 행의 「첩보」는 명령 흐름(첩보, 대상 미리 채움)을 연다 — 단추 상태는 첩보 옵션(서버 가능 여부 · 사유).
// 「정찰 보내기」 · 「망루 짓기」는 배치 · 공사 화면(P-T01, K4)으로 간다.
// 첩보 단추의 사유 시트에는 회복 문장 · 도움말 고리(K7 useReasonHelp)를 붙인다 — 훅이라 행마다 작은 부품으로 나눈다.
import { InputAction, StatusView } from '@opensamguk/ui';
import { useReasonHelp } from '@/hooks/useHelp';
import { availabilityOf } from '@/lib/input-availability';
import { TIER_LABEL, tierLine, type IntelRow, type IntelView } from '@/lib/intel/intel-model';
import styles from './Intel.module.css';

export type IntelLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly view: IntelView; readonly onRetry: () => void };

export interface IntelPanelProps {
    readonly load: IntelLoad;
    readonly onScout: (row: IntelRow) => void;
    readonly onOpenPlacement?: () => void;
    readonly onOpenWorks?: () => void;
}

const TIER_TONE = { FULL: 'os-chip--moss', INTEL: 'os-chip--info', FOG: '' } as const;

export function IntelPanel({ load, onScout, onOpenPlacement, onOpenWorks }: IntelPanelProps) {
    return (
        <section className={styles.panel} aria-label="시야 · 첩보" data-testid="intel-panel">
            <div className={styles.links}>
                {onOpenPlacement ? <button type="button" className="os-button" onClick={onOpenPlacement}>정찰 보내기</button> : null}
                {onOpenWorks ? <button type="button" className="os-button os-button--ghost" onClick={onOpenWorks}>망루 짓기</button> : null}
            </div>
            <Body load={load} onScout={onScout} />
            <section className={styles.box} aria-label="내 시야 출처">
                <h3 className={styles.head}>내 시야 출처</h3>
                <StatusView kind="waiting" title="시야 출처 준비 중" body="내 위치 · 군단 · 부 인물 · 망루 같은 출처는 서버가 아직 주지 않습니다." />
            </section>
        </section>
    );
}

function Body({ load, onScout }: { load: IntelLoad; onScout: (row: IntelRow) => void }) {
    if (load.state === 'loading') return <StatusView kind="loading" rows={5} />;
    if (load.state === 'error') return <StatusView kind="error" title="시야를 불러오지 못했습니다" body="빈 지도가 아닙니다 — 불러오기가 실패했습니다." onRetry={load.onRetry} />;
    const view = load.view;
    if (view.state === 'unreadable') {
        return <StatusView kind="error" title={view.status === 'UNAVAILABLE' ? '시야를 계산하지 못했습니다' : '이 서버에서는 시야를 읽을 수 없습니다'} errorCode={view.status} onRetry={load.onRetry} />;
    }
    if (view.groups.length === 0) return <StatusView kind="empty" title="보이는 군이 없습니다" body="출병하거나 첩보를 보내면 여기에 보입니다." />;
    return (
        <>
            {view.scoutBlocked ? (
                <p className={styles.blocked} role="status">지금은 첩보를 보낼 수 없습니다 — {view.scoutBlocked.reason ?? '사유를 받지 못했습니다'}</p>
            ) : null}
            {view.groups.map((g) => (
                <section key={g.tier} className={styles.group} aria-label={TIER_LABEL[g.tier]}>
                    <h3 className={styles.head}><span className={`os-chip ${TIER_TONE[g.tier]}`}>{TIER_LABEL[g.tier]}</span> <span className={styles.muted}>{g.rows.length}</span></h3>
                    <ul className={styles.rows}>
                        {g.rows.map((r) => (
                            <li key={r.no} className={styles.row} data-tier={r.tier} data-commandery-no={r.no}>
                                <span className={styles.name}>{r.name}</span>
                                <span className={styles.line}>{tierLine(r)}</span>
                                {r.scout ? <ScoutAction row={r} blocked={view.scoutBlocked} onScout={onScout} /> : null}
                            </li>
                        ))}
                    </ul>
                </section>
            ))}
        </>
    );
}

function ScoutAction({ row, blocked, onScout }: {
    readonly row: IntelRow;
    readonly blocked: { readonly code: string | null; readonly reason: string | null } | null;
    readonly onScout: (row: IntelRow) => void;
}) {
    const availability = availabilityOf('action.scout', { options: blocked ? { available: false, ...blocked } : row.scout! });
    const help = useReasonHelp(availability?.code ?? null, 'action.scout');
    return (
        <InputAction
            inputId="action.scout"
            availability={availability}
            label="첩보"
            variant="ghost"
            reasonTitle={`${row.name} 첩보 — 지금은 할 수 없습니다`}
            onAct={() => onScout(row)}
            className={styles.scout}
            {...help}
        />
    );
}

export default IntelPanel;
