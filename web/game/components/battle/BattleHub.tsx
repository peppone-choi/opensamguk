'use client';

// 전투 · 부재 대비(P-C04) — 왼쪽 내 전투 목록 · 오른쪽 부재 대비 360. 보드 V31K6Battles · MBattles. K6 설계서 §3.5.
// 전투 목록은 K6-11 읽기(useActiveBattles)로 그린다. 정상 빈 목록·인증·권한·원천 불가·조회 실패를 구분하고,
// 행이 오면 ActiveBattleList(종류 · 장소 · 양쪽 · 내 자리 · 상태 · 입장).
// 부재 대비는 방침 읽기로 그리고, 고치기는 방침 화면(P-T01, K4) · 계책 덱(P-S01)으로 간다.
import { StatusView } from '@opensamguk/ui';
import { ABSENCE_NOTE, BATTLE_LIST_STATUS, BATTLE_NOT_OPEN, type AbsenceView } from '@/lib/battle/absence';
import type { ActiveBattlesLoad } from '@/lib/battle/use-active-battles';
import { ActiveBattleList } from './ActiveBattleList';
import styles from './Battle.module.css';

export type AbsenceLoad =
    | { readonly state: 'loading' }
    | { readonly state: 'error'; readonly onRetry: () => void }
    | { readonly state: 'ready'; readonly view: AbsenceView; readonly onRetry: () => void };

export interface BattleHubProps {
    readonly absence: AbsenceLoad;
    /** 내 전투 목록 읽기 — 없으면 서버 대기. */
    readonly battles?: ActiveBattlesLoad;
    /** 방 주소 앞부분(서버 경로 포함). */
    readonly roomBase?: string;
    readonly onOpenPolicy?: () => void;
    readonly onOpenStratagem?: () => void;
}

export function BattleHub({ absence, battles = { state: 'waiting' }, roomBase = '/game/corps/battle', onOpenPolicy, onOpenStratagem }: BattleHubProps) {
    return (
        <div className={styles.hub} data-testid="battle-hub">
            <section className={styles.battles} aria-label="내 전투">
                <h2 className={styles.head}>내 전투</h2>
                {battles.state === 'ready' ? <ActiveBattleList rows={battles.rows} roomBase={roomBase} />
                    : battles.state === 'loading' ? <StatusView kind="loading" rows={3} />
                    : battles.state === 'empty' ? <StatusView kind="empty" {...BATTLE_LIST_STATUS.empty} actions={<button type="button" className="os-button" onClick={battles.onRetry}>다시 읽기</button>} />
                    : battles.state === 'unauthorized' || battles.state === 'forbidden' ? <StatusView kind="denied" title={BATTLE_LIST_STATUS[battles.state].title} howTo={BATTLE_LIST_STATUS[battles.state].body} />
                    : battles.state === 'source-unavailable' ? <StatusView kind="unavailable" {...BATTLE_LIST_STATUS.sourceUnavailable} onReload={battles.onRetry} />
                    : battles.state === 'error' ? <StatusView kind="error" title="전투 목록을 읽지 못했습니다" body={battles.errorCode === 'INVALID_RESPONSE' ? '받은 목록의 모양이 약속과 다릅니다. 다시 읽어 주세요.' : battles.errorCode === 'HTTP_404' ? '이 서버에서 전투 목록을 찾지 못했습니다. 잠시 후 다시 읽어 주세요.' : '전투 목록 요청이 실패했습니다. 잠시 후 다시 읽어 주세요.'} errorCode={battles.errorCode} onRetry={battles.onRetry} />
                    : <div data-server-wait="K6-11"><StatusView kind="waiting" {...BATTLE_LIST_STATUS.waiting} /></div>}
            </section>
            <aside className={styles.absence} aria-label="부재 대비">
                <h2 className={styles.head}>부재 대비</h2>
                <p className={styles.note}>{ABSENCE_NOTE}</p>
                <p className={styles.muted}>현은 내가 직접 맡은 현만 보입니다. 수하에게 맡긴 현은 아직 보이지 않습니다.</p>
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
    if (rows.length === 0) return <StatusView kind="empty" title="출전 군단 · 직접 맡은 현이 없습니다" body="출병하거나 발령받은 현에 부임하면 방침이 여기 보입니다." />;
    return (
        <ul className={styles.rows} aria-label="없을 때 싸우는 것">
            {rows.map((r) => (
                <li key={r.key} className={styles.row}>
                    <span className="os-chip">{r.kind === 'corps' ? '군단' : '직접 맡은 현'}</span>
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
