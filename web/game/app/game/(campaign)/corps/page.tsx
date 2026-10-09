'use client';

import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useState } from 'react';
import { StatusView } from '@opensamguk/ui';
import GameShell from '@/components/GameShell';
import { CorpsPanel, type CorpsLoad } from '@/components/corps/CorpsPanel';
import { api, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { deployOrderOf, toCorpsRows } from '@/lib/corps/corps-model';
import { useServerGameUrl } from '@/lib/serverGameUrl';
import { corpsPolicyHref, territoryBaseFor } from '@/lib/territory/corps-policy-link';
import { warRoomMapSearch } from '@/lib/war-room-map-view';
import type { CourtActionOptions } from '@/lib/types';
import styles from './page.module.css';

/**
 * 군단 · 세력 작전(P-C01) — K6 설계서 §3.4, 보드 V31K6Corps · MCorps. 새 화면.
 *
 * 오른쪽 칸(448)은 내 군단 · 보이는 남의 군단 목록 → 고른 군단 카드. 읽기는 `/api/corps` · `/api/visibility`(군 이름) ·
 * `/api/deploy/options`(내 출병 명령 · 목적 구역 이름) · `/api/policies`(군단 방침) · 조정 옵션 `court.releaseCorps`.
 * 왼쪽 지도(군단 경로 레이어)는 지도 층(K2) 몫이라 자리만 두고 천하 지도로 잇는다.
 * 「출병」 · 「부대 모으기」는 작전실 명령 흐름(`?do=…`)으로, 방침은 영지 방침 칸(`?view=policy&scope=CORPS&orderId=…`),
 * 군단장은 영지 배치로 간다(P-T01).
 * 편성 해제는 확인 뒤 이 자리에서 보내고, 서버가 받지 않으면 그 사유로 단추가 막힌다.
 */
/** 편성 해제 선택지를 못 읽었을 때 — 단추를 「가능」으로 두지 않고 이 사유로 막는다(빈 선택지와 다르다). */
const RELEASE_OPTIONS_FAILED: CourtActionOptions = {
    inputId: 'court.releaseCorps', available: false, reason: '편성 해제 선택지를 불러오지 못했습니다 — 새로고침해 주세요', choices: [],
};

export default function CorpsPage() {
    const { generalId } = useGameSession();
    const router = useRouter();
    // 방침 링크는 지금 탭 주소의 서버를 먼저 쓴다(다른 탭이 서버 쿠키를 바꿔도 이 탭의 서버로 간다).
    const pathname = usePathname();
    // `?tab=operations` — 「세력 작전」 탭을 바로 연다(옛 작전 링크가 갈 자리).
    const initialTab = useSearchParams()?.get('tab') === 'operations' ? 'operations' : 'corps';
    const [seq, setSeq] = useState(0);
    const corps = useCampaignRead((id, signal) => api.campaignCorps(id, signal), [seq]);
    const vision = useCampaignRead((id, signal) => api.campaignVisibility(id, signal), [seq]);
    const deploy = useCampaignRead((id) => api.deployOptions(id), [seq]);
    const policies = useCampaignRead((id, signal) => api.campaignPolicies(id, signal), [seq]);
    const release = useCampaignRead((id) => api.legacyCourtOptions('court.releaseCorps', id), [seq]);
    const warRoomHref = useServerGameUrl('');
    const territoryHref = useServerGameUrl('territory');
    // 옛 천하 지도(/game/map)는 지웠다 — 작전실 주 보기로 연다(새 지도만 ?view= 를 듣는다)
    const mapHref = `${warRoomHref}${warRoomMapSearch('ju')}`;
    const retry = () => setSeq((n) => n + 1);

    const list = corps.data;
    const load: CorpsLoad = corps.error || (list && list.status !== 'READY')
        ? { state: 'error', onRetry: retry, ...(list && list.status !== 'READY' ? { code: list.status } : {}) }
        : list ? { state: 'ready', rows: toCorpsRows(list, vision.data, deploy.data, policies.data) }
        : { state: 'loading' };

    return (
        <GameShell title="군단 · 세력 작전">
            <div className={styles.page}>
                <section className={styles.map} aria-label="군단 지도">
                    <StatusView kind="waiting" title="군단 지도 준비 중" body="군단 경로를 지도에 그리는 층은 준비 중입니다. 천하 지도는 지도 화면에서 봅니다." />
                    <Link href={mapHref} className="os-button os-button--ghost">천하 지도 보기</Link>
                </section>
                <CorpsPanel
                    initialTab={initialTab}
                    load={load}
                    order={deployOrderOf(deploy.data)}
                    releaseOptions={release.error ? RELEASE_OPTIONS_FAILED : release.data}
                    onOpenFlow={(inputId) => router.push(`${warRoomHref}?do=${encodeURIComponent(inputId)}`)}
                    onOpenPolicy={(corpsId) => router.push(corpsPolicyHref(territoryBaseFor(pathname, territoryHref), corpsId))}
                    onOpenPlacement={() => router.push(territoryHref)}
                    onRelease={async (_row, args) => {
                        if (generalId == null) return { ok: false, reason: '장수를 확인하지 못했습니다' };
                        const out = await api.courtLegacy('court.releaseCorps', generalId, args);
                        if (isIntakeQueued(out)) { retry(); return { ok: true }; }
                        return { ok: false, ...(out.code ? { code: out.code } : {}), ...(out.reason ? { reason: out.reason } : {}) };
                    }}
                />
            </div>
        </GameShell>
    );
}
