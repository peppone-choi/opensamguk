'use client';

import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { Modal, StatusView, useViewportClass, withParticle } from '@opensamguk/ui';
import { campaignReadNotice } from '@/components/campaign/GameStates';
import { IncomingRequests } from '@/components/requests/IncomingRequests';
import { api, isIntakeDenied, isIntakeQueued } from '@/lib/api';
import { useCampaignRead } from '@/lib/campaign-reads';
import { useGameSession } from '@/lib/campaign-session';
import { DISPATCH_QUEUED_TEXT, courtChoices, dispatchCounties, dispatchPeople, issuedDispatches, rewardTargets } from '@/lib/court-view';
import { availabilityOf } from '@/lib/input-availability';
import { useRequests } from '@/lib/requests';
import type { CourtActionId, CourtActionOptions, DispatchOptionsResponse, IntakeOutcome } from '@/lib/types';
import {
    CourtChoiceSheet,
    CourtDecisionCard,
    CourtDecisionList,
    CourtLinks,
    DispatchSheet,
    IssuedDispatches,
    RewardPanel,
    SheetHead,
    type DecisionItem,
} from './CourtParts';
import styles from './court.module.css';

type DecisionId = Extract<CourtActionId, 'court.releaseCorps' | 'court.abandonCounty' | 'court.moveCapital'>;
const DECISIONS: readonly { readonly inputId: DecisionId; readonly title: string; readonly desc: string }[] = [
    { inputId: 'court.releaseCorps', title: '부대 탈퇴 지시', desc: '군단에 든 부대를 빼도록 지시합니다.' },
    { inputId: 'court.abandonCounty', title: '현 포기', desc: '우리 현 하나를 버립니다. 마지막 현 · 수도는 버릴 수 없습니다.' },
    { inputId: 'court.moveCapital', title: '천도', desc: '수도를 다른 우리 현으로 옮깁니다.' },
];

type Sheet = { readonly kind: 'dispatch' } | { readonly kind: 'issued' } | { readonly kind: 'decision'; readonly inputId: DecisionId } | { readonly kind: 'requests' } | { readonly kind: 'reward' };

/** 조정 명령 옵션을 못 받았을 때 — 가능하다고 보이지 않게(사유 시트로) 막는다. */
const OPTIONS_FAILED = '가능 여부를 불러오지 못했습니다 — 다시 시도해 주세요.';

/**
 * 읽기 하나가 정상이 아니면 빈 목록 · 「없습니다」 대신 그릴 것 — 불러오는 중 · 실패(한국어 제목 + 오류 번호 + 다시 시도, 원문은 화면에 두지 않음) ·
 * 서버 상태(옛 형식 월드 등). 정상이면 null — 그때만 목록과 빈 문구를 그린다.
 */
function readState(read: { readonly loading: boolean; readonly error: string | null; readonly data: unknown }, status: string | null | undefined,
    title: string, onRetry: () => void): ReactNode {
    if (read.loading && read.data == null) return <StatusView kind="loading" rows={2} />;
    if (read.error) return <StatusView kind="error" title={title} errorCode={read.error.match(/^\d{3}\b/)?.[0]} onRetry={onRetry} />;
    const notice = campaignReadNotice({ loading: false, error: null }, status);
    return notice ? <StatusView kind="waiting" title={notice} /> : null;
}

export interface CourtScreenProps {
    readonly hrefs: {
        readonly office?: string;
        readonly diplomacy?: string;
        readonly territory: string;
        readonly records?: string;
    };
}

function useCourtOptions(generalId: number | null, reload: number) {
    // 'failed' = 조회 실패 — null(대상 없음)과 섞지 않는다(실패를 「가능」으로 그리지 않게).
    const [opts, setOpts] = useState<Partial<Record<DecisionId, CourtActionOptions | 'failed'>>>({});
    useEffect(() => {
        if (generalId == null) return;
        let live = true;
        for (const d of DECISIONS) {
            api.legacyCourtOptions(d.inputId, generalId)
                .then((o) => { if (live) setOpts((prev) => ({ ...prev, [d.inputId]: o })); })
                .catch(() => { if (live) setOpts((prev) => ({ ...prev, [d.inputId]: 'failed' })); });
        }
        return () => { live = false; };
    }, [generalId, reload]);
    return opts;
}

