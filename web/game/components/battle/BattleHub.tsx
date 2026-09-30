'use client';

// 전투 · 부재 대비(P-C04) — 왼쪽 내 전투 목록 · 오른쪽 부재 대비 360. 보드 V31K6Battles · MBattles. K6 설계서 §3.5.
// 전투 목록은 서버가 전투를 열지 않아(캠페인 → 실시간 전투 배선 전) 영역 전체 서버 대기다.
// 부재 대비는 방침 읽기로 그리고, 고치기는 방침 화면(P-T01, K4) · 계책 덱(P-S01)으로 간다.
import { StatusView } from '@opensamguk/ui';
import { ABSENCE_NOTE, BATTLE_NOT_OPEN, type AbsenceView } from '@/lib/battle/absence';
import styles from './Battle.module.css';

export type AbsenceLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly view: AbsenceView; readonly onRetry: () => void };

export interface BattleHubProps {
    readonly absence: AbsenceLoad;
    readonly onOpenPolicy?: () => void;
    readonly onOpenStratagem?: () => void;
}

export function BattleHub({ absence, onOpenPolicy, onOpenStratagem }: BattleHubProps) {
    return (
        <div className={styles.hub} data-testid="battle-hub">
            <section className={styles.battles} aria-label="내 전투">
                <h2 className={styles.head}>내 전투</h2>
                <StatusView kind="waiting" title={BATTLE_NOT_OPEN.title} body={BATTLE_NOT_OPEN.body} />
            </section>
            <aside className={styles.absence} aria-label="부재 대비">
                <h2 className={styles.head}>부재 대비</h2>
                <p className={styles.note}>{ABSENCE_NOTE}</p>
                <AbsenceList absence={absence} />
                <div className={styles.links}>
                    {onOpenPolicy ? <button type="button" className="os-button" onClick={onOpenPolicy}>방침 고치기</button> : null}
                    {onOpenStratagem ? <button type="button" className="os-button os-button--ghost" onClick={onOpenStratagem}>대응 계책 칸 보기</button> : null}
                </div>
                <section className={styles.sub} aria-label="대응 계책 칸">
                    <h3 className={styles.subHead}>대응 계책 칸</h3>
                    <p className={styles.muted}>비어 있음 — 계책을 거는 입력이 아직 열리지 않았습니다.</p>
                </section>
            </aside>
        </div>
    );
}

function AbsenceList({ absence }: { absence: AbsenceLoad }) {
    if (absence.state === 'loading') return <StatusView kind="loading" rows={3} />;
    if (absence.state === 'error') return <StatusView kind="error" title="방침을 불러오지 못했습니다" body="빈 목록이 아닙니다 — 불러오기가 실패했습니다." onRetry={absence.onRetry} />;
    if (absence.view.state === 'unreadable') {
        return <StatusView kind="error" title="이 서버에서는 방침을 읽을 수 없습니다" errorCode={absence.view.status} onRetry={absence.onRetry} />;
    }
    const rows = absence.view.rows;
    if (rows.length === 0) return <StatusView kind="empty" title="맡긴 군단 · 현이 없습니다" body="출병하거나 현에 배치되면 없을 때 누가 어떻게 싸우는지 여기 보입니다." />;
    return (
        <ul className={styles.rows} aria-label="없을 때 싸우는 것">
            {rows.map((r) => (
                <li key={r.key} className={styles.row}>
                    <span className="os-chip">{r.kind === 'corps' ? '군단' : '맡은 현'}</span>
                    <span className={styles.name}>{r.name}</span>
                    <span className={styles.policy}>
                        {r.policy ?? '방침 없음'}
                        {r.pending ? <span className={styles.muted}> · 다음 순부터 {r.pending}</span> : null}
                    </span>
                    {r.blocked ? <span className={styles.blocked}>{r.blocked}</span> : null}
                </li>
            ))}
        </ul>
    );
}

/** P-C03 · P-C05 자리(`/corps/battle/[id]`) — 서버가 전투를 열기 전에는 이 상태뿐이다. */
export function BattleRoomUnavailable({ onBack }: { onBack?: () => void }) {
    return (
        <div className={styles.room} data-testid="battle-room">
            <StatusView
                kind="waiting"
                title={BATTLE_NOT_OPEN.title}
                body={BATTLE_NOT_OPEN.body}
            />
            {onBack ? <button type="button" className="os-button" onClick={onBack}>전투 · 부재 대비로</button> : null}
        </div>
    );
}

export default BattleHub;
