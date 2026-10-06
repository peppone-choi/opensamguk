'use client';

// 서신(P-Q02) — 개인 · 세력 · 전체 · 요청. K6 설계서 §3.8, 보드 V31K6Mail · MMail.
// 외교 서신은 외교 화면(P-K02)의 칸이다 — 이 화면에는 외교 탭이 없다. 머리줄 서신 단추도 이 주소를 연다.
// 장수 · 세력은 셸(GameFrame)의 세션 한 읽기에서 받고, GameShell 이 광장 하위 탭 · 장수 없음 · 불러오는 중을 맡는다.
// 받은 요청은 한 읽기(useRequests)를 요청 탭 배지와 같이 쓴다. 「새로고침」은 서신함과 받은 요청을 같이 다시 읽는다.
import { useState } from 'react';
import GameShell from '@/components/GameShell';
import { MailScreen } from '@/components/mail/MailScreen';
import { useGameSession } from '@/lib/campaign-session';
import { useRequests } from '@/lib/requests';
import styles from './page.module.css';

export default function MailPage() {
    const session = useGameSession();
    const general = session.frontInfo?.general ?? null;
    const [refreshKey, setRefreshKey] = useState(0);
    const requests = useRequests(session.generalId, refreshKey);

    return (
        <GameShell title="서신">
            <div className={styles.toolbar}>
                <button type="button" className="os-button os-button--ghost" onClick={() => setRefreshKey((k) => k + 1)}>새로고침</button>
            </div>
            {session.generalId != null && general ? (
                <MailScreen me={{ generalId: session.generalId, nationId: general.nationId }} requests={requests} refreshKey={refreshKey} />
            ) : null}
        </GameShell>
    );
}
