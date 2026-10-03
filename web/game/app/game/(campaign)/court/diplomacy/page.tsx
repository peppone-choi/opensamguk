'use client';

// 외교(P-K02) — 옛 「중원 정보」를 대신한다. K6 설계서 §3.7, 보드 V31K6Diplomacy · MDiplomacy.
// 오른쪽 칸(세력 외교 · 주변 세계 · 외교 서신)은 DiplomacyPanel. 관계는 지금 읽기 `/api/diplomacy/conflict`의 코드를 글자로
// (계약판 K6-05 `/api/diplomacy/relations` 가 오면 바꾼다). 분쟁 현황(삼모 점령 기여)은 옮기지 않는다.
// 왼쪽 관계 지도(세력 경계 · 관계 레이어)는 지도 층 몫이라 자리만 두고 천하 지도로 잇는다.
// 장수 · 세력은 셸(GameFrame)의 세션 한 읽기에서 받고, GameShell 이 조정 하위 탭 · 장수 없음 · 불러오는 중을 맡는다.
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { DiplomacyPanel, type RelationsLoad } from '@/components/diplomacy/DiplomacyPanel';
import { MailScreen } from '@/components/mail/MailScreen';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from '@/lib/api';
import { useGameSession } from '@/lib/campaign-session';
import { toRelations } from '@/lib/diplomacy/relations';
import { useServerGameUrl } from '@/lib/serverGameUrl';
import { warRoomMapSearch } from '@/lib/war-room-map-view';
import styles from './page.module.css';

/** officerLevel 12 = 군주(front-info). */
const RULER_LEVEL = 12;

export default function DiplomacyPage() {
    const session = useGameSession();
    const general = session.frontInfo?.general ?? null;
    const [load, setLoad] = useState<RelationsLoad>({ state: 'loading' });
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    // 옛 천하 지도(/game/map)는 지웠다 — 작전실 주 보기로 연다(새 지도만 ?view= 를 듣는다)
    const mapHref = `${useServerGameUrl('')}${warRoomMapSearch('ju')}`;
    useTurnRefresh(reload);

    useEffect(() => {
        let alive = true;
        setLoad((l) => (l.state === 'ready' ? l : { state: 'loading' }));
        api.diplomacyConflict()
            .then((res) => { if (alive) setLoad({ state: 'ready', view: toRelations(res) }); })
            .catch(() => { if (alive) setLoad({ state: 'error', onRetry: reload }); });
        return () => { alive = false; };
    }, [seq, reload]);

    // 「새로고침」은 관계와 외교 서신을 같이 다시 읽는다(refreshKey).
    const letters = session.generalId != null && general
        ? <MailScreen me={{ generalId: session.generalId, nationId: general.nationId }} tabs={['diplomacy']} variant="drawer" refreshKey={seq} />
        : <StatusView kind="loading" rows={3} />;

    return (
        <GameShell title="외교">
            <div className={styles.toolbar}>
                <button type="button" className="os-button os-button--ghost" onClick={reload}>새로고침</button>
            </div>
            <div className={styles.page}>
                <section className={styles.map} aria-label="관계 지도">
                    <StatusView kind="waiting" title="관계 지도 준비 중" body="세력 경계와 관계를 지도에 칠하는 층은 준비 중입니다. 천하 지도는 지도 화면에서 봅니다." />
                    <Link href={mapHref} className="os-button os-button--ghost">천하 지도 보기</Link>
                </section>
                <DiplomacyPanel load={load} letters={letters} viewerIsRuler={general ? general.officerLevel === RULER_LEVEL : null} />
            </div>
        </GameShell>
    );
}
