'use client';

// 외교(P-K02) — 옛 「중원 정보」를 대신한다. K6 설계서 §3.7, 보드 V31K6Diplomacy · MDiplomacy.
// 오른쪽 칸(세력 외교 · 주변 세계 · 외교 서신)은 DiplomacyPanel. 관계는 지금 읽기 `/api/diplomacy/conflict`의 코드를 글자로
// (계약판 K6-05 `/api/diplomacy/relations` 가 오면 바꾼다). 분쟁 현황(삼모 점령 기여)은 옮기지 않는다.
// 왼쪽 관계 지도(세력 경계 · 관계 레이어)는 지도 층 몫이라 자리만 두고 천하 지도로 잇는다. 셸 메뉴가 이 주소를 연다.
// 셸(#1107) 전에는 이 경로가 휘하 세션 제공자 밖이라 장수 · 세력을 여기서 한 번 읽는다.
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import PageHead from '@/components/PageHead';
import Shell from '@/components/Shell';
import { DiplomacyPanel, type RelationsLoad } from '@/components/diplomacy/DiplomacyPanel';
import { MailScreen } from '@/components/mail/MailScreen';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';
import { api } from '@/lib/api';
import { toRelations } from '@/lib/diplomacy/relations';
import type { MailMe } from '@/lib/mail/use-mail';
import { useServerGameUrl } from '@/lib/serverGameUrl';
import styles from './page.module.css';

/** officerLevel 12 = 군주(front-info). */
const RULER_LEVEL = 12;

type Viewer = { state: 'loading' } | { state: 'error' } | { state: 'none' } | { state: 'ready'; me: MailMe; isRuler: boolean };

export default function DiplomacyPage() {
    const [viewer, setViewer] = useState<Viewer>({ state: 'loading' });
    const [viewerSeq, setViewerSeq] = useState(0);
    const [load, setLoad] = useState<RelationsLoad>({ state: 'loading' });
    const [seq, setSeq] = useState(0);
    const reload = useCallback(() => setSeq((n) => n + 1), []);
    const mapHref = useServerGameUrl('map');
    useTurnRefresh(reload);

    useEffect(() => {
        let alive = true;
        setViewer({ state: 'loading' });
        api.frontInfo()
            .then((info) => {
                if (!alive) return;
                const g = info.general;
                setViewer(g.hasGeneral && g.generalId != null
                    ? { state: 'ready', me: { generalId: g.generalId, nationId: g.nationId }, isRuler: g.officerLevel === RULER_LEVEL }
                    : { state: 'none' });
            })
            .catch(() => { if (alive) setViewer({ state: 'error' }); });
        return () => { alive = false; };
    }, [viewerSeq]);

    useEffect(() => {
        let alive = true;
        setLoad((l) => (l.state === 'ready' ? l : { state: 'loading' }));
        api.diplomacyConflict()
            .then((res) => { if (alive) setLoad({ state: 'ready', view: toRelations(res) }); })
            .catch(() => { if (alive) setLoad({ state: 'error', onRetry: reload }); });
        return () => { alive = false; };
    }, [seq, reload]);

    const letters = viewer.state === 'ready' ? <MailScreen me={viewer.me} tabs={['diplomacy']} variant="drawer" />
        : viewer.state === 'error' ? <StatusView kind="error" title="장수 정보를 불러오지 못했습니다" onRetry={() => setViewerSeq((n) => n + 1)} />
        : viewer.state === 'none' ? <StatusView kind="empty" title="이 서버에 내 장수가 없습니다" body="장수를 만들면 외교 서신을 볼 수 있습니다." />
        : <StatusView kind="loading" rows={3} />;

    return (
        <Shell>
            <PageHead
                title="외교"
                actions={<button type="button" className="os-button os-button--ghost" onClick={reload}>새로고침</button>}
            />
            <div className={styles.page}>
                <section className={styles.map} aria-label="관계 지도">
                    <StatusView kind="waiting" title="관계 지도 준비 중" body="세력 경계와 관계를 지도에 칠하는 층은 준비 중입니다. 천하 지도는 지도 화면에서 봅니다." />
                    <Link href={mapHref} className="os-button os-button--ghost">천하 지도 보기</Link>
                </section>
                <DiplomacyPanel load={load} letters={letters} viewerIsRuler={viewer.state === 'ready' ? viewer.isRuler : null} />
            </div>
        </Shell>
    );
}