/**
 * 지금 수도 이름 — 공개 지도 미리보기의 城 표에서 찾는다(옛 조정 구상 화면과 같은 원천, 번호를 그대로 보이지 않는다).
 * 못 받으면 「불러오지 못했습니다」(옛 화면은 실패를 삼켜 「—」 였다 — 설계서 P-K01). 수도가 없으면 null.
 */
function useCapitalName(capitalCityId: number | null): string | null {
    const [name, setName] = useState<string | null>(null);
    useEffect(() => {
        if (capitalCityId == null) { setName(null); return; }
        const controller = new AbortController();
        api.mapPreview(controller.signal)
            .then((p) => setName(p.cities.find((c) => c.id === capitalCityId)?.name ?? '불러오지 못했습니다'))
            .catch(() => { if (!controller.signal.aborted) setName('불러오지 못했습니다'); });
        return () => controller.abort();
    }, [capitalCityId]);
    return name;
}

/**
 * 조정 화면 본문(P-K01) — 위 「받은 요청」 띠(K6 IncomingRequests, 대기만) · 세 칸(발령 · 포상 · 조정 결정) · 고리 카드(관직 · 외교).
 * 모바일은 조정 결정 목록(V3MReason) → 누르면 그 입력의 시트. 조정 결정은 명령 목록 순을 쓰지 않는다.
 * 가능 여부: 발령 · 조정 명령은 서버 옵션(available · 사유), 상사는 원장(비율 · 상한은 K4-15 전 준비 중), 몰수 · 외교는 원장 PLANNED.
 */
