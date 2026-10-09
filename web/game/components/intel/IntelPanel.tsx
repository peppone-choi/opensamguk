'use client';

// 시야 · 첩보(P-C06) 오른쪽 목록 360 — 보드 V31K6Intel · MIntel. 지도(郡 보기 · 시야 레이어)는 K2 부품.
// 군 행의 「첩보」는 명령 흐름(첩보, 대상 미리 채움)을 연다 — 단추 상태는 첩보 옵션(서버 가능 여부 · 사유).
// 「정찰 보내기」 · 「망루 짓기」는 배치 · 공사 화면(P-T01, K4)으로 간다.
// 「내 시야 출처」는 시야 읽기가 READY 일 때 그 응답의 sources 만 그린다(종류 · 郡國 이름 · 반경, 날 id 없음).
// 첩보 단추의 사유 시트에는 회복 문장 · 도움말 고리(K7 useReasonHelp)를 붙인다 — 훅이라 행마다 작은 부품으로 나눈다.
import { InputAction, StatusView } from '@opensamguk/ui';
import { useReasonHelp } from '@/hooks/useHelp';
import { availabilityOf } from '@/lib/input-availability';
import {
    SOURCE_LABEL, TIER_LABEL, sourceLine, tierLine,
    type IntelRow, type IntelSources, type IntelView, type ServerInvalidSources,
} from '@/lib/intel/intel-model';
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
    // 출처는 읽기가 READY 일 때만 그 응답에서 그린다 — 읽는 중 · 실패에는 출처 칸이 없다.
    const sources = <Sources sources={view.sources} serverInvalid={view.serverInvalidSources} />;
    if (view.groups.length === 0) return <><StatusView kind="empty" title="보이는 군이 없습니다" body="출병하거나 첩보를 보내면 여기에 보입니다." />{sources}</>;
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
            {sources}
        </>
    );
}

function Sources({ sources, serverInvalid }: { readonly sources: IntelSources; readonly serverInvalid: ServerInvalidSources }) {
    return (
        <section className={styles.box} aria-label="내 시야 출처">
            <h3 className={styles.head}>내 시야 출처</h3>
            {sources.state === 'absent' ? (
                <StatusView kind="waiting" title="시야 출처를 받지 못했습니다" body="이 서버는 시야 출처를 보내지 않습니다. 출처가 없다는 뜻은 아닙니다." />
            ) : sources.state === 'malformed' ? (
                <p className={styles.note} role="status">시야 출처 목록을 읽지 못했습니다 — 출처가 없다는 뜻은 아닙니다.</p>
            ) : sources.rows.length === 0 ? (
                <p className={styles.note}>지금 시야를 주는 출처가 없습니다.</p>
            ) : (
                <ul className={styles.rows}>
                    {sources.rows.map((s, i) => (
                        <li key={i} className={styles.source} data-source-kind={s.kind ?? 'UNKNOWN'} data-malformed={s.malformed || undefined}>
                            <span className={styles.name}>{s.kind ? SOURCE_LABEL[s.kind] : '알 수 없는 출처'}</span>
                            <span className={styles.line}>{sourceLine(s)}</span>
                        </li>
                    ))}
                </ul>
            )}
            {sources.state === 'listed' && sources.malformedRows > 0 ? (
                <p className={styles.note}>모양이 어긋난 출처 {sources.malformedRows}줄 — 알 수 없는 칸은 그대로 「알 수 없음」으로 둡니다.</p>
            ) : null}
            {serverInvalid.state === 'count' && serverInvalid.count > 0 ? (
                <p className={styles.note}>읽지 못한 출처 기록 {serverInvalid.count}개</p>
            ) : serverInvalid.state === 'missing' ? (
                <p className={styles.note}>읽지 못한 출처 기록 수를 받지 못했습니다.</p>
            ) : serverInvalid.state === 'invalid' ? (
                <p className={styles.note}>읽지 못한 출처 기록 수를 알 수 없습니다.</p>
            ) : null}
        </section>
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
