'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { IntelPanel, type IntelLoad } from '@/components/intel/IntelPanel';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { toIntelView } from '@/lib/intel/intel-model';
import { useServerGameUrl } from '@/lib/serverGameUrl';
import styles from './page.module.css';

/**
 * 시야 · 첩보(P-C06) — K6 설계서 §3.6, 보드 V31K6Intel · MIntel. 새 화면.
 *
 * 오른쪽 목록(360)은 군을 시야 단계(첩보 · 안 보임 · 다 보임)로 묶어 보이고, 첩보 후보 군에는 「첩보」 단추를 둔다.
 * 읽기는 `/api/visibility` · `/api/scout-options`. 「첩보」는 작전실 명령 흐름(`?do=action.scout&target=commandery:<id>`,
 * 그 군을 미리 고름)으로, 「정찰 보내기」 · 「망루 짓기」는 배치 · 공사 화면(영지, P-T01 — K4)으로 간다.
 * 왼쪽 지도(郡 보기 · 시야 레이어)는 지도 층(K2) 몫이라 자리만 두고 천하 지도로 잇는다. 역정보 표식은 어디에도 쓰지 않는다.
 */
export default function IntelPage() {
    const router = useRouter();
    const [seq, setSeq] = useState(0);
    const vision = useCampaignRead((id, signal) => api.campaignVisibility(id, signal), [seq]);
    const scout = useCampaignRead((id, signal) => api.campaignScoutOptions(id, signal), [seq]);
    const warRoomHref = useServerGameUrl('');
    const territoryHref = useServerGameUrl('territory');
    const mapHref = useServerGameUrl('map');
    const retry = () => setSeq((n) => n + 1);

    // 첩보 옵션을 못 읽으면 단추 없이 군만 보인다(가능으로 두지 않는다) — 시야 읽기가 실패하면 목록 전체가 실패 모양.
    const load: IntelLoad = vision.error ? { state: 'error', onRetry: retry }
        : vision.data ? { state: 'ready', view: toIntelView(vision.data, scout.error ? null : scout.data), onRetry: retry }
        : { state: 'loading' };

    return (
        <GameShell title="시야 · 첩보">
            <div className={styles.page}>
                <section className={styles.map} aria-label="시야 지도">
                    <StatusView kind="waiting" title="시야 지도 준비 중" body="군별 시야를 지도에 칠하는 층은 준비 중입니다. 천하 지도는 지도 화면에서 봅니다." />
                    <Link href={mapHref} className="os-button os-button--ghost">천하 지도 보기</Link>
                </section>
                <IntelPanel
                    load={load}
                    onScout={(row) => router.push(`${warRoomHref}?do=action.scout&target=${encodeURIComponent(`commandery:${row.id}`)}`)}
                    onOpenPlacement={() => router.push(territoryHref)}
                    onOpenWorks={() => router.push(territoryHref)}
                />
            </div>
        </GameShell>
    );
}