export function CourtScreen({ hrefs }: CourtScreenProps) {
    const { generalId, frontInfo } = useGameSession();
    const viewport = useViewportClass();
    const mobile = viewport === null ? null : viewport === 'mobile';
    const [reload, setReload] = useState(0);
    const [sheet, setSheet] = useState<Sheet | null>(null);
    const [busy, setBusy] = useState(false);
    const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null);
    const [target, setTarget] = useState<number | null>(null);
    const [targetReload, setTargetReload] = useState(0);
    const [targetRead, setTargetRead] = useState<{
        generalId: number; target: number; reload: number;
        data: DispatchOptionsResponse | null; error: string | null;
    } | null>(null);
    const requests = useRequests(generalId, reload);
    const pending = useCampaignRead((id) => api.dispatchPending(id), [reload]);
    const options = useCampaignRead((id) => api.dispatchOptions(id), [reload]);
    const retinue = useCampaignRead((id, s) => api.campaignRetinue(id, s), [reload]);
    const court = useCourtOptions(generalId, reload);
    const capital = useCapitalName(frontInfo?.nation?.capitalCityId ?? null);

    useEffect(() => {
        if (generalId == null || target == null) { setTargetRead(null); return; }
        let live = true;
        setTargetRead(null);
        const source = { generalId, target, reload: targetReload };
        api.dispatchOptions(generalId, target)
            .then((data) => { if (live) setTargetRead({ ...source, data, error: null }); })
            .catch((error: unknown) => { if (live) setTargetRead({ ...source, data: null, error: error instanceof Error ? error.message : '' }); });
        return () => { live = false; };
    }, [generalId, target, targetReload]);

    const done = useCallback((out: IntakeOutcome, ok: string) => {
        if (isIntakeQueued(out)) { setNotice({ tone: 'ok', text: ok }); setSheet(null); setTarget(null); setReload((n) => n + 1); }
        else if (isIntakeDenied(out)) setNotice({ tone: 'error', text: out.reason?.trim() || '접수하지 못했습니다.' });
    }, []);
    const run = async (send: () => Promise<IntakeOutcome>, ok: string) => {
        setBusy(true);
        try { done(await send(), ok); } catch { setNotice({ tone: 'error', text: '보내지 못했습니다 — 다시 해 보세요.' }); } finally { setBusy(false); }
    };

    if (mobile === null) return <StatusView kind="loading" rows={5} />;

    const verdict = (o: { result?: boolean; available?: boolean; code?: string | null; reason?: string | null } | null | undefined) =>
        o ? { available: o.available ?? o.result ?? false, code: o.code, reason: o.reason } : null;
    const failed = { available: false, reason: OPTIONS_FAILED };
    const retry = () => setReload((n) => n + 1);
    const dispatchVerdict = options.error ? failed : verdict(options.data);
    const dispatchAvail = availabilityOf('court.dispatch', { options: options.loading ? 'loading' : dispatchVerdict });
    const rewardAvail = availabilityOf('court.reward');
    const confiscateAvail = availabilityOf('court.confiscate');
    const decisionAvail = (id: DecisionId) => {
        const o = court[id];
        return availabilityOf(id, { options: o === undefined ? 'loading' : o === 'failed' ? failed : verdict(o) });
    };
    const choicesOf = (id: DecisionId) => { const o = court[id]; return courtChoices(o === undefined || o === 'failed' ? null : o); };
    const people = dispatchPeople(options.data);
    const issued = issuedDispatches(pending.data, generalId);
    const queued = pending.data?.queued || options.data?.queued ? DISPATCH_QUEUED_TEXT : null;
    const noticeLine = notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null;

    const dispatchCol = (
        <IssuedDispatches rows={issued} queued={queued} availability={dispatchAvail} onNew={() => setSheet({ kind: 'dispatch' })}
            state={readState(pending, null, '내린 발령을 불러오지 못했습니다', retry)}
            noPeople={dispatchVerdict?.available === true && people.length === 0} territoryHref={hrefs.territory} />
    );
    const rewardCol = (
        <RewardPanel targets={rewardTargets(retinue.data)} reward={rewardAvail} confiscate={confiscateAvail} busy={busy}
            onReward={(args) => generalId != null && void run(() => api.courtReward(generalId, args), '상사를 접수했습니다 — 다음 개인 턴에 처리합니다.')}
            onConfiscate={() => {}} recordsHref={hrefs.records}
            state={readState(retinue, retinue.data?.status, '부 인물을 불러오지 못했습니다', retry)} />
    );
    const decisionsCol = (
        <div className={styles.col}>
            {DECISIONS.map((d) => (
                <CourtDecisionCard key={d.inputId} inputId={d.inputId} title={d.title} desc={d.desc} availability={decisionAvail(d.inputId)}
                    onOpen={() => setSheet({ kind: 'decision', inputId: d.inputId })}
                    extra={d.inputId === 'court.moveCapital' && capital ? <p className={styles.muted}>{`지금 수도 — ${capital}`}</p> : undefined} />
            ))}
        </div>
    );

    const closeSheet = () => { setSheet(null); setTarget(null); };
    let sheetBody: ReactNode = null;
    let sheetLabel = '조정 입력';
    if (sheet?.kind === 'dispatch') {
        sheetLabel = '새 발령';
        // Only the current person's current attempt may supply candidates.
        const current = targetRead?.generalId === generalId && targetRead?.target === target && targetRead?.reload === targetReload ? targetRead : null;
        const targetOptions = current?.data;
        const retryTarget = () => setTargetReload((n) => n + 1);
        const countiesState = current == null ? null : current.error != null ? (
            <StatusView kind="error" title="현 후보를 불러오지 못했습니다" errorCode={current.error.match(/^\d{3}\b/)?.[0]} onRetry={retryTarget} />
        ) : targetOptions?.result === false ? (
            <div>
                <StatusView kind="denied" title={targetOptions.reason?.trim() || '이 사람에게 발령할 수 없습니다.'}
                    howTo="직접 거느린 장수인지 확인하거나 다른 사람을 고르세요." />
                <button type="button" className="os-button os-button--ghost" onClick={retryTarget}>다시 시도</button>
            </div>
        ) : null;
        sheetBody = (
            <DispatchSheet people={options.data ? people : null} target={target} onTargetChange={setTarget}
                counties={target != null && targetOptions?.result === true ? dispatchCounties(targetOptions) : null} countiesState={countiesState} busy={busy}
                onSubmit={(args) => generalId != null && void run(() => api.courtDispatch(generalId, args), DISPATCH_QUEUED_TEXT)}
                onCancel={closeSheet} />
        );
    } else if (sheet?.kind === 'decision') {
        const d = DECISIONS.find((x) => x.inputId === sheet.inputId)!;
        sheetLabel = d.title;
        sheetBody = (
            <CourtChoiceSheet inputId={d.inputId} title={d.title} choices={choicesOf(d.inputId)} busy={busy}
                onSubmit={(args) => generalId != null && void run(() => api.courtLegacy(d.inputId, generalId, args as Record<string, string | number>), `${withParticle(d.title, '을/를')} 접수했습니다 — 다음 개인 턴에 처리합니다.`)}
                onCancel={() => setSheet(null)} />
        );
    } else if (sheet?.kind === 'issued') {
        sheetLabel = '발령';
        sheetBody = <><SheetHead title={sheetLabel} onClose={closeSheet} />{dispatchCol}</>;
    } else if (sheet?.kind === 'requests') {
        sheetLabel = '받은 요청';
        sheetBody = <><SheetHead title={sheetLabel} onClose={closeSheet} /><IncomingRequests generalId={generalId} source={requests} waitingOnly compact /></>;
    } else if (sheet?.kind === 'reward') {
        sheetLabel = '포상';
        sheetBody = <><SheetHead title={sheetLabel} onClose={closeSheet} />{rewardCol}</>;
    }
    const modal = sheetBody ? (
        <Modal ariaLabel={sheetLabel} onClose={closeSheet} overlayClassName={mobile ? styles.sheetBottom : styles.sheetRight}>{sheetBody}</Modal>
    ) : null;

    if (mobile) {
        const items: DecisionItem[] = [
            { inputId: 'court.dispatchReply', name: '받은 요청', desc: '발령 · 정치 동의에 답합니다', availability: availabilityOf('court.dispatchReply'),
                waiting: requests.waiting || undefined, onOpen: () => setSheet({ kind: 'requests' }) },
            // 모바일도 내린 발령 목록을 본다(데스크톱 발령 칸과 같은 부품) — 「새 발령」은 그 안에서.
            { inputId: 'court.dispatch', name: '발령', desc: '내 부 사람 장수를 현으로 보냅니다', availability: dispatchAvail, onOpen: () => setSheet({ kind: 'issued' }) },
            { inputId: 'court.reward', name: '포상', desc: '직속 인물에게 창고 금을 내립니다', availability: rewardAvail, onOpen: () => setSheet({ kind: 'reward' }) },
            { inputId: 'court.confiscate', name: '몰수', desc: '인물의 금을 거둡니다', availability: confiscateAvail, onOpen: () => {} },
            ...DECISIONS.map((d) => ({ inputId: d.inputId, name: d.title, desc: d.desc, availability: decisionAvail(d.inputId),
                onOpen: () => setSheet({ kind: 'decision', inputId: d.inputId }) })),
            { inputId: 'court.diplomacy', name: '외교', desc: '다른 세력과의 관계', availability: availabilityOf('court.diplomacy'), onOpen: () => {} },
        ];
        return (
            <div className={styles.screenMobile}>
                <p className={styles.muted}>조정 결정은 명령 목록 순을 쓰지 않습니다.</p>
                {noticeLine}
                <CourtDecisionList items={items} />
                <CourtLinks officeHref={hrefs.office} diplomacyHref={hrefs.diplomacy} />
                {modal}
            </div>
        );
    }
    return (
        <div className={styles.screen}>
            <p className={styles.muted}>조정 결정은 명령 목록 순을 쓰지 않습니다.</p>
            {noticeLine}
            <section className={`os-panel ${styles.band}`} aria-label="받은 요청">
                <IncomingRequests generalId={generalId} source={requests} waitingOnly className={styles.bandList} />
            </section>
            <div className={styles.columns}>
                <section className={`os-panel ${styles.column}`} aria-label="발령">{dispatchCol}</section>
                <section className={`os-panel ${styles.column}`} aria-label="포상">{rewardCol}</section>
                <section className={`os-panel ${styles.column}`} aria-label="조정 결정">{decisionsCol}</section>
            </div>
            <CourtLinks officeHref={hrefs.office} diplomacyHref={hrefs.diplomacy} />
            {modal}
        </div>
    );
}
