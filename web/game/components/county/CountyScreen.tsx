'use client';

import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { Modal, ReasonTooltip, Seg, StatusView } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { PlacementSheet } from '@/components/territory/PlacementParts';
import { PolicySheet } from '@/components/territory/PolicyParts';
import { WorkSheet, WorksPanel } from '@/components/territory/WorkParts';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { flowCommand } from '@/lib/command-flow/catalog';
import { COUNTY_WAITING, specialtyRows, specialtyZeroReason } from '@/lib/county-view';
import { availabilityOf } from '@/lib/input-availability';
import { countyPolicyRows } from '@/lib/territory-view';
import { useIsMobile } from '@/lib/use-viewport';
import { CountyGovernance, CountyHeader, CountyRecordsLink, CountySpecialties, CountyStock, HereActions } from './CountyParts';
import styles from './county.module.css';

/** 내 장수가 이 현에 서 있을 때 여기서 할 수 있는 직접 행동(설계서 P-T02 입력 표). 이름은 K6 명령 표(원장 displayName)에서. */
const HERE_INPUTS = [
    'action.farm', 'action.commerce', 'action.fortify', 'action.repairWall', 'action.security', 'action.settle', 'action.selectResidents',
    'action.tour', 'action.conscript', 'action.raiseVolunteers', 'action.train', 'action.boostMorale', 'action.demobilize',
] as const;

type Kind = 'placement' | 'policy' | 'work';
type Sheet = { readonly kind: 'seatCard' } | { readonly kind: 'seat'; readonly cardId: number } | { readonly kind: 'policy' } | { readonly kind: 'work' };
type MobileTab = 'state' | 'rule' | 'work' | 'people' | 'events';

export interface CountyScreenProps {
    readonly cityId: number;
    readonly hrefs: {
        /** 기록 피드 현 거르기(K5). */
        readonly records: string;
        /** 명령 흐름을 그 입력 · 대상으로. */
        readonly flow: (inputId: string, target: string) => string;
    };
}

/**
 * 현 상세 화면 본문(P-T02). 지금 있는 조회(특산 · 창고 · 방침 · 공사 · 배치 · 우리 현 목록)로 채울 수 있는 칸만 채우고,
 * 7지표 · 수비군 · 이 현의 인물은 K4-04 전까지 서버 대기. 소속은 우리 현이면 세력명, 아니면 칩 없음(짐작 금지).
 * 입력은 이 현을 미리 채운다: 현령 앉히기(카드 고르기 → 배치 시트 MAGISTRATE · 이 현) · 방침 · 공사 · 직접 행동 · 첩보.
 */
