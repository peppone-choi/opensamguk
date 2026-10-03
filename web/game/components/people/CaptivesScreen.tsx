'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Modal, Seg, StatusView, useViewportClass } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { RenownBand } from '@/components/retinue/RenownBand';
import { api } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { availabilityOf } from '@/lib/input-availability';
import { renownBand } from '@/lib/retinue-view';
import { CaptivePanel, TalentPanel, employQuery, talentRows } from './CaptivesParts';
import styles from './people.module.css';

export interface CaptivesScreenProps {
    readonly hrefs: {
        /** 명령 흐름 바탕 주소(`/game/<서버>`) — 뒤에 `?do=…&target=…` 를 붙인다. */
        readonly flowBase: string;
        readonly yuedan: string;
    };
}

/**
 * 포로 · 등용 화면 본문(P-R05, 보드 V31K4Captives · V31K4MCaptives).
 * 위 명망 띠(편성과 같은 부품) · 「등용할 수 있는 인재」 / 「잡은 포로」(서버 대기).
 * 모바일: 「인재 · 포로」 세그먼트, 인재를 누르면 하단 시트 「{이름} — 등용」(코스트 서버 대기 · 「등용 — 명령 흐름에서 순 고르기」).
 * 등용 · 인재탐색은 명령 흐름으로(`?do=action.employ&target=general:<id>`).
 */
export function CaptivesScreen({ hrefs }: CaptivesScreenProps) {
    const router = useRouter();
    const viewport = useViewportClass();
    // 구조가 다른 것은 모바일뿐 — 태블릿은 데스크톱 구조에 CSS 로 줄인다. 재기 전(null)은 뼈대.
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [view, setView] = useState<'talent' | 'captive'>('talent');
    const [picked, setPicked] = useState<number | null>(null);
    const [reload, setReload] = useState(0);
    const employ = useCampaignRead((id) => api.peopleOptions('action.employ', id), [reload]);
    const search = useCampaignRead((id) => api.peopleOptions('action.search', id), [reload]);
    const retinue = useCampaignRead((id, s) => api.campaignRetinue(id, s));
    const yuedan = useCampaignRead((id, s) => api.campaignYuedan(id, s));

    if (mobile === null || (employ.loading && !employ.data)) return <StatusView kind="loading" rows={4} />;
    const band = <RenownBand band={renownBand(retinue.data, yuedan.data)} people={retinue.data?.people.length ?? 0}
        units={retinue.data?.units.length ?? 0} yuedanHref={hrefs.yuedan} compact={mobile} />;
    const employAvailability = availabilityOf('action.employ', { options: employ.data ?? null });
    const toEmploy = (generalId: number) => router.push(`${hrefs.flowBase}${employQuery(generalId)}`);

    const talent = employ.error ? (
        <StatusView kind="error" title="등용할 인재를 불러오지 못했습니다" errorCode={employ.errorCode ?? undefined} onRetry={() => setReload((n) => n + 1)} />
    ) : (
        <TalentPanel employ={employ.data} search={search.data}
            searchAvailability={availabilityOf('action.search', { options: search.data ?? null })}
            employAvailability={employAvailability}
            onSearch={() => router.push(`${hrefs.flowBase}?do=${encodeURIComponent('action.search')}`)}
            picked={picked} onPick={setPicked} onEmploy={mobile ? null : toEmploy} />
    );
    const captive = <CaptivePanel persuade={availabilityOf('action.persuadeCaptive')} onPersuade={() => {}} />;

    if (mobile) {
        const chosen = talentRows(employ.data).find((r) => r.generalId === picked && r.available) ?? null;
        return (
            <div className={styles.peopleScreenMobile}>
                {band}
                <Seg label="보기" value={view} onChange={setView}
                    options={[{ value: 'talent', label: '인재', count: employ.data?.targets.length ?? null }, { value: 'captive', label: '포로' }]} />
                {view === 'talent' ? talent : captive}
                {chosen ? (
                    <Modal ariaLabel={`${chosen.name} — 등용`} onClose={() => setPicked(null)} overlayClassName={styles.sheetBottom}>
                        <div className={styles.captiveSheet}>
                            <div className={styles.sheetHead}>
                                <h3 className={styles.sheetTitle}>{`${chosen.name} — 등용`}</h3>
                                <button type="button" className="os-button os-button--sm" onClick={() => setPicked(null)}>닫기</button>
                            </div>
                            <p className={styles.muted}>성공하면 내 부 인물 카드가 됩니다.</p>
                            <dl className={styles.captiveKv}>
                                <div><dt className={styles.muted}>코스트</dt><dd className={styles.muted}>서버 대기</dd></div>
                            </dl>
                            <HelpedInputAction inputId="action.employ" availability={employAvailability} label="등용 — 명령 흐름에서 순 고르기"
                                onAct={() => toEmploy(chosen.generalId)} block />
                        </div>
                    </Modal>
                ) : null}
            </div>
        );
    }
    return (
        <div className={styles.peopleScreen}>
            {band}
            <div className={styles.peopleColumns}>
                <section className={`os-panel ${styles.peopleMain}`} aria-label="등용할 수 있는 인재">{talent}</section>
                <section className={`os-panel ${styles.peopleMain}`} aria-label="잡은 포로">{captive}</section>
            </div>
        </div>
    );
}