export function CountyScreen({ cityId, hrefs }: CountyScreenProps) {
    const { frontInfo, generalId } = useGameSession();
    const router = useRouter();
    const mobile = useIsMobile();
    const [reload, setReload] = useState(0);
    const [tab, setTab] = useState<MobileTab>('state');
    const [sheet, setSheet] = useState<Sheet | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const county = useCampaignRead((id, s) => api.campaignCounty(id, cityId, s), [cityId, reload]);
    const warehouses = useCampaignRead((id, s) => api.warehouses(id, s), [reload]);
    const policies = useCampaignRead((id, s) => api.campaignPolicies(id, s), [reload]);
    const works = useCampaignRead((id, s) => api.campaignWorks(id, s), [reload]);
    const posts = useCampaignRead((id, s) => api.campaignPosts(id, s), [reload]);
    const ours = useCampaignRead((id, s) => api.counties(id, 'NATION', null, s));

    const submit = async (kind: Kind, body: Readonly<Record<string, unknown>>) => {
        if (generalId == null) return;
        setBusy(true);
        try {
            const out = await api.campaignDomestic(generalId, kind, body);
            if (isIntakeQueued(out)) { setNotice({ tone: 'ok', text: '접수했습니다 — 다음 턴부터 반영합니다.' }); setSheet(null); setReload((n) => n + 1); }
            else if (isIntakeDenied(out)) setNotice({ tone: 'error', text: out.reason?.trim() || '접수하지 못했습니다.' });
        } catch { setNotice({ tone: 'error', text: '보내지 못했습니다 — 다시 해 보세요.' }); } finally { setBusy(false); }
    };

    if (mobile === null || (county.loading && !county.data)) return <StatusView kind="loading" rows={6} />;
    if (county.error) {
        const code = county.error.split(':')[0];
        if (code === '404') return <StatusView kind="not-found" />;
        return <StatusView kind="error" title="현을 불러오지 못했습니다" errorCode={code} onRetry={() => setReload((n) => n + 1)} />;
    }
    const serverNotice = campaignReadNotice(county, county.data?.status);
    if (serverNotice || !county.data) return <StatusView kind="waiting" title={serverNotice ?? '현을 받지 못했습니다'} />;

    const house = warehouses.data?.warehouses.find((w) => w.cityId === cityId) ?? null;
    const mine = ours.data?.counties.some((c) => c.cityId === cityId) ?? false;
    const nation = mine && frontInfo?.nation ? { label: frontInfo.nation.name, color: frontInfo.nation.color } : null;
    const policy = policies.data ? countyPolicyRows(policies.data).find((r) => r.targetId === String(cityId)) ?? null : null;
    const workCounty = works.data?.counties.find((c) => c.countyId === cityId) ?? null;
    const here = frontInfo?.city?.id === cityId;
    const target = `county:${cityId}`;

    const head = (
        <CountyHeader name={county.data.name} commanderyName={policy?.sub ?? workCounty?.commanderyName ?? null} nation={nation}
            isolated={house ? !house.supplied : null} here={here} />
    );
    const noticeLine = notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null;
    const stateCol = (
        <>
            <StatusView kind="waiting" title={COUNTY_WAITING.indicators.title} body={COUNTY_WAITING.indicators.body} />
            <CountySpecialties rows={specialtyRows(county.data)} zeroReason={specialtyZeroReason(nation != null, house)} />
            <CountyStock stock={house?.stock ?? null} />
        </>
    );
    const ruleCol = (
        <CountyGovernance policy={policy}
            policyAvailability={policy ? availabilityOf('policy.set', { options: { available: policy.settable, code: policy.blocked?.code, reason: policy.blocked?.reason } }) : null}
            onChangePolicy={() => setSheet({ kind: 'policy' })}
            seatAvailability={mine ? availabilityOf('placement.assign') : null} onSeat={() => setSheet({ kind: 'seatCard' })} />
    );
    const workCol = workCounty && works.data ? (
        <WorksPanel works={{ ...works.data, counties: [workCounty] }} startAvailabilityOf={() => availabilityOf('work.start')}
            reduceAvailability={availabilityOf('work.reduce')} onNewWork={() => setSheet({ kind: 'work' })} onReduce={() => {}} />
    ) : <p className={styles.muted}>이 현의 공사를 맡을 권한이 없습니다.</p>;
    const peopleCol = (
        <>
            <StatusView kind="waiting" title={COUNTY_WAITING.people.title} body={COUNTY_WAITING.people.body} />
            <StatusView kind="waiting" title={COUNTY_WAITING.garrison.title} body={COUNTY_WAITING.garrison.body} />
        </>
    );
    const actions = (
        <HereActions here={here}
            items={HERE_INPUTS.map((inputId) => ({ inputId, label: flowCommand(inputId)?.name ?? inputId, availability: flowCommand(inputId) ? availabilityOf(inputId) : null,
                onAct: () => router.push(hrefs.flow(inputId, target)) }))}
            scout={{ inputId: 'action.scout', label: '첩보 — 명령 목록에 넣기', availability: availabilityOf('action.scout'), onAct: () => router.push(hrefs.flow('action.scout', target)) }} />
    );
    const records = <CountyRecordsLink href={hrefs.records} empty={false} />;

    const cards = posts.data?.cards ?? [];
    const card = sheet?.kind === 'seat' ? cards.find((c) => c.cardId === sheet.cardId) ?? null : null;
    const sheetBody = sheet?.kind === 'seatCard' ? (
        <section className={styles.block} aria-label="현령으로 앉힐 인물">
            <h3 className={styles.sub}>{`${county.data.name} 현령으로 앉힐 인물`}</h3>
            <div role="listbox" aria-label="인물">
                {cards.length === 0 ? <p className={styles.muted}>배치할 NPC 인물이 없습니다(사람 장수는 발령).</p> : cards.map((c) => c.placeable ? (
                    <button key={c.cardId} type="button" role="option" aria-selected="false" className="os-opt" onClick={() => setSheet({ kind: 'seat', cardId: c.cardId })}>
                        <span className="os-opt__text"><span className="os-opt__name">{c.name}</span></span>
                    </button>
                ) : (
                    <ReasonTooltip key={c.cardId} reason={c.blocked?.reason?.trim() || '사유를 받지 못했습니다'} block>
                        {(d) => (
                            <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={d} className="os-opt os-opt--no">
                                <span className="os-opt__text"><span className="os-opt__name">{c.name}</span></span>
                                <span className="os-opt__end"><span className="os-opt__why">{c.blocked?.reason?.trim() || '사유를 받지 못했습니다'}</span></span>
                            </button>
                        )}
                    </ReasonTooltip>
                ))}
            </div>
        </section>
    ) : sheet?.kind === 'seat' && card && posts.data ? (
        <PlacementSheet card={card} posts={posts.data} busy={busy} initialPost="MAGISTRATE" initialTarget={String(cityId)}
            onSubmit={(b) => void submit('placement', b)} onCancel={() => setSheet(null)} />
    ) : sheet?.kind === 'policy' && policy && policies.data ? (
        <PolicySheet policies={policies.data} row={policy} busy={busy} onSubmit={(b) => void submit('policy', b)} onCancel={() => setSheet(null)} />
    ) : sheet?.kind === 'work' && workCounty ? (
        <WorkSheet county={workCounty} busy={busy} onSubmit={(b) => void submit('work', b)} onCancel={() => setSheet(null)} />
    ) : null;
    const modal = sheetBody ? (
        <Modal ariaLabel={`${county.data.name} 입력`} onClose={() => setSheet(null)} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>{sheetBody}</Modal>
    ) : null;

    if (mobile) {
        const body = { state: stateCol, rule: ruleCol, work: workCol, people: peopleCol, events: <>{records}{actions}</> }[tab];
        return (
            <div className={styles.screenMobile}>
                {head}
                {noticeLine}
                <Seg label="보기" value={tab} onChange={setTab} scroll options={[
                    { value: 'state', label: '형편' }, { value: 'rule', label: '다스림' }, { value: 'work', label: '공사' },
                    { value: 'people', label: '사람' }, { value: 'events', label: '사건' },
                ]} />
                <div className={styles.block}>{body}</div>
                {modal}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            {head}
            {noticeLine}
            <div className={styles.columns}>
                <section className={`os-panel ${styles.colState}`} aria-label="형편">{stateCol}</section>
                <section className={`os-panel ${styles.colRule}`} aria-label="다스림">{ruleCol}{workCol}</section>
                <section className={`os-panel ${styles.colRight}`} aria-label="사람 · 사건 · 행동">{peopleCol}{records}{actions}</section>
            </div>
            {modal}
        </div>
    );
}
